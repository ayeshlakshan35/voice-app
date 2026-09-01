package com.ayesha3565.voicemobile

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.facebook.react.module.annotations.ReactModule
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streams backend Gemini PCM chunks directly to the Android speaker.
 * The backend contract is PCM16, mono, little-endian at 24 kHz.
 */
@ReactModule(name = PcmAudioModule.NAME)
class PcmAudioModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {
  companion object {
    const val NAME = "PcmAudio"
    private const val SAMPLE_RATE = 24_000
    private const val MAX_QUEUED_CHUNKS = 30
  }

  private val queue = LinkedBlockingDeque<ByteArray>(MAX_QUEUED_CHUNKS)
  private val executor = Executors.newSingleThreadExecutor()
  private val inputExecutor = Executors.newSingleThreadExecutor()
  private val draining = AtomicBoolean(false)
  @Volatile private var audioTrack: AudioTrack? = null
  @Volatile private var audioRecord: AudioRecord? = null
  @Volatile private var inputRunning = false
  private val audioManager = reactContext.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
  private var echoCanceler: AcousticEchoCanceler? = null
  private var noiseSuppressor: NoiseSuppressor? = null
  private var gainControl: AutomaticGainControl? = null

  override fun getName() = NAME

  @ReactMethod
  fun enqueue(base64Pcm: String, sampleRate: Int) {
    if (sampleRate != SAMPLE_RATE) return

    val pcm = try {
      Base64.decode(base64Pcm, Base64.DEFAULT)
    } catch (_: IllegalArgumentException) {
      return
    }

    if (!queue.offerLast(pcm)) {
      queue.pollFirst()
      queue.offerLast(pcm)
    }
    scheduleDrain()
  }

  @ReactMethod
  fun clear() {
    queue.clear()
    audioTrack?.let { track ->
      try {
        track.pause()
        track.flush()
        track.play()
      } catch (_: IllegalStateException) {
      }
    }
  }

  @ReactMethod
  fun release() {
    queue.clear()
    audioTrack?.release()
    audioTrack = null
    stopInput()
  }

  /**
   * Captures call audio with Android's voice-communication source. Unlike the
   * generic MIC source used by the Expo stream, this enables platform AEC/NS
   * so the agent's speaker output is not sent back to Gemini as user speech.
   */
  @ReactMethod
  fun startInput() {
    if (inputRunning) return

    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
    @Suppress("DEPRECATION")
    audioManager.isSpeakerphoneOn = true

    val minBuffer = AudioRecord.getMinBufferSize(
      16_000,
      AudioFormat.CHANNEL_IN_MONO,
      AudioFormat.ENCODING_PCM_16BIT
    )
    if (minBuffer <= 0) return

    val record = AudioRecord(
      MediaRecorder.AudioSource.VOICE_COMMUNICATION,
      16_000,
      AudioFormat.CHANNEL_IN_MONO,
      AudioFormat.ENCODING_PCM_16BIT,
      maxOf(minBuffer, 3_200)
    )
    if (record.state != AudioRecord.STATE_INITIALIZED) {
      record.release()
      return
    }

    enableInputEffects(record.audioSessionId)
    audioRecord = record
    inputRunning = true
    record.startRecording()

    inputExecutor.execute {
      val buffer = ByteArray(3_200) // 100 ms: PCM16 mono at 16 kHz
      while (inputRunning && audioRecord === record) {
        // The three-argument overload blocks by default and is available from
        // API 3. The read-mode overload was only added in API 23.
        val bytesRead = record.read(buffer, 0, buffer.size)
        if (bytesRead > 0) {
          val event = Arguments.createMap().apply {
            putString("data", Base64.encodeToString(buffer.copyOf(bytesRead), Base64.NO_WRAP))
            putInt("bytes", bytesRead)
          }
          reactApplicationContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit("voicePcmInput", event)
        }
      }
    }
  }

  @ReactMethod
  fun stopInput() {
    inputRunning = false
    audioRecord?.let { record ->
      try {
        record.stop()
      } catch (_: IllegalStateException) {
      }
      record.release()
    }
    audioRecord = null
    echoCanceler?.release(); echoCanceler = null
    noiseSuppressor?.release(); noiseSuppressor = null
    gainControl?.release(); gainControl = null
    audioManager.mode = AudioManager.MODE_NORMAL
  }

  @ReactMethod
  fun addListener(eventName: String) = Unit

  @ReactMethod
  fun removeListeners(count: Int) = Unit

  private fun enableInputEffects(audioSessionId: Int) {
    if (AcousticEchoCanceler.isAvailable()) {
      echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.also { it.enabled = true }
    }
    if (NoiseSuppressor.isAvailable()) {
      noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.also { it.enabled = true }
    }
    if (AutomaticGainControl.isAvailable()) {
      gainControl = AutomaticGainControl.create(audioSessionId)?.also { it.enabled = true }
    }
  }

  private fun scheduleDrain() {
    if (!draining.compareAndSet(false, true)) return
    executor.execute {
      val track = getOrCreateTrack() ?: run {
        draining.set(false)
        return@execute
      }
      try {
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
        while (true) {
          val pcm = queue.pollFirst() ?: break
          var offset = 0
          while (offset < pcm.size) {
            val written = track.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) break
            offset += written
          }
        }
      } catch (_: IllegalStateException) {
      } finally {
        draining.set(false)
        if (!queue.isEmpty()) scheduleDrain()
      }
    }
  }

  private fun getOrCreateTrack(): AudioTrack? {
    audioTrack?.let { return it }
    synchronized(this) {
      audioTrack?.let { return it }
      val minBuffer = AudioTrack.getMinBufferSize(
        SAMPLE_RATE,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT
      )
      if (minBuffer <= 0) return null

      return AudioTrack.Builder()
        .setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        )
        .setAudioFormat(
          AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        )
        .setBufferSizeInBytes(minBuffer * 4)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()
        .also { audioTrack = it }
    }
  }
}

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
import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.facebook.react.module.annotations.ReactModule
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streams backend Gemini PCM chunks directly to the Android speaker.
 * The backend contract is PCM16, mono, little-endian. The sample rate is
 * supplied with each response frame and must match the source PCM.
 */
@ReactModule(name = PcmAudioModule.NAME)
class PcmAudioModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {
  companion object {
    const val NAME = "PcmAudio"
    private const val TAG = "PcmAudio"
    private const val DEFAULT_SAMPLE_RATE = 24_000
    private const val BYTES_PER_PCM_FRAME = 2

    // Start only once we have a small jitter buffer.  This prevents normal
    // WebSocket scheduling jitter from becoming audible gaps between words.
    private const val START_BUFFER_MS = 240
    private const val MAX_BUFFER_MS = 8_000
  }

  private val queueLock = Object()
  // Serializes play/write/flush/release. AudioTrack is not safe to release
  // concurrently with a blocking write on older vendor audio implementations.
  private val trackLock = Object()
  private val queue = ArrayDeque<ByteArray>()
  private var queuedBytes = 0
  private val executor = Executors.newSingleThreadExecutor()
  private val inputExecutor = Executors.newSingleThreadExecutor()
  private val draining = AtomicBoolean(false)
  @Volatile private var playbackGeneration = 0L
  @Volatile private var outputSampleRate = DEFAULT_SAMPLE_RATE
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
    if (sampleRate !in setOf(8_000, 16_000, 24_000, 48_000)) return

    val pcm = try {
      Base64.decode(base64Pcm, Base64.DEFAULT)
    } catch (_: IllegalArgumentException) {
      return
    }

    if (pcm.isEmpty() || pcm.size % BYTES_PER_PCM_FRAME != 0) return

    // A frame larger than the entire queue would otherwise wait forever on
    // the React Native module thread. Reject malformed/oversized packets.
    if (pcm.size > maxBufferBytes(sampleRate)) {
      Log.w(TAG, "Ignoring oversized PCM frame: ${pcm.size} bytes at $sampleRate Hz")
      return
    }

    configureOutputSampleRate(sampleRate)

    // Never discard old PCM.  The old 30-item queue dropped the beginning of
    // a sentence whenever the JS or audio thread was busy briefly, which is
    // heard as speech jumping to a later sentence.  Back-pressure preserves
    // ordering if the producer ever gets ahead of playback.
    synchronized(queueLock) {
      while (queuedBytes + pcm.size > maxBufferBytes()) {
        try {
          queueLock.wait()
        } catch (_: InterruptedException) {
          Thread.currentThread().interrupt()
          return
        }
      }
      queue.addLast(pcm)
      queuedBytes += pcm.size
      queueLock.notifyAll()
    }
    scheduleDrain()
  }

  @ReactMethod
  fun clear() {
    playbackGeneration += 1
    synchronized(queueLock) {
      queue.clear()
      queuedBytes = 0
      queueLock.notifyAll()
    }
    withCurrentTrack("clear") { track ->
      track.pause()
      track.flush()
      track.play()
    }
  }

  @ReactMethod
  fun release() {
    playbackGeneration += 1
    synchronized(queueLock) {
      queue.clear()
      queuedBytes = 0
      queueLock.notifyAll()
    }
    releaseTrack()
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
      val generation = playbackGeneration
      try {
        val track = getOrCreateTrack() ?: run {
          // Avoid repeatedly scheduling a failed track creation for the same
          // queued response. A later response may retry after the route changes.
          synchronized(queueLock) {
            queue.clear()
            queuedBytes = 0
            queueLock.notifyAll()
          }
          return@execute
        }
        // Keep one AudioTrack alive for the entire response.  WebSocket
        // packets are transport frames, not separate clips to play.
        waitForInitialBuffer(generation)
        if (generation != playbackGeneration) return@execute
        if (!startPlayback(track, generation)) return@execute
        while (true) {
          val pcm = takeNextPcm(generation) ?: break
          if (generation != playbackGeneration) continue
          var offset = 0
          while (offset < pcm.size && generation == playbackGeneration) {
            val written = writePcm(track, pcm, offset, pcm.size - offset, generation)
            if (written <= 0) break
            offset += written
          }
        }
      } catch (error: RuntimeException) {
        // This runs on an executor, so an uncaught exception would otherwise
        // terminate the Android process rather than reach React Native.
        Log.e(TAG, "PCM playback failed", error)
      } catch (error: LinkageError) {
        // Keep a production build alive if a vendor runtime is missing an
        // expected framework symbol. API-22 code below does not require API 23.
        Log.e(TAG, "PCM playback API linkage failed", error)
      } finally {
        draining.set(false)
        synchronized(queueLock) {
          if (queue.isNotEmpty()) scheduleDrain()
        }
      }
    }
  }

  private fun waitForInitialBuffer(generation: Long) {
    synchronized(queueLock) {
      while (
        generation == playbackGeneration &&
        queue.isNotEmpty() &&
        queuedBytes < startBufferBytes()
      ) {
        try {
          queueLock.wait()
        } catch (_: InterruptedException) {
          Thread.currentThread().interrupt()
          return
        }
      }
    }
  }

  private fun takeNextPcm(generation: Long): ByteArray? = synchronized(queueLock) {
    while (generation == playbackGeneration && queue.isEmpty()) {
      // There is no "end of response" event in this bridge.  Waiting here
      // keeps the same AudioTrack session ready for the next PCM frame rather
      // than repeatedly stopping and recreating playback at packet boundaries.
      try {
        queueLock.wait()
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        return@synchronized null
      }
    }

    if (generation != playbackGeneration) return@synchronized null

    queue.removeFirst().also { pcm ->
      queuedBytes -= pcm.size
      queueLock.notifyAll()
    }
  }

  private fun getOrCreateTrack(): AudioTrack? {
    audioTrack?.let { return it }
    synchronized(this) {
      audioTrack?.let { return it }
      val sampleRate = outputSampleRate
      val minBuffer = AudioTrack.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT
      )
      if (minBuffer <= 0) {
        Log.w(TAG, "Unsupported PCM output: sampleRate=$sampleRate, minBuffer=$minBuffer")
        return null
      }

      val bufferSize = maxOf(minBuffer * 8, startBufferBytes(sampleRate))
      val track = try {
        // This constructor is available from API 21. The newer builder API
        // starts at API 23 and crashes Android 5.1/API 22 at first response.
        AudioTrack(
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build(),
          AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build(),
          bufferSize,
          AudioTrack.MODE_STREAM,
          AudioManager.AUDIO_SESSION_ID_GENERATE
        )
      } catch (error: IllegalArgumentException) {
        Log.e(TAG, "Invalid PCM output configuration: sampleRate=$sampleRate, bufferSize=$bufferSize", error)
        return null
      } catch (error: UnsupportedOperationException) {
        Log.e(TAG, "PCM output is unsupported by this device", error)
        return null
      }

      if (track.state != AudioTrack.STATE_INITIALIZED) {
        Log.w(TAG, "AudioTrack failed to initialize: sampleRate=$sampleRate, bufferSize=$bufferSize")
        track.release()
        return null
      }

      synchronized(trackLock) {
        audioTrack = track
      }
      return track
    }
  }

  private fun startPlayback(track: AudioTrack, generation: Long): Boolean = synchronized(trackLock) {
    if (audioTrack !== track || generation != playbackGeneration) return@synchronized false
    try {
      if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
      true
    } catch (error: IllegalStateException) {
      Log.e(TAG, "Unable to start PCM playback", error)
      false
    }
  }

  private fun writePcm(
    track: AudioTrack,
    pcm: ByteArray,
    offset: Int,
    size: Int,
    generation: Long
  ): Int = synchronized(trackLock) {
    if (audioTrack !== track || generation != playbackGeneration) return@synchronized 0
    try {
      // API 3. This overload blocks until the requested bytes are queued and
      // is the API-22-compatible equivalent of the API-23 write-mode method.
      track.write(pcm, offset, size)
    } catch (error: IllegalStateException) {
      Log.e(TAG, "Unable to write PCM data", error)
      0
    }
  }

  private fun withCurrentTrack(operation: String, block: (AudioTrack) -> Unit) {
    synchronized(trackLock) {
      val track = audioTrack ?: return
      try {
        block(track)
      } catch (error: IllegalStateException) {
        Log.w(TAG, "AudioTrack $operation failed", error)
      }
    }
  }

  private fun releaseTrack() {
    synchronized(trackLock) {
      val track = audioTrack ?: return
      audioTrack = null
      try {
        track.release()
      } catch (error: IllegalStateException) {
        Log.w(TAG, "AudioTrack release failed", error)
      }
    }
  }

  /**
   * Playing 16 kHz PCM through a 24 kHz AudioTrack speeds it up by 1.5x and
   * makes syllables sound chopped. Reset the stream if the server changes its
   * declared format, then create the next track at the true source rate.
   */
  private fun configureOutputSampleRate(sampleRate: Int) {
    if (sampleRate == outputSampleRate) return

    synchronized(this) {
      if (sampleRate == outputSampleRate) return

      playbackGeneration += 1
      synchronized(queueLock) {
        queue.clear()
        queuedBytes = 0
        queueLock.notifyAll()
      }
      withCurrentTrack("reconfigure") { track ->
        track.pause()
        track.flush()
      }
      releaseTrack()
      outputSampleRate = sampleRate
    }
  }

  private fun startBufferBytes(sampleRate: Int = outputSampleRate) =
    sampleRate * BYTES_PER_PCM_FRAME * START_BUFFER_MS / 1_000

  private fun maxBufferBytes(sampleRate: Int = outputSampleRate) =
    sampleRate * BYTES_PER_PCM_FRAME * MAX_BUFFER_MS / 1_000
}

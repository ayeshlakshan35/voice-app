package com.ayesha3565.voicemobile

import android.net.Uri
import android.util.Log
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.module.annotations.ReactModule
import com.ubtrobot.Robot
import com.ubtrobot.async.ProgressivePromise
import com.ubtrobot.motion.MotionManager
import com.ubtrobot.motion.PerformingException
import com.ubtrobot.motion.PerformingOption
import com.ubtrobot.motion.PerformingProgress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Small, failure-isolated bridge to the Cruzr MotionManager. The SDK's
 * performAction call returns immediately with a cancellable promise; all SDK
 * interaction is nevertheless serialized off React Native's module thread so
 * a robot-service fault can never delay the voice/audio pipeline.
 */
@ReactModule(name = CruzrModule.NAME)
class CruzrModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {
  companion object {
    const val NAME = "Cruzr"
    private const val TAG = "Cruzr"
    private const val SPEAKING_MOTION = "talk1"
    // The documented SDK supports a finite loop count. The active promise is
    // cancelled at response end instead of sending unbounded motion commands.
    private const val SPEAKING_LOOPS = 100
    private val PRESET_MOTIONS = setOf(
      "applause", "celebrate", "goodbye", "nod", "hug", "shankhand",
      "guideright", "guideleft", "swingarm", "searching", "tiaowang",
      "surprise", "shy", "zhanggao", "fadai"
    ) + (1..24).map { "talk$it" }
  }

  private val executor: ExecutorService = Executors.newSingleThreadExecutor()
  private val speakingRequested = AtomicBoolean(false)
  private val promiseLock = Any()
  private var speakingPromise: ProgressivePromise<Void, PerformingException, PerformingProgress>? = null

  override fun getName() = NAME

  @ReactMethod
  fun startSpeakingGesture() {
    if (!speakingRequested.compareAndSet(false, true)) return

    executor.execute {
      if (!speakingRequested.get()) return@execute

      try {
        val manager = motionManagerOrNull() ?: return@execute
        if (manager.isPerformingAction) {
          Log.w(TAG, "[Cruzr] Motion already active; not starting speaking gesture")
          speakingRequested.set(false)
          return@execute
        }

        val option = PerformingOption.Builder(actionUri(SPEAKING_MOTION))
          .setLoops(SPEAKING_LOOPS)
          .build()
        val promise = manager.performAction(option)
        synchronized(promiseLock) {
          speakingPromise = promise
        }
        Log.i(TAG, "[Cruzr] Starting speaking gesture: $SPEAKING_MOTION")

        // A stop request may have arrived while the SDK call was queued.
        if (!speakingRequested.get()) cancelSpeakingPromise()
      } catch (error: RuntimeException) {
        speakingRequested.set(false)
        Log.e(TAG, "[Cruzr] Unable to start speaking gesture", error)
      } catch (error: LinkageError) {
        speakingRequested.set(false)
        Log.e(TAG, "[Cruzr] Cruzr runtime is unavailable", error)
      }
    }
  }

  @ReactMethod
  fun stopSpeakingGesture() {
    if (!speakingRequested.getAndSet(false)) return

    executor.execute {
      cancelSpeakingPromise()
      Log.i(TAG, "[Cruzr] Stopping speaking gesture")
    }
  }

  /** Exposes a one-shot documented preset for safe hardware verification. */
  @ReactMethod
  fun performMotion(name: String) {
    val motion = name.trim().lowercase()
    if (motion !in PRESET_MOTIONS) {
      Log.w(TAG, "[Cruzr] Ignoring unsupported preset motion: $name")
      return
    }

    executor.execute {
      try {
        val manager = motionManagerOrNull() ?: return@execute
        if (manager.isPerformingAction) {
          Log.w(TAG, "[Cruzr] Motion already active; not starting $motion")
          return@execute
        }
        manager.performAction(actionUri(motion))
        Log.i(TAG, "[Cruzr] Performing motion: $motion")
      } catch (error: RuntimeException) {
        Log.e(TAG, "[Cruzr] Motion error: $motion", error)
      } catch (error: LinkageError) {
        Log.e(TAG, "[Cruzr] Cruzr runtime is unavailable", error)
      }
    }
  }

  private fun motionManagerOrNull(): MotionManager? = try {
    val context = Robot.globalContext()
    val manager = context?.getSystemService<MotionManager>(MotionManager.SERVICE)
    if (manager == null) Log.w(TAG, "[Cruzr] MotionManager is unavailable")
    else Log.d(TAG, "[Cruzr] MotionManager initialized")
    manager
  } catch (error: RuntimeException) {
    Log.e(TAG, "[Cruzr] Could not obtain MotionManager", error)
    null
  } catch (error: LinkageError) {
    Log.e(TAG, "[Cruzr] Cruzr runtime is unavailable", error)
    null
  }

  private fun cancelSpeakingPromise() {
    val promise = synchronized(promiseLock) {
      speakingPromise.also { speakingPromise = null }
    }
    if (promise != null && !promise.isCanceled) {
      try {
        promise.cancel()
      } catch (error: RuntimeException) {
        Log.e(TAG, "[Cruzr] Could not cancel speaking gesture", error)
      }
    }
  }

  private fun actionUri(name: String): Uri = Uri.parse("action://ubtrobot/$name")

  override fun invalidate() {
    speakingRequested.set(false)
    executor.execute { cancelSpeakingPromise() }
    executor.shutdown()
    super.invalidate()
  }
}

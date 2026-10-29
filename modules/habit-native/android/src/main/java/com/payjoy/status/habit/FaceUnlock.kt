package com.payjoy.status.habit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.FaceDetector
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * Unlock method C: two face checks in sequence (two people, one after another).
 *
 * Enforcement is native: a session must be started, each capture must contain a face,
 * and the second capture must come at least 3 seconds after the first. Android's
 * offline [FaceDetector] is used — nothing leaves the phone and no images are stored.
 *
 * Honest limit: this verifies that two separate face photos were taken, not the identity
 * of the people in them.
 */
object FaceUnlock {
  private const val TAG = "FaceUnlock"
  private const val MIN_GAP_MS = 3_000L
  private const val MAX_FACES = 4
  private const val TARGET_WIDTH = 720

  @Volatile private var stage = 0
  @Volatile private var firstCaptureElapsed = 0L

  fun start(context: Context): Boolean {
    if (HabitStore.state(context).mode == ProtectionState.MODE_NORMAL) return false
    if (HabitUnlock.lockRemainingMs(context) > 0L) return false
    stage = 1
    firstCaptureElapsed = 0L
    return true
  }

  fun cancel() {
    stage = 0
    firstCaptureElapsed = 0L
  }

  fun submit(context: Context, uriString: String): Map<String, Any?> {
    if (stage == 0) return result("error", -1, "Face unlock was not started.")

    val locked = HabitUnlock.lockRemainingMs(context)
    if (locked > 0L) {
      cancel()
      return result("error", -1, "Too many attempts. Wait before trying again.")
    }

    val now = SystemClock.elapsedRealtime()
    if (stage == 2 && now - firstCaptureElapsed < MIN_GAP_MS) {
      return result("retry", -1, "Wait a moment, then take the second photo.")
    }

    val faces = try {
      countFaces(context, uriString)
    } catch (error: Throwable) {
      Log.w(TAG, "Face check failed: ${error.message}")
      -1
    } finally {
      // The camera writes each capture to the cache; remove it right after counting.
      deleteCapture(context, uriString)
    }

    if (faces <= 0) {
      HabitUnlock.recordFailure(context)
      val message = when (faces) {
        0 -> "No face found. Hold the phone at eye level in good light."
        else -> "That photo could not be read. Try again."
      }
      return result("retry", maxOf(faces, 0), message)
    }

    if (stage == 1) {
      stage = 2
      firstCaptureElapsed = now
      return result("first", faces, "First face confirmed. Hand the phone to the second person.")
    }

    stage = 0
    firstCaptureElapsed = 0L
    HabitUnlock.completeUnlock(context)
    return result("unlocked", faces, "Both faces confirmed. Protection is off.")
  }

  /** Counts faces in a captured photo. Returns -1 when the image can't be decoded. */
  fun countFaces(context: Context, uriString: String): Int {
    val source = decode(context, uriString) ?: return -1
    var crop: Bitmap? = null
    var converted: Bitmap? = null
    try {
      val evenWidth = source.width - (source.width % 2)
      val evenHeight = source.height - (source.height % 2)
      if (evenWidth <= 0 || evenHeight <= 0) return -1

      val base: Bitmap = if (evenWidth == source.width && evenHeight == source.height) {
        source
      } else {
        Bitmap.createBitmap(source, 0, 0, evenWidth, evenHeight).also { crop = it }
      }

      // FaceDetector only accepts RGB_565 bitmaps.
      val bitmap: Bitmap = if (base.config == Bitmap.Config.RGB_565) {
        base
      } else {
        val copy = base.copy(Bitmap.Config.RGB_565, false) ?: return -1
        converted = copy
        copy
      }

      val detector = FaceDetector(bitmap.width, bitmap.height, MAX_FACES)
      @Suppress("UNCHECKED_CAST")
      val faces = arrayOfNulls<FaceDetector.Face>(MAX_FACES) as Array<FaceDetector.Face>
      return detector.findFaces(bitmap, faces)
    } finally {
      converted?.recycle()
      crop?.recycle()
      source.recycle()
    }
  }

  private fun decode(context: Context, uriString: String): Bitmap? {
    val uri = try {
      Uri.parse(uriString)
    } catch (error: Throwable) {
      return null
    }
    if (uri.scheme.isNullOrEmpty()) return null

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    try {
      context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, bounds)
      }
    } catch (error: Throwable) {
      Log.w(TAG, "Could not open photo: ${error.message}")
      return null
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (bounds.outWidth / (sample * 2) >= TARGET_WIDTH && sample < 8) sample *= 2
    val options = BitmapFactory.Options().apply {
      inSampleSize = sample
      inPreferredConfig = Bitmap.Config.RGB_565
    }
    return try {
      context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, options)
      }
    } catch (error: Throwable) {
      Log.w(TAG, "Could not decode photo: ${error.message}")
      null
    }
  }

  private fun deleteCapture(context: Context, uriString: String) {
    try {
      val uri = Uri.parse(uriString)
      when (uri.scheme) {
        "content" -> context.contentResolver.delete(uri, null, null)
        "file" -> uri.path?.let { path -> File(path).delete() }
        else -> Unit
      }
    } catch (error: Throwable) {
      Log.w(TAG, "Could not remove capture: ${error.message}")
    }
  }

  private fun result(status: String, faces: Int, message: String): Map<String, Any?> {
    return mapOf(
      "status" to status,
      "faces" to faces,
      "stage" to stage,
      "message" to message
    )
  }
}

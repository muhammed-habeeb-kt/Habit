package com.payjoy.status.habit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.media.FaceDetector
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import java.io.File

private fun luma(pixel: Int): Double {
  val red = (pixel shr 16) and 0xFF
  val green = (pixel shr 8) and 0xFF
  val blue = pixel and 0xFF
  return (0.299 * red + 0.587 * green + 0.114 * blue) / 255.0
}

/**
 * Unlock method C: two people, one after another, each proving they are really there
 * with a slow blink.
 *
 * The flow is a native state machine:
 *   stage 1 (person 1) -> stage 2 (person 2) -> unlocked
 *
 * A burst of camera frames is reduced in memory to:
 *   - the number of faces in the frame,
 *   - the average brightness of the eye band (a blink changes it sharply),
 *   - a small, mean-centred 6x6 brightness signature of the face (person 2's signature
 *     must differ from person 1's, so one person cannot step in twice).
 *
 * Nothing leaves the phone and no image is kept: each capture is decoded, sampled and
 * recycled, then its temporary file is deleted.
 *
 * Honest limit: this proves two live, blinking, visually different faces were in front of
 * the camera. It is not identity recognition — it cannot say *who* the people are.
 */
object FaceUnlock {
  private const val TAG = "FaceUnlock"
  /** Short hand-off: the second burst cannot finish sooner than this after the first. */
  private const val MIN_GAP_MS = 1_500L
  private const val MAX_FACES = 2
  private const val TARGET_WIDTH = 640
  private const val MAX_FRAMES = 8
  private const val GRID = 6
  /** Eye-band brightness must move by at least this much (0..1) across the burst. */
  private const val BLINK_DELTA = 0.05
  /** Mean absolute difference of the face signatures needed to count as another person. */
  private const val PERSON_DELTA = 0.035

  /** 0 = idle, 1 = person 1 scanning, 2 = person 2 scanning. */
  @Volatile private var stage = 0
  @Volatile private var firstVerifiedElapsed = 0L
  @Volatile private var firstSignature: DoubleArray? = null

  // region session

  @Synchronized
  fun start(context: Context): Boolean {
    if (HabitStore.state(context).mode == ProtectionState.MODE_NORMAL) return false
    if (HabitUnlock.lockRemainingMs(context) > 0L) return false
    reset()
    stage = 1
    return true
  }

  @Synchronized
  fun cancel() {
    reset()
  }

  private fun reset() {
    stage = 0
    firstVerifiedElapsed = 0L
    firstSignature = null
  }

  // endregion

  // region verification

  /**
   * Checks one blink burst. Returns `first` (person 1 verified), `unlocked` (both people
   * verified, 30-minute OFF window started), `retry` (keep scanning) or `error`.
   */
  @Synchronized
  fun submit(context: Context, frames: List<String>): Map<String, Any?> {
    if (stage == 0) return result("error", -1, "Face check was not started.")
    if (frames.isEmpty()) return result("error", -1, "No camera frames were captured.")

    val locked = HabitUnlock.lockRemainingMs(context)
    if (locked > 0L) {
      reset()
      return result("error", -1, "Too many attempts. Wait before trying again.")
    }

    val now = SystemClock.elapsedRealtime()
    if (stage == 2 && now - firstVerifiedElapsed < MIN_GAP_MS) {
      return result("retry", -1, "Take a breath, then let the second person blink.")
    }

    val samples = ArrayList<Sample>()
    try {
      for (uri in frames.take(MAX_FRAMES)) {
        analyze(context, uri)?.let { samples.add(it) }
      }
    } finally {
      // The camera writes each capture to the cache; remove it right after sampling.
      frames.forEach { deleteCapture(context, it) }
    }

    if (samples.isEmpty()) {
      HabitUnlock.recordFailure(context)
      return result("retry", -1, "Those frames could not be read. Try again in better light.")
    }

    val mostFaces = samples.maxOf { it.faces }
    if (samples.any { it.faces > 1 }) {
      return result("retry", mostFaces, "Only one face at a time. Ask the other person to step back.")
    }

    val single = samples.filter { it.faces == 1 && it.signature != null }
    if (single.size < 2) {
      HabitUnlock.recordFailure(context)
      val message = if (mostFaces <= 0) {
        "No face found. Hold the phone at eye level in good light."
      } else {
        "Keep your whole face in the circle, then blink slowly."
      }
      return result("retry", maxOf(mostFaces, 0), message)
    }

    val bright = single.maxByOrNull { it.eye }
      ?: return result("retry", 1, "Could not read that face. Try again.")
    val dim = single.minByOrNull { it.eye } ?: bright
    if (bright.eye - dim.eye < BLINK_DELTA) {
      return result("retry", 1, "No blink detected. Blink slowly once and keep the phone still.")
    }

    val signature = bright.signature
      ?: return result("retry", 1, "Could not read that face. Try again.")

    if (stage == 1) {
      firstSignature = signature
      firstVerifiedElapsed = now
      stage = 2
      return result("first", 1, "Person 1 verified. Pass the phone to Person 2.")
    }

    val previous = firstSignature
    if (previous == null) {
      reset()
      return result("error", -1, "The first check expired. Start again.")
    }
    if (meanAbsDifference(previous, signature) < PERSON_DELTA) {
      return result("retry", 1, "That looks like the same face. Ask the second person to hold the phone.")
    }

    reset()
    HabitUnlock.completeFaceUnlock(context)
    return result("unlocked", 1, "Two faces verified. Protection is off for 30 minutes.")
  }

  private fun result(status: String, faces: Int, message: String): Map<String, Any?> {
    return mapOf(
      "status" to status,
      "faces" to faces,
      "stage" to stage,
      "message" to message
    )
  }

  // endregion

  // region frame analysis

  private class Sample(val faces: Int, val eye: Double, val signature: DoubleArray?)

  /** Decodes one capture, counts faces and samples the eye band and the face box. */
  private fun analyze(context: Context, uriString: String): Sample? {
    val source = decode(context, uriString) ?: return null
    var crop: Bitmap? = null
    var converted: Bitmap? = null
    try {
      // FaceDetector only accepts evenly sized RGB_565 bitmaps.
      val evenWidth = source.width - (source.width % 2)
      val evenHeight = source.height - (source.height % 2)
      if (evenWidth <= 0 || evenHeight <= 0) return null

      val base: Bitmap = if (evenWidth == source.width && evenHeight == source.height) {
        source
      } else {
        Bitmap.createBitmap(source, 0, 0, evenWidth, evenHeight).also { crop = it }
      }
      // Cropping can hand back an ARGB bitmap, which FaceDetector rejects.
      val frame: Bitmap = if (base.config == Bitmap.Config.RGB_565) {
        base
      } else {
        base.copy(Bitmap.Config.RGB_565, false)?.also { converted = it } ?: return null
      }

      @Suppress("UNCHECKED_CAST")
      val faces = arrayOfNulls<FaceDetector.Face>(MAX_FACES) as Array<FaceDetector.Face>
      val detector = FaceDetector(frame.width, frame.height, MAX_FACES)
      val found = detector.findFaces(frame, faces)
      if (found <= 0) return Sample(found, 0.0, null)

      var best: FaceDetector.Face? = null
      var bestDistance = 0f
      for (index in 0 until found) {
        val face = faces[index] ?: continue
        val distance = face.eyesDistance()
        if (distance > bestDistance) {
          bestDistance = distance
          best = face
        }
      }
      val face = best ?: return Sample(found, 0.0, null)
      if (bestDistance <= 1f) return Sample(found, 0.0, null)

      val midpoint = PointF()
      face.getMidPoint(midpoint)

      // getMidPoint() sits between the eyes, so the eye band is a thin strip on that line.
      val eyeRegion = readRegion(
        frame,
        midpoint.x - bestDistance * 0.85f,
        midpoint.y - bestDistance * 0.38f,
        midpoint.x + bestDistance * 0.85f,
        midpoint.y + bestDistance * 0.38f
      ) ?: return Sample(found, 0.0, null)

      val faceRegion = readRegion(
        frame,
        midpoint.x - bestDistance * 1.5f,
        midpoint.y - bestDistance * 1.4f,
        midpoint.x + bestDistance * 1.5f,
        midpoint.y + bestDistance * 2.2f
      ) ?: return Sample(found, 0.0, null)

      return Sample(found, eyeRegion.mean(), faceRegion.normalizedGrid(GRID))
    } catch (error: Throwable) {
      Log.w(TAG, "Face frame failed: ${error.message}")
      return null
    } finally {
      converted?.recycle()
      crop?.recycle()
      source.recycle()
    }
  }

  private class Region(val width: Int, val height: Int, val pixels: IntArray) {
    fun mean(): Double {
      if (pixels.isEmpty()) return 0.0
      var total = 0.0
      for (pixel in pixels) total += luma(pixel)
      return total / pixels.size
    }

    /** Coarse brightness grid, mean-centred so exposure changes cancel out. */
    fun normalizedGrid(size: Int): DoubleArray {
      val cells = DoubleArray(size * size)
      val counts = IntArray(size * size)
      for (y in 0 until height) {
        val row = (y * size / height).coerceIn(0, size - 1)
        for (x in 0 until width) {
          val column = (x * size / width).coerceIn(0, size - 1)
          val index = row * size + column
          cells[index] += luma(pixels[y * width + x])
          counts[index]++
        }
      }
      for (index in cells.indices) {
        if (counts[index] > 0) cells[index] /= counts[index]
      }
      val average = cells.average()
      for (index in cells.indices) cells[index] -= average
      return cells
    }
  }

  private fun readRegion(
    bitmap: Bitmap,
    leftF: Float,
    topF: Float,
    rightF: Float,
    bottomF: Float
  ): Region? {
    val left = maxOf(0, leftF.toInt())
    val top = maxOf(0, topF.toInt())
    val right = minOf(bitmap.width, rightF.toInt())
    val bottom = minOf(bitmap.height, bottomF.toInt())
    val width = right - left
    val height = bottom - top
    if (width < 4 || height < 4) return null
    return try {
      val pixels = IntArray(width * height)
      bitmap.getPixels(pixels, 0, width, left, top, width, height)
      Region(width, height, pixels)
    } catch (error: Throwable) {
      null
    }
  }

  private fun meanAbsDifference(first: DoubleArray, second: DoubleArray): Double {
    val size = minOf(first.size, second.size)
    if (size == 0) return 0.0
    var total = 0.0
    for (index in 0 until size) total += kotlin.math.abs(first[index] - second[index])
    return total / size
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

  // endregion
}

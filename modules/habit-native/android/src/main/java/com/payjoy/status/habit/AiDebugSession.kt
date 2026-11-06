package com.payjoy.status.habit

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Debug session for the AI scanner: one downscaled JPEG plus one CSV row per scanned frame,
 * kept inside `filesDir/ai_debug_session/` and exported on demand into the phone's public
 * `Download` folder.
 *
 * This exists only for threshold calibration and bug reports, so it is deliberately plain:
 *  - `frames/frame_0001.jpg` is the whole screenshot, scaled to [FRAME_WIDTH] px wide.
 *  - `scan_log.csv` has one header line and one line per frame, with both model scores,
 *    the winning tile, the strikes and whether the cascade would have blocked.
 *  - Export copies a standalone CSV and a ZIP of the CSV + frames into `Download/` through
 *    `MediaStore.Downloads`, which needs no storage permission on Android 10+.
 *
 * Nothing is uploaded, and the session is removed by [clear] (or by uninstalling Habit).
 */
object AiDebugSession {
  private const val TAG = "AiDebugSession"
  private const val SESSION_DIR = "ai_debug_session"
  private const val FRAMES_DIR = "frames"
  private const val CSV_NAME = "scan_log.csv"
  private const val FRAME_PREFIX = "frame_"
  private const val FRAME_SUFFIX = ".jpg"
  private const val FRAME_WIDTH = 540
  private const val JPEG_QUALITY = 72
  private const val CSV_MIME = "text/csv"
  private const val ZIP_MIME = "application/zip"

  private val HEADER = listOf(
    "frame_id",
    "image_file",
    "timestamp",
    "package_name",
    "sensitivity_mode",
    "s_mob_max",
    "winning_tile",
    "mob_top",
    "mob_center",
    "mob_bottom",
    "p_porn",
    "p_hentai",
    "p_sexy",
    "p_neutral",
    "p_drawing",
    "s_nude_max",
    "nude_top_class",
    "nude_top_tile",
    "unsure_strikes",
    "would_block",
    "verdict_reason",
    "stage1_ms",
    "stage2_ms",
    "total_ms"
  )

  private val lock = Any()
  private val stampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
  private val exportStampFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

  /** -1 until the real count is read from disk; kept in sync by [record] and [clear]. */
  @Volatile private var cachedFrames = -1
  @Volatile private var nextFrameId = -1

  /** Everything one analyzed frame contributes to the CSV, minus the id and file name. */
  data class FrameRecord(
    val packageName: String,
    val sensitivity: String,
    val mobMax: Float,
    val winningTile: String,
    val mobTop: Float,
    val mobCenter: Float,
    val mobBottom: Float,
    val porn: Float,
    val hentai: Float,
    val sexy: Float,
    val neutral: Float,
    val drawing: Float,
    val nudeMax: Float,
    val nudeTopClass: String,
    val nudeTopTile: String,
    val unsureStrikes: Int,
    val wouldBlock: Boolean,
    val verdictReason: String,
    val stage1Ms: Long,
    val stage2Ms: Long,
    val totalMs: Long
  )

  fun sessionDir(context: Context): File = File(context.filesDir, SESSION_DIR)

  fun framesDir(context: Context): File = File(sessionDir(context), FRAMES_DIR)

  fun csvFile(context: Context): File = File(sessionDir(context), CSV_NAME)

  /** Frames currently stored in the session; used for the live counter in the Apps tab. */
  fun recordedFrames(context: Context): Int {
    val cached = cachedFrames
    if (cached >= 0) return cached
    val counted = countFrames(context)
    cachedFrames = counted
    return counted
  }

  /** Frames recorded so far, read from memory; -1 until the folder was counted once. */
  fun cachedFrameCount(): Int = cachedFrames

  /**
   * Saves [screen] as the next frame and appends its CSV row.
   *
   * Returns the frame id, or -1 when the frame could not be written; the caller records
   * that as a visible scanner error instead of losing the frame silently.
   */
  fun record(context: Context, screen: Bitmap, frame: FrameRecord): Int {
    synchronized(lock) {
      return try {
        val frames = framesDir(context)
        if (!frames.exists() && !frames.mkdirs()) {
          throw IllegalStateException("could not create ${frames.absolutePath}")
        }
        val id = nextId(context)
        val fileName = frameName(id)
        val frameFile = File(frames, fileName)
        writeFrame(screen, frameFile)

        val csv = csvFile(context)
        val fresh = !csv.exists() || csv.length() == 0L
        csv.appendText(buildString {
          if (fresh) {
            append(HEADER.joinToString(","))
            append('\n')
          }
          append(csvRow(id, "$FRAMES_DIR/$fileName", frame))
          append('\n')
        })

        nextFrameId = id + 1
        cachedFrames = if (cachedFrames < 0) countFrames(context) else cachedFrames + 1
        id
      } catch (error: Throwable) {
        Log.w(TAG, "Could not record frame: ${describe(error)}")
        -1
      }
    }
  }

  /**
   * Copies a standalone CSV and a ZIP (CSV + frames) into the public `Download` folder and
   * returns the exact file paths. On Android 10+ this uses `MediaStore.Downloads`, so no
   * storage permission is involved.
   */
  fun export(context: Context): Map<String, Any?> {
    val csv = csvFile(context)
    if (!csv.exists() || csv.length() == 0L) {
      return exportResult(false, null, null, 0, "nothing recorded yet")
    }
    val frames = countFrames(context)
    if (frames <= 0) {
      return exportResult(false, null, null, 0, "no frame images recorded yet")
    }

    val stamp = exportStampFormat.format(Date())
    val csvName = "Habit_scan_log_$stamp.csv"
    val zipName = "Habit_debug_session_$stamp.zip"
    val zipTemp = File(context.cacheDir, zipName)

    return try {
      buildZip(context, zipTemp)
      val csvPath = copyIntoDownloads(context, csvName, CSV_MIME) { output ->
        csv.inputStream().use { input -> input.copyTo(output) }
      }
      val zipPath = copyIntoDownloads(context, zipName, ZIP_MIME) { output ->
        zipTemp.inputStream().use { input -> input.copyTo(output) }
      }
      exportResult(true, csvPath, zipPath, frames, null)
    } catch (error: Throwable) {
      Log.w(TAG, "Export failed: ${describe(error)}")
      exportResult(false, null, null, frames, describe(error))
    } finally {
      if (zipTemp.exists() && !zipTemp.delete()) {
        Log.w(TAG, "Could not delete temporary zip ${zipTemp.absolutePath}")
      }
    }
  }

  /** Deletes the recorded CSV and every frame, and resets the live counter. */
  fun clear(context: Context): Map<String, Any?> {
    synchronized(lock) {
      var removed = 0
      var error: String? = null
      try {
        val frames = framesDir(context)
        val files = frames.listFiles()
        if (files != null) {
          for (file in files) {
            if (file.isFile && file.delete()) removed++
          }
        }
        val csv = csvFile(context)
        if (csv.exists() && !csv.delete()) {
          error = "could not delete ${csv.absolutePath}"
        }
      } catch (throwable: Throwable) {
        error = describe(throwable)
        Log.w(TAG, "Clear failed: $error")
      }
      nextFrameId = 1
      cachedFrames = 0
      return mapOf(
        "ok" to (error == null),
        "framesRemoved" to removed,
        "error" to error
      )
    }
  }

  // region internals

  /** Frame numbers start at 1 (`frame_0001.jpg`) and continue after a restart. */
  private fun nextId(context: Context): Int {
    if (nextFrameId >= 1) return nextFrameId
    var highest = 0
    val files = framesDir(context).listFiles()
    if (files != null) {
      for (file in files) {
        val name = file.name
        if (!name.startsWith(FRAME_PREFIX) || !name.endsWith(FRAME_SUFFIX)) continue
        val number = name
          .substring(FRAME_PREFIX.length, name.length - FRAME_SUFFIX.length)
          .toIntOrNull() ?: continue
        if (number > highest) highest = number
      }
    }
    nextFrameId = highest + 1
    return nextFrameId
  }

  private fun frameName(id: Int): String =
    FRAME_PREFIX + id.toString().padStart(4, '0') + FRAME_SUFFIX

  private fun countFrames(context: Context): Int {
    return try {
      val files = framesDir(context).listFiles() ?: return 0
      files.count { it.isFile && it.name.startsWith(FRAME_PREFIX) && it.name.endsWith(FRAME_SUFFIX) }
    } catch (error: Throwable) {
      0
    }
  }

  private fun writeFrame(screen: Bitmap, target: File) {
    val width = FRAME_WIDTH.coerceAtMost(screen.width)
    val height = (screen.height.toLong() * width / screen.width).toInt().coerceAtLeast(1)
    val scaled = if (width == screen.width && height == screen.height) {
      screen
    } else {
      Bitmap.createScaledBitmap(screen, width, height, true)
    }
    try {
      FileOutputStream(target).use { output ->
        if (!scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
          throw IllegalStateException("JPEG encoder refused the frame")
        }
      }
    } finally {
      if (scaled !== screen && !scaled.isRecycled) scaled.recycle()
    }
  }

  private fun csvRow(id: Int, imageFile: String, record: FrameRecord): String {
    val cells = listOf(
      id.toString(),
      imageFile,
      stampFormat.format(Date()),
      record.packageName,
      record.sensitivity,
      decimal(record.mobMax),
      record.winningTile,
      decimal(record.mobTop),
      decimal(record.mobCenter),
      decimal(record.mobBottom),
      decimal(record.porn),
      decimal(record.hentai),
      decimal(record.sexy),
      decimal(record.neutral),
      decimal(record.drawing),
      decimal(record.nudeMax),
      record.nudeTopClass,
      record.nudeTopTile,
      record.unsureStrikes.toString(),
      record.wouldBlock.toString(),
      record.verdictReason,
      record.stage1Ms.toString(),
      record.stage2Ms.toString(),
      record.totalMs.toString()
    )
    return cells.joinToString(",") { escape(it) }
  }

  private fun decimal(value: Float): String =
    String.format(Locale.US, "%.4f", value)

  /** RFC 4180 style: a value with a comma, quote or newline is quoted and its quotes doubled. */
  private fun escape(value: String): String {
    val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
    if (!needsQuotes) return value
    return "\"" + value.replace("\"", "\"\"") + "\""
  }

  /** Builds the ZIP in the cache directory so the export itself stays a single copy. */
  private fun buildZip(context: Context, target: File) {
    val csv = csvFile(context)
    val frames = framesDir(context)
    ZipOutputStream(target.outputStream().buffered()).use { zip ->
      putEntry(zip, CSV_NAME) { output -> csv.inputStream().use { it.copyTo(output) } }
      val files = frames.listFiles() ?: emptyArray()
      for (file in files.sortedBy { it.name }) {
        if (!file.isFile || !file.name.endsWith(FRAME_SUFFIX)) continue
        putEntry(zip, "$FRAMES_DIR/${file.name}") { output ->
          file.inputStream().use { it.copyTo(output) }
        }
      }
    }
  }

  private fun putEntry(zip: ZipOutputStream, name: String, write: (OutputStream) -> Unit) {
    zip.putNextEntry(ZipEntry(name))
    try {
      write(zip)
    } finally {
      zip.closeEntry()
    }
  }

  /**
   * Writes one exported file into the public Download folder.
   *
   * Android 10+ goes through `MediaStore.Downloads` (no storage permission). Older
   * releases fall back to a direct write into `Download/`, which needs the legacy storage
   * permission and reports a readable error when Android refuses it.
   */
  private fun copyIntoDownloads(
    context: Context,
    displayName: String,
    mimeType: String,
    write: (OutputStream) -> Unit
  ): String {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val resolver = context.contentResolver
      val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
      }
      val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
      val uri = resolver.insert(collection, values)
        ?: throw IllegalStateException("Android refused to create Download/$displayName")
      try {
        resolver.openOutputStream(uri)?.use(write)
          ?: throw IllegalStateException("Android returned no file stream for Download/$displayName")
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)
      } catch (error: Throwable) {
        try {
          resolver.delete(uri, null, null)
        } catch (deleteError: Throwable) {
          Log.w(TAG, "Could not remove the failed export: ${deleteError.message}")
        }
        throw error
      }
      return "Download/$displayName"
    }

    val directory = downloadsDirectory()
    val file = File(directory, displayName)
    FileOutputStream(file).use(write)
    return file.absolutePath
  }

  @Suppress("DEPRECATION")
  private fun downloadsDirectory(): File {
    val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    if (!directory.exists() && !directory.mkdirs()) {
      throw IllegalStateException("could not create ${directory.absolutePath}")
    }
    return directory
  }

  private fun exportResult(
    ok: Boolean,
    csvPath: String?,
    zipPath: String?,
    frames: Int,
    error: String?
  ): Map<String, Any?> = mapOf(
    "ok" to ok,
    "csvPath" to csvPath,
    "zipPath" to zipPath,
    "frames" to frames,
    "error" to error
  )

  private fun describe(error: Throwable): String {
    val message = error.message?.trim()
    return if (message.isNullOrEmpty()) error.javaClass.simpleName else message
  }

  // endregion
}

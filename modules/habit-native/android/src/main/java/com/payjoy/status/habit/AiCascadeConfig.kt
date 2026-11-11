package com.payjoy.status.habit

import android.content.Context

/**
 * Every tunable of the on-device AI NSFW screenshot cascade lives here.
 *
 * The pipeline is two-stage so a normal screen costs at most one small classifier run:
 *  - Stage 1 is a 224x224 MobileNetV2 NSFW classifier (`.tflite`, LiteRT). Its score is
 *    `p_porn + p_hentai + sexyWeight * p_sexy`, so glamour, bikini, cleavage and lingerie
 *    frames score high even when nothing is explicitly exposed.
 *  - Stage 2 is a 320x320 NudeNet YOLOv8n detector (`.onnx`, ONNX Runtime) and is additive:
 *    it only runs on the tiles stage 1 found suspicious, and it can flag an explicit frame
 *    stage 1 was unsure about.
 *  - a two-screen confirmation governs blocking: a positive frame only flags the screen,
 *    and the block flow runs on the next positive frame. A clearly explicit frame bypasses
 *    the wait and blocks immediately.
 *
 * Power is kept down by three gates:
 *  - a 16x16 pixel-diff check skips the whole cascade when the screen did not change,
 *  - stage 2 only runs on tiles that crossed [STAGE2_GATE_SCORE] / the explicit gates,
 *  - a run of safe frames relaxes the capture interval to [RELAXED_CAPTURE_INTERVAL_MS].
 *
 * The POSITIVE levels below are fixed for every sensitivity preset. The presets only change
 * how strongly `p_sexy` counts in the combined stage 1 score. Models are downloaded into
 * `src/main/assets` by `scripts/download-ai-models.sh`.
 */
object AiCascadeConfig {
  const val STAGE1_ASSET = "nsfw_mobilenet_v2.tflite"
  const val STAGE2_ASSET = "nudenet_320n.onnx"

  /** One frame at a time, no queue: never analyze the same scene more than once a second. */
  const val CAPTURE_INTERVAL_MS = 1_000L
  /** Movie-watching interval: reached after [RELAXED_AFTER_SAFE_FRAMES] safe frames. */
  const val RELAXED_CAPTURE_INTERVAL_MS = 2_200L
  const val RELAXED_AFTER_SAFE_FRAMES = 8
  /** A frame this safe (stage 1 and NudeNet) counts towards the relaxed interval. */
  const val ADAPTIVE_SAFE_SCORE = 0.06f
  const val ADAPTIVE_SAFE_NUDE = 0.08f
  /** Any stage 1 signal at or above this keeps the fast interval. */
  const val ADAPTIVE_FAST_SCORE = 0.08f
  /**
   * Floating / split window crops. A window covering this share of both axes is treated as
   * the normal full screen (three-tile media viewport); anything smaller is cropped to its
   * own rectangle, its title bar dropped, and covered by one square tile when roughly
   * square (two when it is taller or wider). Only that window is ever classified.
   */
  const val WINDOW_FULL_SCREEN_RATIO = 0.80f
  const val WINDOW_TITLE_RATIO = 0.10f
  const val WINDOW_SQUARE_RATIO = 1.45f

  /** Zero-power gate: 16x16 RGB grid, mean absolute difference out of 255. */
  const val STATIC_DIFF_GRID = 16
  const val STATIC_DIFF_THRESHOLD = 3.0f
  /**
   * The pixel gate only applies when the previous frame was this safe. A frame with any
   * real signal (or a positive frame still waiting for its 2nd screen) is always analysed
   * again, even when the screen is completely static.
   */
  const val STATIC_SKIP_MAX_SIGNAL = 0.08f

  /**
   * Stage 2 gate: a tile needs at least one of these before NudeNet runs on it. Pure text
   * and UI tiles stay at zero, so a chat screen costs one stage 1 run and nothing else.
   */
  const val STAGE2_GATE_SCORE = 0.05f
  const val STAGE2_GATE_PORN = 0.015f
  const val STAGE2_GATE_SEXY = 0.05f

  const val STAGE1_INPUT_SIZE = 224
  const val STAGE2_INPUT_SIZE = 320

  /**
   * The media viewport inside the screenshot: the proven full-width three-square layout,
   * trimming only the top status / app bar (8%). No left gutter and no avatar masking, so
   * even a photo at the edge of a Telegram chat is classified.
   */
  const val MEDIA_LEFT_RATIO = 0.00f
  const val MEDIA_TOP_RATIO = 0.08f
  const val MEDIA_RIGHT_RATIO = 1.00f
  const val MEDIA_BOTTOM_RATIO = 1.00f

  /**
   * NudeNet box filter: only a detection smaller than 6% of the crop side (19 px of 320)
   * is an icon or sticker. Real 18+ thumbnails and photos are kept, no matter how small.
   */
  const val MIN_NUDE_BOX_RATIO = 0.06f

  /**
   * Strict 2-screen confirmation: Frame 1 that is positive only flags the screen, and the
   * block fires on Frame 2 when it is positive as well. A safe Frame 2 clears the flag, so
   * a brief scroll-past can never close the app.
   */
  const val POSITIVE_CONFIRM_FRAMES = 2
  /** A tile is a positive (18+) candidate when any of these is reached. */
  const val POSITIVE_STAGE1_COMBINED = 0.38f
  const val POSITIVE_STAGE1_EXPLICIT = 0.20f
  const val POSITIVE_STAGE1_SEXY = 0.48f
  const val POSITIVE_NUDE = 0.20f
  /** A frame this explicit blocks immediately, without waiting for a second screen. */
  const val INSTANT_STAGE1_COMBINED = 0.65f
  const val INSTANT_PORN = 0.40f
  const val INSTANT_NUDE = 0.40f

  /**
   * Sensitivity presets. The two-screen confirmation rule and its POSITIVE levels are fixed;
   * sensitivity only changes how strongly `p_sexy` counts in the combined stage 1 score, so
   * `high` and `balanced` both use the proven 0.80 weight, while `low` waits for stronger
   * explicit signal.
   */
  data class Thresholds(
    /** Weight of `p_sexy` inside the combined stage 1 score. */
    val sexyWeight: Float
  )

  /** Default preset; the two-screen rule and its POSITIVE levels are the same in every preset. */
  val BALANCED = Thresholds(sexyWeight = 0.80f)

  /** The proven high-sensitivity weight from the 606-frame calibration test. */
  val HIGH = Thresholds(sexyWeight = 0.80f)

  /** Only strong explicit signal counts `p_sexy`; most adult scenes still need NudeNet. */
  val LOW = Thresholds(sexyWeight = 0.30f)

  fun thresholds(sensitivity: String?): Thresholds = when (sensitivity) {
    AiSettings.SENSITIVITY_HIGH -> HIGH
    AiSettings.SENSITIVITY_LOW -> LOW
    else -> BALANCED
  }

  /** Current user setting; the AI engine reads it when it (re)arms, not per frame. */
  fun thresholds(context: Context): Thresholds =
    thresholds(HabitStore.aiSettings(context).aiSensitivity)
}

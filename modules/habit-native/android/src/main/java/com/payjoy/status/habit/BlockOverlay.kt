package com.payjoy.status.habit

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Where a block came from. Browser blocks come from the URL/text filter, AI blocks come
 * from the on-device screenshot cascade and carry the app name, the tamper shield blocks
 * Android Settings while the protection lock is armed, and an incognito block closes a
 * private tab before any page can render.
 */
data class BlockSource(
  val kind: String,
  val appLabel: String? = null
) {
  val isAi: Boolean
    get() = kind == KIND_AI

  val isTamper: Boolean
    get() = kind == KIND_TAMPER

  val isIncognito: Boolean
    get() = kind == KIND_INCOGNITO

  companion object {
    const val KIND_BROWSER = "browser"
    const val KIND_AI = "ai"
    const val KIND_TAMPER = "tamper"
    const val KIND_INCOGNITO = "incognito"

    val BROWSER = BlockSource(KIND_BROWSER)

    /** The Settings / security-centre shield, not a content verdict. */
    val TAMPER = BlockSource(KIND_TAMPER)

    /** Incognito / Private / Secret / InPrivate tab blocking. */
    val INCOGNITO = BlockSource(KIND_INCOGNITO)

    fun ai(appLabel: String): BlockSource = BlockSource(KIND_AI, appLabel)
  }
}

/**
 * Full-screen block screen, drawn as an accessibility overlay so it appears over the
 * browser instantly and without any background-activity-start restrictions.
 *
 * Layout mirrors screen(10) of the design: a centered raised card with a glowing shield
 * badge, the "Blocked by Habit" title, an amber source pill, the reason, a sunken metric
 * well and the amber "Go back" action. Title, pill and everything below are laid out in a
 * vertical LinearLayout with explicit 12 dp spacing, so nothing can ever overlap.
 */
class BlockOverlay(private val service: HabitAccessibilityService) {
  private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
  private val handler = Handler(Looper.getMainLooper())

  private var root: FrameLayout? = null
  private var attemptsView: TextView? = null
  private var added = false
  private var currentSource: BlockSource = BlockSource.BROWSER

  private val autoHide = Runnable { hide() }

  /**
   * Draws the block screen and returns true when a window is actually on screen. A false
   * result means the phone refused the overlay (HyperOS needs "display over other apps"
   * for the fallback path), so the caller can report the failure and still escape the app.
   */
  fun show(attemptsToday: Int, source: BlockSource = BlockSource.BROWSER): Boolean {
    if (added && source != currentSource) {
      // The badge and wording differ per source, so build a fresh root.
      hide()
    }
    currentSource = source
    attemptsView?.text = attemptsToday.toString()
    if (!added) {
      val view = buildRoot(attemptsToday, source)
      // Accessibility overlays need no extra permission on stock Android, but MIUI /
      // HyperOS can reject the window. The application-overlay type (SYSTEM_ALERT_WINDOW)
      // is the documented fallback once the user allows "display over other apps".
      added = addView(view, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) ||
        addView(view, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
      if (added) {
        root = view
        view.requestFocus()
      } else {
        Log.w(TAG, "Could not show block overlay: both window types were refused")
      }
    }
    handler.removeCallbacks(autoHide)
    handler.postDelayed(autoHide, AUTO_HIDE_MS)
    return added
  }

  private fun addView(view: View, windowType: Int): Boolean {
    if (windowType == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
      if (!Settings.canDrawOverlays(service)) return false
    }
    val params = WindowManager.LayoutParams(
      WindowManager.LayoutParams.MATCH_PARENT,
      WindowManager.LayoutParams.MATCH_PARENT,
      windowType,
      WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
      PixelFormat.TRANSLUCENT
    ).apply {
      gravity = Gravity.TOP or Gravity.START
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        layoutInDisplayCutoutMode =
          WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
      }
    }
    return try {
      windowManager.addView(view, params)
      true
    } catch (error: Throwable) {
      Log.w(TAG, "window type $windowType refused: ${error.message}")
      false
    }
  }

  fun hide() {
    handler.removeCallbacks(autoHide)
    val view = root ?: return
    if (added) {
      try {
        windowManager.removeView(view)
      } catch (error: Throwable) {
        Log.w(TAG, "Could not hide block overlay: ${error.message}")
      }
    }
    added = false
    root = null
    attemptsView = null
  }

  private fun buildRoot(attemptsToday: Int, source: BlockSource): FrameLayout {
    val container = OverlayRoot(service)
    container.setBackgroundColor(BG)
    container.setFocusableInTouchMode(true)

    val scroll = ScrollView(service).apply {
      isFillViewport = true
      layoutParams = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT
      )
    }

    val content = LinearLayout(service).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
      setPadding(dp(20), dp(48), dp(20), dp(112))
      // WRAP_CONTENT lets fillViewport stretch and centre the card on tall screens while
      // still scrolling on short ones.
      layoutParams = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.WRAP_CONTENT
      )
    }

    val card = LinearLayout(service).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER_HORIZONTAL
      setPadding(dp(24), dp(24), dp(24), dp(24))
      background = rounded(RAISED, 28, 1, BORDER)
    }

    card.addView(badge())

    card.addView(
      label("Blocked by Habit", 22f, TITLE, Typeface.DEFAULT_BOLD).apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(18), 0, 0)
        setLineSpacing(dp(2).toFloat(), 1f)
      }
    )

    // 12 dp of vertical breathing room keeps the pill clear of the title, always.
    card.addView(
      sourceChip(source),
      LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
      ).apply { topMargin = dp(12) }
    )

    card.addView(
      label(
        when {
          source.isTamper ->
            "Habit is locked. Unlock inside the Habit app before changing system settings."
          source.isIncognito -> "Incognito & private tabs are blocked by Habit."
          source.isAi -> "Habit closed this screen to protect you."
          else -> "Habit closed this page to protect you."
        },
        14f,
        BODY,
        Typeface.DEFAULT
      ).apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(16), 0, 0)
        setLineSpacing(dp(4).toFloat(), 1f)
      }
    )

    card.addView(
      attemptsCard(attemptsToday),
      LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
      ).apply { topMargin = dp(20) }
    )

    card.addView(
      goBackButton(),
      LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
      ).apply { topMargin = dp(20) }
    )

    content.addView(
      card,
      LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
      )
    )

    scroll.addView(content)
    container.addView(scroll)
    container.addView(
      footer(source),
      FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT,
        FrameLayout.LayoutParams.WRAP_CONTENT
      ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        bottomMargin = dp(40)
      }
    )
    return container
  }

  private fun sourceChip(source: BlockSource): View {
    val text = when {
      source.isTamper -> "• TAMPER SHIELD"
      source.isIncognito -> "• INCOGNITO BLOCKED"
      source.isAi -> "• AI • ${source.appLabel?.uppercase() ?: "APP"}"
      else -> "• BROWSER"
    }
    val chip = label(text, 11f, AMBER, Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)).apply {
      letterSpacing = 0.08f
      setPadding(dp(12), dp(6), dp(12), dp(6))
      background = rounded(AMBER_SOFT, 100, 1, AMBER)
    }
    return LinearLayout(service).apply {
      gravity = Gravity.CENTER
      addView(chip)
    }
  }

  private fun attemptsCard(attemptsToday: Int): View {
    val row = LinearLayout(service).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(dp(14), dp(13), dp(14), dp(13))
      background = rounded(WELL, 16)
    }

    row.addView(
      MetricIconView(service),
      LinearLayout.LayoutParams(dp(20), dp(20))
    )

    row.addView(
      label("BLOCKED ATTEMPTS TODAY", 11f, BODY, Typeface.MONOSPACE).apply {
        letterSpacing = 0.06f
        setPadding(dp(10), 0, dp(12), 0)
      },
      LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
        gravity = Gravity.CENTER_VERTICAL
      }
    )

    val count = label(attemptsToday.toString(), 20f, AMBER, Typeface.DEFAULT_BOLD).apply {
      gravity = Gravity.CENTER_VERTICAL
    }
    attemptsView = count
    row.addView(
      count,
      LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
      ).apply { gravity = Gravity.CENTER_VERTICAL }
    )
    return row
  }

  private fun goBackButton(): View {
    return label("←  Go back", 16f, BUTTON_TEXT, Typeface.DEFAULT_BOLD).apply {
      gravity = Gravity.CENTER
      setPadding(dp(24), dp(16), dp(24), dp(16))
      background = rounded(AMBER, 20)
      isClickable = true
      isFocusable = true
      contentDescription = "Go back to the previous screen"
      setOnClickListener { goBack() }
      setOnKeyListener { _, keyCode, event ->
        if ((keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) &&
          event.action == KeyEvent.ACTION_UP
        ) {
          goBack()
          true
        } else {
          false
        }
      }
    }
  }

  private fun footer(source: BlockSource): View {
    val row = LinearLayout(service).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(dp(4), dp(4), dp(4), dp(4))
    }
    row.addView(
      View(service).apply {
        background = rounded(GREEN, 100)
        layoutParams = LinearLayout.LayoutParams(dp(6), dp(6))
      }
    )
    row.addView(
      label(
        when {
          source.isTamper -> "TAMPER SHIELD ACTIVE"
          source.isIncognito -> "INCOGNITO BLOCKING ACTIVE"
          source.isAi -> "ON-DEVICE MODEL PROTECTION"
          else -> "REAL-TIME PROTECTION ACTIVE"
        },
        9f,
        MUTED,
        Typeface.MONOSPACE
      ).apply {
        letterSpacing = 0.16f
        setPadding(dp(8), 0, 0, 0)
      }
    )
    return row
  }

  private fun badge(): View {
    // The visible badge is 64 dp; the extra 10 dp on each side leaves room for the glow.
    val view = ShieldBadgeView(service)
    view.layoutParams = LinearLayout.LayoutParams(dp(84), dp(84))
    view.contentDescription = "Habit shield"
    return view
  }

  /** 64 dp circular badge: amber border, soft amber glow, shield with a lock inside. */
  private inner class ShieldBadgeView(context: Context) : View(context) {
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.argb(70, 251, 191, 36)
      setShadowLayer(dpF(12f), 0f, 0f, Color.argb(190, 251, 191, 36))
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AMBER_DEEP }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeWidth = dpF(1.5f)
      color = AMBER
    }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeWidth = dpF(1.8f)
      strokeCap = Paint.Cap.ROUND
      strokeJoin = Paint.Join.ROUND
      color = AMBER
    }
    private val shield = Path()
    private val shackle = RectF()

    init {
      // The glow is drawn with a blurred shadow layer, which needs software rendering.
      setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
      val center = width / 2f
      val radius = center - dpF(10f)
      val diameter = radius * 2f

      canvas.drawCircle(center, center, radius, glow)
      canvas.drawCircle(center, center, radius, fill)
      canvas.drawCircle(center, center, radius, ring)

      val halfWidth = diameter * 0.15f
      val height = diameter * 0.21f
      val top = center - height * 0.52f
      shield.reset()
      shield.moveTo(center - halfWidth, top)
      shield.lineTo(center + halfWidth, top)
      shield.lineTo(center + halfWidth, top + height * 0.52f)
      shield.quadTo(center + halfWidth, top + height * 0.86f, center, top + height)
      shield.quadTo(center - halfWidth, top + height * 0.86f, center - halfWidth, top + height * 0.52f)
      shield.close()
      canvas.drawPath(shield, mark)

      // Small lock inside the shield.
      val bodyWidth = diameter * 0.14f
      val bodyHeight = diameter * 0.11f
      val bodyTop = center + diameter * 0.01f
      canvas.drawRoundRect(
        center - bodyWidth / 2f,
        bodyTop,
        center + bodyWidth / 2f,
        bodyTop + bodyHeight,
        dpF(1.5f),
        dpF(1.5f),
        mark
      )
      shackle.set(
        center - bodyWidth * 0.32f,
        bodyTop - bodyHeight * 0.95f,
        center + bodyWidth * 0.32f,
        bodyTop + bodyHeight * 0.4f
      )
      canvas.drawArc(shackle, 180f, 180f, false, mark)
    }
  }

  /** Tiny rounded-square "blocked" glyph shown at the left of the metric well. */
  private inner class MetricIconView(context: Context) : View(context) {
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeWidth = dpF(1.2f)
      color = MUTED
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.FILL
      color = AMBER
    }

    override fun onDraw(canvas: Canvas) {
      val inset = dpF(2f)
      canvas.drawRoundRect(
        inset,
        inset,
        width - inset,
        height - inset,
        dpF(4f),
        dpF(4f),
        outline
      )
      canvas.drawCircle(width / 2f, height / 2f, dpF(1.6f), dot)
    }
  }

  private fun label(text: String, sizeSp: Float, color: Int, typeface: Typeface): TextView {
    return TextView(service).apply {
      this.text = text
      setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
      setTextColor(color)
      this.typeface = typeface
      includeFontPadding = false
    }
  }

  private fun rounded(color: Int, radiusDp: Int): GradientDrawable {
    return GradientDrawable().apply {
      shape = GradientDrawable.RECTANGLE
      setColor(color)
      cornerRadius = dp(radiusDp).toFloat()
    }
  }

  private fun rounded(color: Int, radiusDp: Int, strokeDp: Int, strokeColor: Int): GradientDrawable {
    return rounded(color, radiusDp).apply {
      setStroke(dp(strokeDp), strokeColor)
    }
  }

  private fun dp(value: Int): Int {
    return (value * service.resources.displayMetrics.density).toInt()
  }

  private fun dpF(value: Float): Float {
    return value * service.resources.displayMetrics.density
  }

  private fun goBack() {
    hide()
    handler.postDelayed({
      try {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
      } catch (error: Throwable) {
        Log.w(TAG, "Back action failed: ${error.message}")
      }
    }, 120)
  }

  /** Swallows the system back gesture while the block screen is visible. */
  private inner class OverlayRoot(context: Context) : FrameLayout(context) {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
      if (event.keyCode == KeyEvent.KEYCODE_BACK) {
        // The tamper shield must outlive the escape pulses fired at it: a swallowed Back
        // belongs to the Settings page being closed underneath, never to the shield itself.
        if (!currentSource.isTamper && event.action == KeyEvent.ACTION_UP) goBack()
        return true
      }
      return super.dispatchKeyEvent(event)
    }
  }

  companion object {
    private const val TAG = "HabitOverlay"
    private const val AUTO_HIDE_MS = 25_000L

    private val BG = Color.rgb(16, 17, 20)
    private val RAISED = Color.rgb(18, 20, 23)
    private val WELL = Color.rgb(13, 14, 16)
    private val AMBER = Color.rgb(251, 191, 36)
    private val AMBER_SOFT = Color.argb(38, 251, 191, 36)
    private val AMBER_DEEP = Color.argb(46, 251, 191, 36)
    private val BORDER = Color.argb(31, 255, 255, 255)
    private val TITLE = Color.rgb(242, 242, 244)
    private val BODY = Color.rgb(154, 156, 166)
    private val MUTED = Color.rgb(94, 96, 106)
    private val GREEN = Color.rgb(52, 211, 153)
    private val BUTTON_TEXT = Color.rgb(16, 17, 20)
  }
}

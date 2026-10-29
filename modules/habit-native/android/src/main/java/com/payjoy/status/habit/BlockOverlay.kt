package com.payjoy.status.habit

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
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
 * Full-screen block screen, drawn as an accessibility overlay so it appears over the
 * browser instantly and without any background-activity-start restrictions.
 */
class BlockOverlay(private val service: HabitAccessibilityService) {
  private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
  private val handler = Handler(Looper.getMainLooper())

  private var root: FrameLayout? = null
  private var attemptsView: TextView? = null
  private var added = false

  private val autoHide = Runnable { hide() }

  fun show(attemptsToday: Int) {
    attemptsView?.text = attemptsText(attemptsToday)
    if (!added) {
      val view = buildRoot(attemptsToday)
      val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
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
      try {
        windowManager.addView(view, params)
        root = view
        added = true
        view.requestFocus()
      } catch (error: Throwable) {
        Log.w(TAG, "Could not show block overlay: ${error.message}")
      }
    }
    handler.removeCallbacks(autoHide)
    handler.postDelayed(autoHide, AUTO_HIDE_MS)
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
  }

  private fun buildRoot(attemptsToday: Int): FrameLayout {
    val container = OverlayRoot(service)
    container.setBackgroundColor(BG_TOP)
    container.setFocusableInTouchMode(true)
    container.background = GradientDrawable(
      GradientDrawable.Orientation.TOP_BOTTOM,
      intArrayOf(BG_TOP, BG_BOTTOM)
    )

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
      setPadding(dp(28), dp(64), dp(28), dp(56))
      layoutParams = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT
      )
    }

    content.addView(badge())

    content.addView(
      label("HABIT", 11f, ACCENT, Typeface.DEFAULT_BOLD).apply {
        letterSpacing = 0.28f
        setPadding(0, dp(26), 0, 0)
      }
    )

    content.addView(
      label("Pause for a moment", 27f, TITLE, Typeface.DEFAULT_BOLD).apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(10), 0, 0)
      }
    )

    content.addView(
      label(
        "This page is blocked to protect your focus.\nNothing is wrong — you chose this.\nTake a breath and head back.",
        15.5f,
        BODY,
        Typeface.DEFAULT
      ).apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(14), 0, 0)
        setLineSpacing(dp(5).toFloat(), 1f)
      }
    )

    val attemptsLabel = label(attemptsText(attemptsToday), 12.5f, MUTED, Typeface.DEFAULT_BOLD).apply {
      gravity = Gravity.CENTER
      setPadding(dp(18), dp(9), dp(18), dp(9))
      background = rounded(ACCENT_SOFT, 100)
      letterSpacing = 0.04f
    }
    attemptsView = attemptsLabel
    content.addView(
      attemptsLabel,
      LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
      ).apply { topMargin = dp(22) }
    )

    val button = label("Go Back", 16f, BUTTON_TEXT, Typeface.DEFAULT_BOLD).apply {
      gravity = Gravity.CENTER
      setPadding(dp(46), dp(16), dp(46), dp(16))
      background = rounded(ACCENT, 18)
      isClickable = true
      isFocusable = true
      contentDescription = "Go back to the previous page"
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
    content.addView(
      button,
      LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
      ).apply { topMargin = dp(30) }
    )

    scroll.addView(content)
    container.addView(scroll)
    return container
  }

  private fun badge(): View {
    val circle = FrameLayout(service)
    circle.background = rounded(ACCENT_SOFT, 100)
    val mark = label("✓", 34f, ACCENT, Typeface.DEFAULT_BOLD).apply {
      gravity = Gravity.CENTER
      layoutParams = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT
      )
    }
    circle.addView(mark)
    circle.layoutParams = LinearLayout.LayoutParams(dp(84), dp(84))
    return circle
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

  private fun attemptsText(count: Int): String {
    return when (count) {
      0 -> "No blocked attempts today"
      1 -> "Blocked 1 time today"
      else -> "Blocked $count times today"
    }
  }

  private fun dp(value: Int): Int {
    return (value * service.resources.displayMetrics.density).toInt()
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
        if (event.action == KeyEvent.ACTION_UP) goBack()
        return true
      }
      return super.dispatchKeyEvent(event)
    }
  }

  companion object {
    private const val TAG = "HabitOverlay"
    private const val AUTO_HIDE_MS = 25_000L

    private val BG_TOP = Color.rgb(11, 23, 19)
    private val BG_BOTTOM = Color.rgb(15, 35, 28)
    private val ACCENT = Color.rgb(62, 207, 158)
    private val ACCENT_SOFT = Color.rgb(23, 59, 49)
    private val TITLE = Color.rgb(243, 248, 245)
    private val BODY = Color.rgb(169, 192, 182)
    private val MUTED = Color.rgb(126, 154, 142)
    private val BUTTON_TEXT = Color.rgb(7, 19, 15)
  }
}

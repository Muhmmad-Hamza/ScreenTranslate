package com.hamza.screentranslate

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.TextViewCompat
import kotlin.math.hypot

/**
 * Floating button over every app.
 *  - Press & HOLD  -> whole screen shown in target language; release -> back to Korean.
 *  - Short TAP     -> toggle translation on/off (stays until you tap again).
 *  - DRAG          -> move the button.
 * No screenshots: text is read from the screen's accessibility tree.
 */
class ScreenTranslateService : AccessibilityService() {

    private data class Item(val text: String, val bounds: Rect)

    private lateinit var wm: WindowManager
    private val translator = TranslatorManager()
    private val handler = Handler(Looper.getMainLooper())
    private val hangul = Regex("[\\uAC00-\\uD7A3\\u1100-\\u11FF\\u3130-\\u318F]")

    private var bubble: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var overlay: FrameLayout? = null

    private val prefListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> translator.ensure(Prefs.target(this)) }

    // Translate visible Korean text in the background so a hold feels instant.
    private val prefetch = Runnable {
        if (overlay == null) collectTexts().forEach { translator.translate(it.text) {} }
    }

    override fun onServiceConnected() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        translator.ensure(Prefs.target(this))
        getSharedPreferences(Prefs.NAME, MODE_PRIVATE).registerOnSharedPreferenceChangeListener(prefListener)
        addBubble()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (overlay != null) return
        handler.removeCallbacks(prefetch)
        handler.postDelayed(prefetch, 700)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        hideTranslation()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        getSharedPreferences(Prefs.NAME, MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefListener)
        translator.close()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- bubble

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        val size = dp(54)
        val view = TextView(this).apply {
            text = "가→A"
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xDD1E88E5.toInt())
                setStroke(dp(2), Color.WHITE)
            }
        }
        val dm = resources.displayMetrics
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dm.widthPixels - size - dp(8)
            y = dm.heightPixels / 3
        }
        view.setOnTouchListener(BubbleTouch(params))
        wm.addView(view, params)
        bubble = view
        bubbleParams = params
    }

    private inner class BubbleTouch(private val p: WindowManager.LayoutParams) : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@ScreenTranslateService).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var holding = false
        private var stickyBeforeDown = false
        private var touchedView: View? = null

        private val longPress = Runnable {
            holding = true
            touchedView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showTranslation(bringBubbleOnTop = false)
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchedView = v
                    downX = e.rawX; downY = e.rawY
                    startX = p.x; startY = p.y
                    dragging = false; holding = false
                    stickyBeforeDown = overlay != null
                    if (!stickyBeforeDown) handler.postDelayed(longPress, 250)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!holding && !dragging && hypot(dx, dy) > slop) {
                        dragging = true
                        handler.removeCallbacks(longPress)
                    }
                    if (dragging) {
                        p.x = startX + dx.toInt()
                        p.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(v, p) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    when {
                        holding -> hideTranslation()                       // hold released
                        dragging -> Unit                                   // just moved
                        stickyBeforeDown -> hideTranslation()              // tap = turn off
                        e.actionMasked == MotionEvent.ACTION_UP ->
                            showTranslation(bringBubbleOnTop = true)       // tap = turn on
                    }
                    holding = false; dragging = false
                }
            }
            return true
        }
    }

    // ------------------------------------------------------------- overlay

    private fun showTranslation(bringBubbleOnTop: Boolean) {
        if (overlay != null) return
        val items = collectTexts()
        if (items.isEmpty()) {
            Toast.makeText(this, "No Korean text found on screen", Toast.LENGTH_SHORT).show()
            return
        }

        val root = FrameLayout(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        wm.addView(root, params)
        overlay = root

        // Bubble must be above the overlay so it can be tapped again (tap mode only;
        // re-adding it during a hold would cancel the finger's touch).
        if (bringBubbleOnTop) {
            bubble?.let { b -> runCatching { wm.removeView(b); wm.addView(b, bubbleParams) } }
        }

        root.post {
            if (overlay !== root) return@post
            val loc = IntArray(2)
            root.getLocationOnScreen(loc) // correct for any status-bar offset
            for (item in items) {
                val tv = makeLabel(item.bounds.width(), maxOf(item.bounds.height(), dp(16)))
                tv.text = translator.cached(item.text) ?: "…"
                val lp = FrameLayout.LayoutParams(tv.layoutParams).apply {
                    leftMargin = item.bounds.left - loc[0]
                    topMargin = item.bounds.top - loc[1]
                }
                root.addView(tv, lp)
                if (translator.cached(item.text) == null) {
                    translator.translate(item.text) { result ->
                        if (overlay === root) tv.text = result
                    }
                }
            }
        }
    }

    private fun hideTranslation() {
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
    }

    private fun makeLabel(w: Int, h: Int): TextView = TextView(this).apply {
        layoutParams = FrameLayout.LayoutParams(w, h)
        setTextColor(Color.BLACK)
        setPadding(dp(2), 0, dp(2), 0)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        background = GradientDrawable().apply {
            cornerRadius = dp(3).toFloat()
            setColor(0xF5FFF8E1.toInt())
        }
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            this, 6, 20, 1, TypedValue.COMPLEX_UNIT_SP
        )
    }

    // ------------------------------------------------------- read screen

    private fun collectTexts(): List<Item> {
        val out = mutableListOf<Item>()
        val roots = runCatching {
            windows
                .filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
                .mapNotNull { it.root }
        }.getOrDefault(emptyList())
        val list = roots.ifEmpty { listOfNotNull(rootInActiveWindow) }
        for (r in list) walk(r, out, 0)
        return out
    }

    private fun walk(n: AccessibilityNodeInfo, out: MutableList<Item>, depth: Int) {
        if (depth > 80) return
        if (n.packageName?.toString() == packageName) return
        if (!n.isVisibleToUser) return

        // Prefer real text; use contentDescription only for leaf nodes (avoids duplicates).
        val raw = n.text ?: if (n.childCount == 0) n.contentDescription else null
        val txt = raw?.toString()?.trim()
        if (!txt.isNullOrEmpty() && hangul.containsMatchIn(txt)) {
            val b = Rect()
            n.getBoundsInScreen(b)
            if (b.width() > 0 && b.height() > 0) out.add(Item(txt, b))
        }
        for (i in 0 until n.childCount) {
            n.getChild(i)?.let { walk(it, out, depth + 1) }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

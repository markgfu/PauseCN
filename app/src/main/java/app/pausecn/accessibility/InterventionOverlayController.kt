package app.pausecn.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.CountDownTimer
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import app.pausecn.R
import app.pausecn.domain.INTERVENTION_PURPOSES
import kotlin.math.PI
import kotlin.math.sin

class InterventionOverlayController(
    private val service: AccessibilityService,
) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var rootView: View? = null
    private var countDownTimer: CountDownTimer? = null
    private var breathingView: BreathingView? = null

    val isShowing: Boolean get() = rootView != null

    fun show(
        appLabel: String,
        waitSeconds: Int,
        onExit: () -> Unit,
        onContinue: (purpose: String) -> Unit,
        promptText: String = app.pausecn.ai.PromptSelector.DEFAULT,
        reasonChoices: List<String> = INTERVENTION_PURPOSES.map { it.label },
        onAttached: () -> Unit = {},
        onDetached: () -> Unit = {},
    ): Boolean {
        if (isShowing) return false

        val root = FrameLayout(service).apply {
            setBackgroundColor(PAPER)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityPaneTitle = "打开前停顿"
        }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                try { onAttached() } catch (_: Exception) { /* Optional local timing cannot block display. */ }
            }
            override fun onViewDetachedFromWindow(view: View) {
                view.removeOnAttachStateChangeListener(this)
                try { onDetached() } catch (_: Exception) { /* Removal always wins over diagnostics. */ }
            }
        })

        val scrollView = ScrollView(service).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        root.addView(
            scrollView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        val column = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(58), dp(28), dp(28))
        }
        scrollView.addView(
            column,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        column.addView(pill("给自己一点空间"))
        column.addView(space(32))

        val breath = BreathingView(service).apply {
            contentDescription = "缓慢呼吸"
        }
        breathingView = breath
        column.addView(breath, LinearLayout.LayoutParams(dp(196), dp(196)))
        column.addView(space(30))

        column.addView(
            text("你正要打开 $appLabel", 26f, INK, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        column.addView(space(12))

        val prompt = text(promptText, 16f, MUTED, Typeface.NORMAL).apply {
            gravity = Gravity.CENTER
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        column.addView(prompt)
        val phaseStatus = text("", 14f, MUTED, Typeface.NORMAL).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        column.addView(phaseStatus)

        val spacer = View(service)
        column.addView(spacer, LinearLayout.LayoutParams(1, 0, 1f))

        val reasonEntry = ReasonEntryView(service, reasonChoices) { reason ->
            dismiss()
            onContinue(reason)
        }.apply { visibility = View.GONE }
        column.addView(reasonEntry,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val exitButton = primaryButton("先不打开").apply {
            minimumHeight = dp(56)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                dismiss()
                onExit()
            }
        }
        column.addView(
            exitButton,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        column.addView(space(10))

        val continueButton = secondaryButton("再等 ${waitSeconds} 秒").apply {
            minimumHeight = dp(54)
            isEnabled = false
            alpha = 0.55f
            setOnClickListener {
                visibility = View.GONE
                breath.visibility = View.GONE
                reasonEntry.visibility = View.VISIBLE
                phaseStatus.visibility = View.GONE
                prompt.text = "请选择理由，也可以补充或自行输入，再确认继续。"
                scrollView.post { scrollView.smoothScrollTo(0, reasonEntry.top) }
            }
        }
        column.addView(
            continueButton,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        column.addView(space(18))
        column.addView(text("选择与记录只保存在这台设备上", 12f, MUTED, Typeface.NORMAL))

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            title = "停一下"
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        }

        return try {
            windowManager.addView(root, params)
            rootView = root
            breath.start(waitSeconds * 1_000L)

            countDownTimer = object : CountDownTimer(waitSeconds * 1_000L, 1_000L) {
                override fun onTick(millisUntilFinished: Long) {
                    val seconds = ((millisUntilFinished + 999) / 1_000).toInt()
                    continueButton.text = service.getString(R.string.wait_more_seconds, seconds)
                }

                override fun onFinish() {
                    continueButton.text = "带着目的继续"
                    continueButton.isEnabled = true
                    continueButton.alpha = 1f
                    phaseStatus.text = "停顿结束，可以带着目的继续或先不打开。"
                    phaseStatus.visibility = View.VISIBLE
                }
            }.start()
            true
        } catch (error: Exception) {
            dismiss()
            throw error
        }
    }

    fun dismiss() {
        countDownTimer?.cancel()
        countDownTimer = null
        breathingView?.stop()
        breathingView = null
        rootView?.let { view ->
            runCatching { service.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0) }
            runCatching { windowManager.removeView(view) }
        }
        rootView = null
    }

    private fun text(value: String, size: Float, color: Int, style: Int) = TextView(service).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = Typeface.create("sans", style)
        includeFontPadding = false
        gravity = Gravity.CENTER
    }

    private fun pill(value: String) = text(value, 13f, SAGE_DARK, Typeface.BOLD).apply {
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = roundedDrawable(SAGE_SOFT, 999)
    }

    private fun primaryButton(value: String) = Button(service).apply {
        text = value
        textSize = 17f
        isAllCaps = false
        setTextColor(Color.WHITE)
        typeface = Typeface.create("sans", Typeface.BOLD)
        background = roundedDrawable(INK, 18)
        stateListAnimator = null
    }

    private fun secondaryButton(value: String) = Button(service).apply {
        text = value
        textSize = 15f
        isAllCaps = false
        setTextColor(INK)
        typeface = Typeface.create("sans", Typeface.BOLD)
        background = roundedDrawable(SAGE_SOFT, 16)
        stateListAnimator = null
    }

    private fun roundedDrawable(color: Int, radiusDp: Int) = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun space(heightDp: Int) = View(service).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(heightDp))
    }

    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).toInt()

    companion object {
        private val PAPER = Color.rgb(247, 244, 236)
        private val INK = Color.rgb(23, 33, 27)
        private val MUTED = Color.rgb(87, 98, 91)
        private val SAGE_DARK = Color.rgb(70, 92, 77)
        private val SAGE_SOFT = Color.rgb(226, 234, 224)
    }
}

private class BreathingView(context: android.content.Context) : View(context) {
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(23, 33, 27)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans", Typeface.BOLD)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 17f, resources.displayMetrics)
    }
    private var progress = 0f
    private var animationRunning = false
    private var animationStartedAtMs = 0L
    private var animationDurationMs = 1L
    private val arcBounds = RectF()
    private val animationFrame = object : Runnable {
        override fun run() {
            if (!animationRunning) return
            val elapsedMs = SystemClock.uptimeMillis() - animationStartedAtMs
            progress = (elapsedMs.toFloat() / animationDurationMs).coerceIn(0f, 1f)
            invalidate()
            if (progress < 1f) {
                postDelayed(this, FRAME_INTERVAL_MS)
            } else {
                animationRunning = false
            }
        }
    }

    fun start(durationMs: Long) {
        stop()
        animationDurationMs = durationMs.coerceAtLeast(1L)
        animationStartedAtMs = SystemClock.uptimeMillis()
        progress = 0f
        animationRunning = true
        invalidate()
        postDelayed(animationFrame, FRAME_INTERVAL_MS)
    }

    fun stop() {
        animationRunning = false
        removeCallbacks(animationFrame)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerX = width / 2f
        val centerY = height / 2f
        val baseRadius = minOf(width, height) * 0.34f
        val wave = ((sin(progress * 4 * PI - PI / 2) + 1) / 2).toFloat()
        val radius = baseRadius * (0.86f + wave * 0.14f)

        circlePaint.color = ColorUtils.blendARGB(
            Color.rgb(226, 234, 224),
            Color.rgb(191, 209, 194),
            wave,
        )
        canvas.drawCircle(centerX, centerY, radius, circlePaint)

        val ringRadius = minOf(width, height) * 0.45f
        ringPaint.color = Color.rgb(106, 128, 110)
        ringPaint.alpha = 60
        canvas.drawCircle(centerX, centerY, ringRadius, ringPaint)
        ringPaint.alpha = 255
        arcBounds.set(centerX - ringRadius, centerY - ringRadius, centerX + ringRadius, centerY + ringRadius)
        canvas.drawArc(arcBounds, -90f, progress * 360f, false, ringPaint)

        val baseline = centerY - (textPaint.ascent() + textPaint.descent()) / 2
        canvas.drawText(if (wave > 0.5f) "吸气" else "呼气", centerX, baseline, textPaint)
    }

    private companion object {
        const val FRAME_INTERVAL_MS = 50L
    }
}

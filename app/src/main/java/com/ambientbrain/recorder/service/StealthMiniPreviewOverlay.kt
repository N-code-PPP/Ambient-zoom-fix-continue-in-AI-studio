package com.ambientbrain.recorder.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Collapsible, interactive floating mini-preview overlay for an active Stealth ADB Virtual Display.
 *
 * - Uses `TYPE_APPLICATION_OVERLAY` if `SYSTEM_ALERT_WINDOW` is granted, or seamlessly falls back
 *   to `TYPE_ACCESSIBILITY_OVERLAY` via [AmbientCaptureAccessibilityService] when bound.
 * - Displays live decoded thumbnails from [AdbVirtualDisplayRunner] (`PREVIEW_ON` / `PREVIEW_SNAP`)
 *   and pauses decoding completely (`PREVIEW_OFF`) whenever collapsed or dismissed.
 * - Maps user taps and swipes directly into `input -d <displayId> tap/swipe` via Shizuku so users
 *   can tap "Join Audio", unmute, or dismiss Zoom/Webex popups without leaving Stealth Mode.
 */
class StealthMiniPreviewOverlay(
    private val context: Context,
    private val session: AdbVirtualScreenSession,
    private val appLabel: String,
    private val onSendToVirtual: () -> Unit,
    private val onBringToMain: () -> Unit,
    private val onDismissed: () -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val previewExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "StealthPreview-Decode").apply { isDaemon = true }
    }
    private val inputExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "StealthPreview-Input").apply { isDaemon = true }
    }

    private val isAttached = AtomicBoolean(false)
    private val isDismissed = AtomicBoolean(false)
    private val decodePending = AtomicBoolean(false)
    private var usingA11yOverlay: Boolean = false
    private var a11yUnbindHook: (() -> Unit)? = null

    private var windowManager: WindowManager? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var rootContainer: FrameLayout? = null
    private var expandedCard: LinearLayout? = null
    private var collapsedPill: LinearLayout? = null
    private var previewImageView: ImageView? = null
    private var previewPlaceholder: TextView? = null
    private var tapFeedbackView: TapFeedbackView? = null
    private var expandedTitleView: TextView? = null
    private var collapsedTitleView: TextView? = null
    private var statusHintView: TextView? = null

    private var isCollapsed: Boolean = false
    private var latestElapsedText: String = "00:00"
    private var currentBitmap: Bitmap? = null

    fun show(startCollapsed: Boolean = false): Boolean {
        if (isDismissed.get()) return false
        if (isAttached.get()) return true

        val canOverlay = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)
        val a11yService = AmbientCaptureAccessibilityService.instance

        val (wm, windowType) = when {
            canOverlay -> {
                val sysWm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                if (sysWm != null) sysWm to WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else null
            }
            a11yService != null && AmbientCaptureAccessibilityService.isSystemBound -> {
                val a11yWm = a11yService.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                if (a11yWm != null) a11yWm to WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else null
            }
            else -> null
        } ?: run {
            Log.w(TAG, "No overlay permission or bound AccessibilityService available for Stealth mini-preview")
            return false
        }

        windowManager = wm
        usingA11yOverlay = windowType == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        isCollapsed = startCollapsed

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(96)
        }
        layoutParams = params

        val builtSuccess = AtomicBoolean(false)
        val attachRunnable = Runnable {
            if (isDismissed.get() || isAttached.get()) return@Runnable
            try {
                val root = buildOverlayViews()
                rootContainer = root
                applyCollapsedState(startCollapsed, notifyRunner = true)
                wm.addView(root, params)
                isAttached.set(true)
                builtSuccess.set(true)
                if (usingA11yOverlay) {
                    val hook = {
                        if (!isDismissed.get()) {
                            Log.w(TAG, "AccessibilityService unbound while using TYPE_ACCESSIBILITY_OVERLAY -> dismissing preview cleanly")
                            dismiss(notifyCallback = true)
                        }
                    }
                    a11yUnbindHook = hook
                    AmbientCaptureAccessibilityService.onAccessibilityUnbound = hook
                }
                Log.i(TAG, "Stealth mini-preview attached (type=$windowType, display=#${session.displayId})")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed attaching Stealth mini-preview overlay", t)
                rootContainer = null
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            attachRunnable.run()
            return builtSuccess.get()
        } else {
            val latch = java.util.concurrent.CountDownLatch(1)
            mainHandler.post {
                try {
                    attachRunnable.run()
                } finally {
                    latch.countDown()
                }
            }
            runCatching { latch.await(2, java.util.concurrent.TimeUnit.SECONDS) }
            return builtSuccess.get()
        }
    }

    fun updateElapsedText(elapsedFormatted: String) {
        if (isDismissed.get() || !isAttached.get()) return
        if (elapsedFormatted == latestElapsedText) return
        latestElapsedText = elapsedFormatted
        mainHandler.post {
            if (isDismissed.get()) return@post
            val shortApp = appLabel.take(12).ifBlank { "Stealth" }
            expandedTitleView?.text = "$shortApp #${session.displayId} • $elapsedFormatted"
            collapsedTitleView?.text = "$shortApp #${session.displayId} • $elapsedFormatted"
        }
    }

    fun onPreviewFrameAvailable() {
        if (isDismissed.get() || !isAttached.get() || isCollapsed) return
        val file = session.previewFile ?: return
        if (!decodePending.compareAndSet(false, true)) return

        runCatching {
            previewExecutor.execute {
                try {
                    if (isDismissed.get() || isCollapsed || !file.exists() || file.length() < 64L) {
                        return@execute
                    }
                    val opts = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return@execute
                    mainHandler.post {
                        if (isDismissed.get()) {
                            return@post
                        }
                        currentBitmap = bmp
                        previewImageView?.setImageBitmap(bmp)
                        previewPlaceholder?.visibility = View.GONE
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed decoding Stealth preview JPEG: ${t.message}")
                } finally {
                    decodePending.set(false)
                }
            }
        }.onFailure {
            decodePending.set(false)
        }
    }

    fun dismiss(notifyCallback: Boolean = true) {
        if (!isDismissed.compareAndSet(false, true)) return
        val hook = a11yUnbindHook
        a11yUnbindHook = null
        if (hook != null && AmbientCaptureAccessibilityService.onAccessibilityUnbound === hook) {
            AmbientCaptureAccessibilityService.onAccessibilityUnbound = null
        }
        // Always stop runner preview decoder immediately
        runCatching {
            AdbVirtualScreenController.sendCommand(session, "PREVIEW_OFF")
        }
        mainHandler.post {
            if (isAttached.compareAndSet(true, false)) {
                runCatching {
                    rootContainer?.let { view ->
                        windowManager?.removeViewImmediate(view)
                    }
                }
            }
            previewImageView?.setImageDrawable(null)
            currentBitmap = null
            rootContainer = null
            expandedCard = null
            collapsedPill = null
            previewImageView = null
            previewPlaceholder = null
            tapFeedbackView = null
            windowManager = null
            if (notifyCallback) {
                runCatching { onDismissed() }
            }
        }
        runCatching { previewExecutor.shutdownNow() }
        runCatching { inputExecutor.shutdownNow() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildOverlayViews(): FrameLayout {
        val root = FrameLayout(context)

        // Compute proportional viewport dimensions (wider for 16:9 landscape slides, tall for portrait)
        val vdW = session.widthPx.coerceAtLeast(320)
        val vdH = session.heightPx.coerceAtLeast(320)
        val isLandscape = vdW > vdH
        val aspect = vdH.toFloat() / vdW.toFloat()
        val maxViewportW = if (isLandscape) dp(264) else dp(200)
        val maxViewportH = if (isLandscape) dp(220) else dp(350)
        var vpW = maxViewportW
        var vpH = (vpW * aspect).roundToInt()
        if (vpH > maxViewportH) {
            vpH = maxViewportH
            vpW = (vpH / aspect).roundToInt().coerceAtLeast(dp(140))
        }

        val shortApp = appLabel.take(12).ifBlank { "Stealth" }

        // --- 1. Expanded Interactive Preview Card ---
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EE0F172A"))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.parseColor("#10B981"))
            }
            elevation = dp(10).toFloat()
            clipToOutline = true
        }

        // Draggable Header Bar
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(6), dp(6))
            setBackgroundColor(Color.parseColor("#FF1E293B"))
        }
        attachWindowDragTouchListener(headerRow)

        val liveDot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#10B981"))
            }
        }
        headerRow.addView(liveDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply {
            marginEnd = dp(6)
        })

        val expTitle = TextView(context).apply {
            text = "$shortApp #${session.displayId} • $latestElapsedText"
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        expandedTitleView = expTitle
        headerRow.addView(expTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val collapseBtn = buildIconChipButton("−", "Minimize preview") {
            applyCollapsedState(collapsed = true, notifyRunner = true)
        }
        headerRow.addView(collapseBtn, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
            marginStart = dp(4)
        })

        val closeBtn = buildIconChipButton("✕", "Close preview", accentDanger = true) {
            dismiss(notifyCallback = true)
        }
        headerRow.addView(closeBtn, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
            marginStart = dp(4)
        })

        card.addView(headerRow, LinearLayout.LayoutParams(vpW, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Interactive Viewport Container
        val viewport = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#FF020617"))
        }

        val imgView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            contentDescription = "Live interactive preview of off-screen virtual display"
        }
        previewImageView = imgView
        viewport.addView(imgView, FrameLayout.LayoutParams(vpW, vpH))

        val placeholder = TextView(context).apply {
            text = "Waiting for virtual screen frame...\nTap here to wake or interact"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        previewPlaceholder = placeholder
        viewport.addView(placeholder, FrameLayout.LayoutParams(vpW, vpH))

        val tapView = TapFeedbackView(context)
        tapFeedbackView = tapView
        viewport.addView(tapView, FrameLayout.LayoutParams(vpW, vpH))

        // Forward taps and swipes to virtual display via Shizuku
        val touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop.coerceAtLeast(dp(10))
        var downX = 0f
        var downY = 0f
        var downTimeMs = 0L

        viewport.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x.coerceIn(0f, view.width.toFloat().coerceAtLeast(1f))
                    downY = event.y.coerceIn(0f, view.height.toFloat().coerceAtLeast(1f))
                    downTimeMs = event.eventTime
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val upX = event.x.coerceIn(0f, view.width.toFloat().coerceAtLeast(1f))
                    val upY = event.y.coerceIn(0f, view.height.toFloat().coerceAtLeast(1f))
                    val w = view.width.coerceAtLeast(1)
                    val h = view.height.coerceAtLeast(1)

                    val vdX1 = ((downX / w) * vdW).roundToInt().coerceIn(0, vdW - 1)
                    val vdY1 = ((downY / h) * vdH).roundToInt().coerceIn(0, vdH - 1)
                    val vdX2 = ((upX / w) * vdW).roundToInt().coerceIn(0, vdW - 1)
                    val vdY2 = ((upY / h) * vdH).roundToInt().coerceIn(0, vdH - 1)
                    val dist = hypot((upX - downX).toDouble(), (upY - downY).toDouble())

                    if (dist < touchSlopPx) {
                        tapView.showTap(upX, upY)
                        dispatchVirtualInput("Tap ($vdX2, $vdY2)") {
                            AdbVirtualScreenController.injectTouchTap(context, session.displayId, vdX2, vdY2)
                        }
                    } else {
                        val dur = (event.eventTime - downTimeMs).toInt().coerceIn(120, 600)
                        tapView.showSwipe(downX, downY, upX, upY)
                        dispatchVirtualInput("Swipe") {
                            AdbVirtualScreenController.injectTouchSwipe(
                                context = context,
                                displayId = session.displayId,
                                x1 = vdX1,
                                y1 = vdY1,
                                x2 = vdX2,
                                y2 = vdY2,
                                durationMs = dur
                            )
                        }
                    }
                    true
                }
                else -> false
            }
        }

        card.addView(viewport, LinearLayout.LayoutParams(vpW, vpH))

        // Subtle status hint bar
        val hintText = TextView(context).apply {
            text = "Tap / swipe screen to control off-screen app"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(6), dp(3), dp(6), dp(3))
            setBackgroundColor(Color.parseColor("#FF0F172A"))
        }
        statusHintView = hintText
        card.addView(hintText, LinearLayout.LayoutParams(vpW, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Bottom Quick Control Strip
        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(6))
            setBackgroundColor(Color.parseColor("#FF1E293B"))
        }

        actionRow.addView(
            buildActionChip("Back") {
                dispatchVirtualInput("Back key") {
                    AdbVirtualScreenController.injectKeyEvent(context, session.displayId, KeyEvent.KEYCODE_BACK)
                }
            },
            LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(3) }
        )
        actionRow.addView(
            buildActionChip("Snap") {
                updateStatusHint("Refreshing frame...")
                requestRunnerSnap()
            },
            LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(3) }
        )
        actionRow.addView(
            buildActionChip("Virtual") {
                updateStatusHint("Sending to #${session.displayId}...")
                onSendToVirtual()
                mainHandler.postDelayed({ requestRunnerSnap() }, 500L)
            },
            LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(3) }
        )
        actionRow.addView(
            buildActionChip("Main") {
                updateStatusHint("Moving to main screen...")
                onBringToMain()
            },
            LinearLayout.LayoutParams(0, dp(40), 1f)
        )

        card.addView(actionRow, LinearLayout.LayoutParams(vpW, LinearLayout.LayoutParams.WRAP_CONTENT))
        expandedCard = card

        // --- 2. Collapsed Draggable Pill ---
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F00F172A"))
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Color.parseColor("#10B981"))
            }
            elevation = dp(8).toFloat()
        }
        attachWindowDragTouchListener(pill)

        val pillDot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#10B981"))
            }
        }
        pill.addView(pillDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(6) })

        val colTitle = TextView(context).apply {
            text = "$shortApp #${session.displayId} • $latestElapsedText"
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }
        collapsedTitleView = colTitle
        pill.addView(colTitle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val expandBtn = buildIconChipButton("⤢", "Expand preview") {
            applyCollapsedState(collapsed = false, notifyRunner = true)
        }
        pill.addView(expandBtn, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(8) })

        val pillCloseBtn = buildIconChipButton("✕", "Close preview", accentDanger = true) {
            dismiss(notifyCallback = true)
        }
        pill.addView(pillCloseBtn, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(4) })
        collapsedPill = pill

        root.addView(card)
        root.addView(pill)
        return root
    }

    private fun applyCollapsedState(collapsed: Boolean, notifyRunner: Boolean) {
        isCollapsed = collapsed
        expandedCard?.visibility = if (collapsed) View.GONE else View.VISIBLE
        collapsedPill?.visibility = if (collapsed) View.VISIBLE else View.GONE

        if (notifyRunner) {
            if (collapsed) {
                runCatching { AdbVirtualScreenController.sendCommand(session, "PREVIEW_OFF") }
            } else {
                val previewPath = session.previewFile?.absolutePath
                if (!previewPath.isNullOrBlank()) {
                    runCatching {
                        AdbVirtualScreenController.sendCommand(session, "PREVIEW_ON $previewPath")
                        AdbVirtualScreenController.sendCommand(session, "PREVIEW_SNAP")
                    }
                }
            }
        }
    }

    private fun dispatchVirtualInput(label: String, action: () -> Boolean) {
        updateStatusHint("Sending $label...")
        runCatching {
            inputExecutor.execute {
                val ok = runCatching { action() }.getOrDefault(false)
                if (!isDismissed.get()) {
                    mainHandler.post {
                        updateStatusHint(
                            if (ok) "$label sent • refreshing" else "$label failed (check Shizuku)"
                        )
                    }
                    if (ok && !isCollapsed) {
                        requestRunnerSnap()
                        mainHandler.postDelayed({
                            if (!isDismissed.get() && !isCollapsed) {
                                requestRunnerSnap()
                            }
                        }, 380L)
                    }
                }
            }
        }
    }

    private fun requestRunnerSnap() {
        runCatching {
            AdbVirtualScreenController.sendCommand(session, "PREVIEW_SNAP")
        }
    }

    private fun updateStatusHint(text: String) {
        statusHintView?.text = text
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachWindowDragTouchListener(handleView: View) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var dragging = false
        val slop = ViewConfiguration.get(context).scaledTouchSlop

        handleView.setOnTouchListener { _, event ->
            val params = layoutParams ?: return@setOnTouchListener false
            val wm = windowManager ?: return@setOnTouchListener false
            val root = rootContainer ?: return@setOnTouchListener false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (!dragging && hypot(dx.toDouble(), dy.toDouble()) > slop) {
                        dragging = true
                    }
                    if (dragging) {
                        // Gravity is TOP | END, so dragging left increases x
                        params.x = (initialX - dx.roundToInt()).coerceAtLeast(0)
                        params.y = (initialY + dy.roundToInt()).coerceAtLeast(0)
                        runCatching { wm.updateViewLayout(root, params) }
                    }
                    true
                }
                else -> dragging
            }
        }
    }

    private fun buildIconChipButton(
        symbol: String,
        desc: String,
        accentDanger: Boolean = false,
        onClick: () -> Unit
    ): TextView {
        return TextView(context).apply {
            text = symbol
            contentDescription = desc
            gravity = Gravity.CENTER
            setTextColor(if (accentDanger) Color.parseColor("#FDA4AF") else Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(if (accentDanger) Color.parseColor("#4C1D24") else Color.parseColor("#334155"))
                cornerRadius = dp(8).toFloat()
            }
            setOnClickListener { onClick() }
        }
    }

    private fun buildActionChip(label: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            contentDescription = label
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#334155"))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.parseColor("#475569"))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).roundToInt()
    }

    private class TapFeedbackView(context: Context) : View(context) {
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            color = Color.parseColor("#34D399")
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#4410B981")
        }
        private var startX = -1f
        private var startY = -1f
        private var endX = -1f
        private var endY = -1f
        private var isSwipe = false
        private var visibleUntilMs = 0L

        private val clearRunnable = Runnable {
            visibleUntilMs = 0L
            invalidate()
        }

        fun showTap(x: Float, y: Float) {
            startX = x
            startY = y
            endX = x
            endY = y
            isSwipe = false
            triggerFade()
        }

        fun showSwipe(x1: Float, y1: Float, x2: Float, y2: Float) {
            startX = x1
            startY = y1
            endX = x2
            endY = y2
            isSwipe = true
            triggerFade()
        }

        private fun triggerFade() {
            visibleUntilMs = System.currentTimeMillis() + 360L
            removeCallbacks(clearRunnable)
            postDelayed(clearRunnable, 370L)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (System.currentTimeMillis() > visibleUntilMs || startX < 0f) return
            if (isSwipe) {
                canvas.drawLine(startX, startY, endX, endY, ringPaint)
                canvas.drawCircle(endX, endY, 18f, fillPaint)
                canvas.drawCircle(endX, endY, 18f, ringPaint)
            } else {
                canvas.drawCircle(startX, startY, 22f, fillPaint)
                canvas.drawCircle(startX, startY, 22f, ringPaint)
            }
        }
    }

    companion object {
        private const val TAG = "StealthMiniPreview"
    }
}

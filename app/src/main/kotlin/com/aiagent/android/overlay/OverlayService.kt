package com.aiagent.android.overlay

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.setPadding
import com.aiagent.android.data.Settings
import kotlinx.coroutines.CompletableDeferred

/**
 * Floating overlay window used by the agent to:
 *   - ask the user a yes/no question (`SHOW_QUESTION`)
 *   - show transient status (`SHOW_STATUS`)
 *   - render a persistent **STOP button** that is the ONLY way the user can terminate the agent.
 *     The agent itself cannot exit; the model's `done` tool is logged but ignored. The button is
 *     a separate, draggable overlay window placed above all apps; the AI cannot tap it because:
 *       a) `dispatchGesture` would have to reach the overlay's window which it can but
 *       b) we publish the on-screen bounds via [stopButtonBounds] and `tap_at` / `swipe_at`
 *          refuse to dispatch when the target falls inside that rectangle.
 */
class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    private var titleView: TextView? = null
    private var bodyView: TextView? = null
    private var yesButton: Button? = null
    private var noButton: Button? = null
    private var openButton: Button? = null
    private var dismissButton: Button? = null

    private var stopRoot: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_QUESTION -> showQuestion(intent.getStringExtra(EXTRA_TEXT) ?: "")
            ACTION_SHOW_STATUS -> showStatus(intent.getStringExtra(EXTRA_TEXT) ?: "")
            ACTION_HIDE -> hideAll()
            ACTION_SHOW_STOP -> showStopButton()
            ACTION_HIDE_STOP -> hideStopButton()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun ensureView() {
        if (rootView != null) return
        val ctx: Context = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#1A237E")) // Indigo 900 — visible against most game UIs.
                setStroke(dp(2), Color.WHITE)
            }
        }

        titleView = TextView(ctx).apply {
            text = "AI Agent"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        bodyView = TextView(ctx).apply {
            text = ""
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(6), 0, dp(8))
        }

        val buttonsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        yesButton = makeButton(ctx, "Да", Color.parseColor("#1B5E20")) { deliver("yes") }
        noButton = makeButton(ctx, "Нет", Color.parseColor("#B71C1C")) { deliver("no") }
        openButton = makeButton(ctx, "Открыть", Color.parseColor("#0D47A1")) {
            val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            if (launch != null) startActivity(launch)
            deliver("open")
        }
        dismissButton = makeButton(ctx, "✕", Color.parseColor("#424242")) {
            deliver("dismiss")
            hideAll()
        }
        listOf(yesButton, noButton, openButton, dismissButton).forEach { btn ->
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(4)
            }
            buttonsRow.addView(btn, lp)
        }

        container.addView(titleView)
        container.addView(bodyView)
        container.addView(buttonsRow)

        rootView = container
        attachDragHandler(container)
    }

    private fun makeButton(ctx: Context, text: String, bg: Int, onClick: (View) -> Unit): Button =
        Button(ctx).apply {
            this.text = text
            setTextColor(Color.WHITE)
            isAllCaps = false
            setBackgroundColor(bg)
            setOnClickListener(onClick)
        }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragHandler(container: View) {
        var startX = 0
        var startY = 0
        var rawX = 0f
        var rawY = 0f
        container.setOnTouchListener { _, ev ->
            val params = container.layoutParams as? WindowManager.LayoutParams ?: return@setOnTouchListener false
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    rawX = ev.rawX
                    rawY = ev.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (ev.rawX - rawX).toInt()
                    params.y = startY + (ev.rawY - rawY).toInt()
                    runCatching { windowManager?.updateViewLayout(container, params) }
                    true
                }
                else -> false
            }
        }
    }

    private fun showQuestion(text: String) {
        ensureView()
        val view = rootView ?: return
        if (view.parent == null) attach(view)
        titleView?.text = "Агент спрашивает"
        bodyView?.text = text
        listOf(yesButton, noButton, openButton, dismissButton).forEach { it?.visibility = View.VISIBLE }
    }

    private fun showStatus(text: String) {
        ensureView()
        val view = rootView ?: return
        if (view.parent == null) attach(view)
        titleView?.text = "AI Agent"
        bodyView?.text = text
        // For status-only display we hide yes/no.
        yesButton?.visibility = View.GONE
        noButton?.visibility = View.GONE
        openButton?.visibility = View.VISIBLE
        dismissButton?.visibility = View.VISIBLE
    }

    private fun attach(view: View) {
        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.7f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = dp(60)
        }
        val alpha = Settings(this).overlayAlpha.coerceIn(0.1f, 1.0f)
        view.alpha = alpha
        runCatching { windowManager?.addView(view, params) }
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun showStopButton() {
        if (stopRoot != null) return
        val ctx: Context = this
        windowManager = windowManager ?: getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val btn = Button(ctx).apply {
            text = "🛑 СТОП"
            setTextColor(Color.WHITE)
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#C62828"))
                setStroke(dp(2), Color.WHITE)
            }
            setPadding(dp(20), dp(10), dp(20), dp(10))
            setOnClickListener {
                // Fire the registered ViewModel callback. The button stays visible until the
                // ViewModel asks us to hide it (after it has cancelled the agent job).
                runCatching { stopListener?.invoke() }
            }
        }

        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(80)
        }

        // Make the stop button draggable too — user requested "configurable".
        var startX = 0
        var startY = 0
        var rawX = 0f
        var rawY = 0f
        var dragged = false
        btn.setOnTouchListener { _, ev ->
            val lp = btn.layoutParams as? WindowManager.LayoutParams ?: return@setOnTouchListener false
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y; rawX = ev.rawX; rawY = ev.rawY; dragged = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - rawX).toInt()
                    val dy = (ev.rawY - rawY).toInt()
                    if (kotlin.math.abs(dx) > dp(6) || kotlin.math.abs(dy) > dp(6)) {
                        dragged = true
                        // For Gravity.TOP|END, x grows toward the LEFT edge.
                        lp.x = (startX - dx).coerceAtLeast(0)
                        lp.y = (startY + dy).coerceAtLeast(0)
                        runCatching { windowManager?.updateViewLayout(btn, lp) }
                        updateStopButtonBounds(btn)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragged) true else false // consume only if dragged, else let click fire
                }
                else -> false
            }
        }
        runCatching { windowManager?.addView(btn, params) }
        stopRoot = btn
        // Capture bounds once layout is done.
        btn.post { updateStopButtonBounds(btn) }
    }

    private fun updateStopButtonBounds(view: View) {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        stopButtonBounds = Rect(
            loc[0],
            loc[1],
            loc[0] + view.width,
            loc[1] + view.height,
        )
    }

    private fun hideStopButton() {
        val v = stopRoot
        if (v != null) {
            runCatching { windowManager?.removeView(v) }
        }
        stopRoot = null
        stopButtonBounds = null
    }

    private fun hideAll() {
        val view = rootView
        if (view != null) {
            runCatching { windowManager?.removeView(view) }
        }
        rootView = null
        titleView = null
        bodyView = null
        yesButton = null
        noButton = null
        openButton = null
        dismissButton = null
    }

    override fun onDestroy() {
        super.onDestroy()
        hideAll()
        hideStopButton()
    }

    private fun deliver(value: String) {
        val def = Pending.deferred
        if (def != null && !def.isCompleted) {
            def.complete(value)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * Singleton bridge between the agent (caller) and the OverlayService (UI).
     *
     * Set [deferred] before starting the service; the user's button choice will be delivered
     * into it. Cleared when the agent reads the value.
     */
    object Pending {
        @Volatile
        var deferred: CompletableDeferred<String>? = null
    }

    companion object {
        const val ACTION_SHOW_QUESTION = "com.aiagent.android.OVERLAY_QUESTION"
        const val ACTION_SHOW_STATUS = "com.aiagent.android.OVERLAY_STATUS"
        const val ACTION_HIDE = "com.aiagent.android.OVERLAY_HIDE"
        const val ACTION_SHOW_STOP = "com.aiagent.android.OVERLAY_SHOW_STOP"
        const val ACTION_HIDE_STOP = "com.aiagent.android.OVERLAY_HIDE_STOP"
        const val EXTRA_TEXT = "text"

        /** Screen-space bounds of the persistent STOP button while it's visible. Used by
         *  AgentAccessibilityService to refuse `tap_at` / `swipe_at` calls that would land on it. */
        @Volatile
        var stopButtonBounds: Rect? = null

        /** Invoked when the user taps the persistent STOP overlay button. Set by MainViewModel. */
        @Volatile
        var stopListener: (() -> Unit)? = null

        fun showQuestion(context: Context, text: String) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_SHOW_QUESTION
                putExtra(EXTRA_TEXT, text)
            }
            context.startService(intent)
        }

        fun showStatus(context: Context, text: String) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_SHOW_STATUS
                putExtra(EXTRA_TEXT, text)
            }
            context.startService(intent)
        }

        fun hide(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply { action = ACTION_HIDE }
            context.startService(intent)
        }

        fun showStop(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply { action = ACTION_SHOW_STOP }
            context.startService(intent)
        }

        fun hideStop(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply { action = ACTION_HIDE_STOP }
            context.startService(intent)
        }

        /**
         * Apply window-level brightness to the overlay view (if currently shown). `value` should be
         * in [0..1] for an explicit level or -1 to follow system. Has no effect when the overlay
         * is not currently visible — Android does not provide a way to change brightness of other
         * apps without WRITE_SETTINGS, which is intentionally not declared.
         */
        fun applyBrightness(@Suppress("UNUSED_PARAMETER") context: Context, value: Float) {
            pendingBrightness = value
        }

        @Volatile
        var pendingBrightness: Float = -1f
    }
}

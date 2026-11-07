package com.notedown.overlay

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.PixelFormat
import android.graphics.Color
import android.view.*
import android.widget.*
import com.notedown.domain.DictationState

class OverlayManager(private val service: AccessibilityService, private val start: () -> Unit, private val stop: () -> Unit,
    private val cancel: () -> Unit, private val retry: (String) -> Unit, private val dismiss: () -> Unit,
    private val moved: (Boolean, Float) -> Unit) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private var view: DraggableOverlayLayout? = null
    private var lastRender: String? = null
    private var params: WindowManager.LayoutParams? = null
    private var dockLeft = false
    private var verticalFraction: Float? = null
    private var lastPosition: Pair<Boolean, Float?>? = null
    private var placement = OverlayPlacement(0, 0, 0, 0)
    private var dragOrigin: Pair<Int, Int>? = null
    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).toInt()
    fun show(state: DictationState, keyboardTop: Int?, left: Boolean, fraction: Float?, elapsed: Long = 0) {
        // Do not replace a pressed child or reset the window halfway through a gesture.
        if (view?.touching == true) return
        val position = left to fraction
        if (position != lastPosition) {
            dockLeft = left
            verticalFraction = fraction
            lastPosition = position
        }
        placement = safePlacement(keyboardTop)
        val signature = "$state/$placement/$position/$elapsed"
        if (lastRender == signature && view != null) return
        lastRender = signature
        val layout = view ?: DraggableOverlayLayout(service).apply {
            onDrag = { dx, dy ->
                val p = params
                if (p != null) {
                    val origin = dragOrigin ?: (p.x to p.y).also { dragOrigin = it }
                    p.x = (origin.first + dx.toInt()).coerceIn(placement.left, placement.maxX(width))
                    p.y = (origin.second + dy.toInt()).coerceIn(placement.top, placement.maxY(height))
                    wm.updateViewLayout(this, p)
                }
            }
            onDrop = { save ->
                params?.let { p ->
                    if (save) {
                        dockLeft = placement.dockLeft(p.x, width)
                        verticalFraction = placement.fraction(p.y, height)
                        moved(dockLeft, verticalFraction!!)
                    }
                    dragOrigin = null
                    place(this)
                }
            }
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.rgb(35, 58, 47)); cornerRadius = dp(28).toFloat() }
            elevation = dp(8).toFloat()
        }.also {
            val p = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.LEFT }
            params = p; wm.addView(it, p); view = it
        }
        layout.removeAllViews()
        fun label(text: String) { layout.addView(TextView(service).apply { this.text = text; setTextColor(Color.WHITE); setPadding(dp(8),0,dp(8),0) }) }
        fun button(text: String, description: String = text, click: () -> Unit) {
            layout.addView(Button(service).apply { this.text = text; contentDescription = description; minWidth = dp(48); minimumHeight = dp(48); setOnClickListener { click() } })
        }
        when (state) {
            DictationState.Idle -> layout.addView(ImageButton(service).apply {
                setImageResource(com.notedown.R.drawable.ic_mic)
                imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                setBackgroundColor(Color.TRANSPARENT)
                contentDescription = "Start voice dictation"
                setOnClickListener { start() }
            }, LinearLayout.LayoutParams(dp(52), dp(52)))
            DictationState.Starting -> label("Starting microphone…")
            is DictationState.Recording -> { label("● ${elapsed / 60}:${(elapsed % 60).toString().padStart(2,'0')}"); button("Stop", click = stop); button("Cancel", click = cancel) }
            DictationState.Saving -> label("Saving…")
            is DictationState.Processing -> { layout.addView(ProgressBar(service), LinearLayout.LayoutParams(dp(28), dp(28))); label("Transcribing…") }
            is DictationState.Result -> { label("Saved"); button("Copy") { copy(state.text) }; button("Done", click = dismiss) }
            is DictationState.Error -> { label("Failed"); state.recordingId?.let { id -> button("Retry") { retry(id) } }; button("Details") { service.startActivity(android.content.Intent(service, com.notedown.MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }; button("×", "Dismiss", dismiss) }
        }
        place(layout)
    }

    private fun safePlacement(keyboardTop: Int?): OverlayPlacement {
        val margin = dp(12)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val metrics = wm.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return OverlayPlacement(insets.left + margin, insets.top + margin,
                metrics.bounds.width() - insets.right - margin,
                minOf(metrics.bounds.height() - insets.bottom, keyboardTop ?: metrics.bounds.height()) - margin)
        }
        // API 29: visible frame excludes system bars; keyboardTop is in screen coordinates.
        val frame = android.graphics.Rect()
        view?.getWindowVisibleDisplayFrame(frame)
        val metrics = service.resources.displayMetrics
        if (frame.isEmpty) frame.set(0, dp(24), metrics.widthPixels, metrics.heightPixels - dp(48))
        return OverlayPlacement(frame.left + margin, frame.top + margin, frame.right - margin,
            minOf(frame.bottom, keyboardTop ?: frame.bottom) - margin)
    }

    private fun place(layout: LinearLayout) {
        val p = params ?: return
        layout.measure(View.MeasureSpec.makeMeasureSpec((placement.right - placement.left).coerceAtLeast(1), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec((placement.bottom - placement.top).coerceAtLeast(1), View.MeasureSpec.AT_MOST))
        p.x = placement.x(dockLeft, layout.measuredWidth)
        p.y = placement.y(verticalFraction, layout.measuredHeight)
        wm.updateViewLayout(layout, p)
    }
    private fun copy(text: String) {
        service.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Notedown transcript", text).apply {
            if (android.os.Build.VERSION.SDK_INT >= 33) description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        })
        Toast.makeText(service, "Transcript copied", Toast.LENGTH_SHORT).show()
    }
    fun hide() { view?.let { wm.removeView(it) }; view = null; params = null; dragOrigin = null; lastRender = null }
}

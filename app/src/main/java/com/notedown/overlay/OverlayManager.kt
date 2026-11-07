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
    private val cancel: () -> Unit, private val retry: (String) -> Unit, private val dismiss: () -> Unit) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private var view: LinearLayout? = null
    private var lastRender: String? = null
    private var params: WindowManager.LayoutParams? = null
    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).toInt()
    fun show(state: DictationState, keyboardTop: Int?, left: Boolean, elapsed: Long = 0) {
        val signature = "$state/$keyboardTop/$left/$elapsed"
        if (lastRender == signature && view != null) return
        lastRender = signature
        val layout = view ?: LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.rgb(35, 58, 47)); cornerRadius = dp(28).toFloat() }
            elevation = dp(8).toFloat()
        }.also {
            val p = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or if (left) Gravity.LEFT else Gravity.RIGHT }
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
        val p = params ?: return
        p.gravity = Gravity.TOP or if (left) Gravity.LEFT else Gravity.RIGHT
        p.x = dp(12)
        val metrics = service.resources.displayMetrics
        p.y = ((keyboardTop ?: (metrics.heightPixels - dp(120))) - dp(70)).coerceIn(dp(32), (metrics.heightPixels - dp(90)).coerceAtLeast(dp(32)))
        wm.updateViewLayout(layout, p)
    }
    private fun copy(text: String) {
        service.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Notedown transcript", text).apply {
            if (android.os.Build.VERSION.SDK_INT >= 33) description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        })
        Toast.makeText(service, "Transcript copied", Toast.LENGTH_SHORT).show()
    }
    fun hide() { view?.let { wm.removeView(it) }; view = null; params = null }
}

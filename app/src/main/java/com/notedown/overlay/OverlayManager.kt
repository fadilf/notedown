package com.notedown.overlay

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.*
import android.widget.*
import com.notedown.R
import com.notedown.domain.DictationState
import com.notedown.data.CleanupProfile
import com.notedown.data.CleanupProfileIcon
import com.notedown.ui.drawableResource

class OverlayManager(private val service: AccessibilityService, private val start: (Rect) -> Unit, private val stop: () -> Unit,
    private val cancel: () -> Unit, private val retry: (String) -> Unit, private val dismiss: () -> Unit,
    private val moved: (Boolean, Float) -> Unit, private val finishWithProfile: (CleanupProfile) -> Unit = {}) {
    private val charcoal = service.getColor(R.color.brand_charcoal)
    private val cream = service.getColor(R.color.brand_cream)
    private val wm = service.getSystemService(WindowManager::class.java)
    private var view: DraggableOverlayLayout? = null
    private var lastRender: String? = null
    private var params: WindowManager.LayoutParams? = null
    private var dockLeft = false
    private var verticalFraction: Float? = null
    private var lastPosition: Pair<Boolean, Float?>? = null
    private var placement = OverlayPlacement(0, 0, 0, 0)
    private var dragOrigin: Pair<Int, Int>? = null
    private var timer: TextView? = null
    private var moreOpen = false
    private var profilesExpanded = false
    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).toInt()
    fun show(state: DictationState, keyboardTop: Int?, left: Boolean, fraction: Float?, elapsed: Long = 0,
        profiles: List<CleanupProfile> = emptyList()) {
        // The visible capture activity owns startup; accessibility overlays sit above it.
        if (state == DictationState.Starting) { hide(); return }
        // Do not replace a pressed child or reset the window halfway through a gesture.
        if (view?.touching == true) return
        val position = left to fraction
        if (position != lastPosition) {
            dockLeft = left
            verticalFraction = fraction
            lastPosition = position
        }
        placement = safePlacement(keyboardTop)
        timer?.text = "● ${elapsed / 60}:${(elapsed % 60).toString().padStart(2,'0')}"
        if (state !is DictationState.Recording) { moreOpen = false; profilesExpanded = false }
        val signature = "$state/$placement/$position/$profiles/$moreOpen/$profilesExpanded"
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
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }.also {
            val p = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.LEFT }
            params = p; wm.addView(it, p); view = it
        }
        val idle = state == DictationState.Idle
        layout.alpha = if (idle) 0.75f else 1f
        layout.setPadding(0, 0, 0, 0)
        layout.background = null
        layout.removeAllViews()
        timer = null
        layout.scrollArea = null
        val controls = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            if (!idle && state !is DictationState.Recording) {
                setPadding(dp(6), dp(4), dp(6), dp(4))
                background = GradientDrawable().apply { setColor(charcoal); cornerRadius = dp(28).toFloat() }
                elevation = dp(8).toFloat()
            }
        }
        layout.addView(controls)
        fun label(text: String): TextView = TextView(service).apply {
            this.text = text; setTextColor(cream); setPadding(dp(8),0,dp(8),0)
            controls.addView(this)
        }
        fun button(text: String, description: String = text, click: () -> Unit) {
            controls.addView(Button(service).apply {
                this.text = text; contentDescription = description
                minWidth = dp(48); minimumHeight = dp(48)
                backgroundTintList = ColorStateList.valueOf(cream)
                setTextColor(charcoal)
                setOnClickListener { click() }
            })
        }
        fun iconButton(icon: Int, description: String, click: () -> Unit) {
            controls.addView(ImageButton(service).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(charcoal)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = RippleDrawable(ColorStateList.valueOf(0x3316181D), GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(cream)
                }, null)
                contentDescription = description
                elevation = dp(8).toFloat()
                setOnClickListener { click() }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        when (state) {
            DictationState.Idle -> controls.addView(ImageButton(service).apply {
                setImageResource(com.notedown.R.drawable.ic_notedown)
                imageTintList = null
                setPadding(0, 0, 0, 0)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.TRANSPARENT)
                contentDescription = "Start voice dictation"
                setOnClickListener {
                    val location = IntArray(2)
                    layout.getLocationOnScreen(location)
                    start(Rect(location[0], location[1], location[0] + layout.width, location[1] + layout.height))
                }
            }, LinearLayout.LayoutParams(dp(52), dp(52)))
            DictationState.Starting -> Unit
            is DictationState.Recording -> {
                controls.contentDescription = "Recording controls"
                iconButton(R.drawable.ic_close, "Cancel", cancel)
                timer = TextView(service).apply {
                    text = "● ${elapsed / 60}:${(elapsed % 60).toString().padStart(2,'0')}"
                    contentDescription = "Elapsed recording time"
                    setTextColor(charcoal)
                    textSize = 16f
                    gravity = Gravity.CENTER
                    setSingleLine()
                    setPadding(dp(16), 0, dp(16), 0)
                    // Keep the row stable as the timer grows to the ten-minute limit.
                    minimumWidth = maxOf(dp(88), kotlin.math.ceil(paint.measureText("● 10:00")).toInt() + dp(32))
                    background = GradientDrawable().apply { setColor(cream); cornerRadius = dp(24).toFloat() }
                    elevation = dp(8).toFloat()
                    controls.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)).apply {
                        marginStart = dp(8); marginEnd = dp(8)
                    })
                }
                iconButton(R.drawable.ic_check, "Stop without cleanup", stop)
                // Profile pills match the full three-control row's width and height.
                controls.measure(View.MeasureSpec.makeMeasureSpec((placement.right - placement.left).coerceAtLeast(1), View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                val pillWidth = controls.measuredWidth
                val pillHeight = controls.measuredHeight
                controls.layoutParams = LinearLayout.LayoutParams(pillWidth, pillHeight)
                val shortcuts = profiles.filter { it.shortcut }
                val others = profiles.filterNot { it.shortcut }
                if (profiles.isNotEmpty() && !profilesExpanded) {
                    layout.addView(ImageButton(service).apply {
                        setImageResource(R.drawable.ic_menu)
                        imageTintList = ColorStateList.valueOf(charcoal)
                        setPadding(dp(12), dp(12), dp(12), dp(12))
                        background = RippleDrawable(ColorStateList.valueOf(0x3316181D), GradientDrawable().apply {
                            setColor(cream); cornerRadius = pillHeight / 2f
                        }, null)
                        elevation = dp(8).toFloat()
                        contentDescription = "Show cleanup profiles"
                        setOnClickListener {
                            profilesExpanded = true
                            layout.post { show(state, keyboardTop, left, fraction, elapsed, profiles) }
                        }
                    }, LinearLayout.LayoutParams(checkNotNull(timer).measuredWidth, pillHeight).apply { topMargin = dp(8) })
                } else if (profiles.isNotEmpty()) {
                    val scroll = ScrollView(service).apply {
                        background = null
                        isVerticalScrollBarEnabled = false
                    }
                    val choices = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
                    scroll.addView(choices)
                    fun choice(text: String, description: String = text, icon: CleanupProfileIcon? = null, click: () -> Unit) {
                        choices.addView(Button(service).apply {
                            this.text = text
                            contentDescription = description
                            minWidth = 0
                            minimumWidth = 0
                            minHeight = 0
                            minimumHeight = 0
                            isAllCaps = false
                            setSingleLine()
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            setPadding(dp(16), 0, dp(16), 0)
                            icon?.let {
                                val drawable = checkNotNull(service.getDrawable(it.drawableResource)).mutate().apply {
                                    setTint(charcoal)
                                    setBounds(0, 0, dp(20), dp(20))
                                }
                                setCompoundDrawablesRelative(drawable, null, null, null)
                                compoundDrawablePadding = dp(12)
                            }
                            background = RippleDrawable(ColorStateList.valueOf(0x3316181D), GradientDrawable().apply {
                                setColor(cream); cornerRadius = pillHeight / 2f
                            }, null)
                            setTextColor(charcoal)
                            setOnClickListener { click() }
                        }, LinearLayout.LayoutParams(pillWidth, pillHeight).apply {
                            if (choices.childCount > 0) topMargin = dp(8)
                        })
                    }
                    (if (moreOpen) others else shortcuts).forEach { profile ->
                        choice(profile.name, "Stop and apply ${profile.name}", profile.icon) {
                            if (profile.model.isBlank()) Toast.makeText(service, "Choose a cleanup model for ${profile.name} in Settings.", Toast.LENGTH_LONG).show()
                            else finishWithProfile(profile)
                        }
                    }
                    if (others.isNotEmpty()) choice(if (moreOpen) "Back to shortcuts" else "More…") {
                        moreOpen = !moreOpen
                        layout.post { show(state, keyboardTop, left, fraction, elapsed, profiles) }
                    }
                    val available = (placement.bottom - placement.top - pillHeight - dp(8)).coerceAtLeast(0)
                    val rows = (if (moreOpen) others.size else shortcuts.size) + if (others.isNotEmpty()) 1 else 0
                    layout.addView(scroll, LinearLayout.LayoutParams(pillWidth,
                        minOf(dp(240), available, pillHeight * rows + dp(8) * (rows - 1))).apply { topMargin = dp(8) })
                    layout.scrollArea = scroll
                }
            }
            DictationState.Saving -> label("Saving…")
            is DictationState.Processing -> { controls.addView(ProgressBar(service).apply { indeterminateTintList = ColorStateList.valueOf(cream) }, LinearLayout.LayoutParams(dp(28), dp(28))); label("Transcribing…") }
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
    fun hide() { view?.let { wm.removeView(it) }; view = null; params = null; dragOrigin = null; lastRender = null; timer = null; moreOpen = false; profilesExpanded = false }
}

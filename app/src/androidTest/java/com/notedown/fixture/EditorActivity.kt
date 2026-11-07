package com.notedown.fixture

import android.app.Activity
import android.os.Bundle
import android.os.Build
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Separate test-APK application window, standing in for a messenger's editor. */
class EditorActivity : Activity() {
    private var sawIme = false
    private var hideCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val providerMode = intent.getStringExtra("provider-mode")
        if (providerMode != null) {
            // A virtual editor whose provider reports its container from findFocus.
            // This exercises traversal through the real accessibility connection.
            setContentView(ProviderEditorView(this, providerMode))
            return
        }
        val editor = EditText(this).apply {
            contentDescription = "editor-primary"
            hint = "Dictation regression editor"
            setSingleLine(false)
            setText("Keep this text: ")
            setSelection(text.length)
        }
        val second = EditText(this).apply {
            contentDescription = "editor-secondary"
            hint = "Another message"
            setText("Other text: ")
            setSelection(text.length)
        }
        val password = EditText(this).apply {
            contentDescription = "editor-password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        // Expose actual IME hide transitions, including ones that recover before
        // the instrumentation test next observes the editor window.
        val imeStatus = TextView(this).apply {
            contentDescription = "editor-ime-hide-count"
            text = "0"
            setOnClickListener { hideCount = 0; text = "0" }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(editor); addView(second); addView(password)
            addView(imeStatus)
            // This activity also runs as the standalone test APK, where the app's
            // Kotlin runtime is absent. Nullable SAM parameters avoid Intrinsics checks.
            setOnApplyWindowInsetsListener { _: View?, suppliedInsets: WindowInsets? ->
                val insets = suppliedInsets ?: return@setOnApplyWindowInsetsListener WindowInsets.Builder().build()
                val visible = if (Build.VERSION.SDK_INT >= 30) insets.isVisible(WindowInsets.Type.ime())
                    else insets.systemWindowInsetBottom > insets.stableInsetBottom
                if (sawIme && !visible) {
                    hideCount++
                    imeStatus.text = hideCount.toString()
                }
                sawIme = visible
                insets
            }
        })
        editor.requestFocus()
        editor.postDelayed({ getSystemService(InputMethodManager::class.java).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT) }, 400)
    }
}

package com.notedown.fixture

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout

/** Separate test-APK application window, standing in for a messenger's editor. */
class EditorActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(editor); addView(second); addView(password)
        })
        editor.requestFocus()
        editor.postDelayed({ getSystemService(InputMethodManager::class.java).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT) }, 400)
    }
}

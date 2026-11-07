package com.notedown.fixture

import android.app.Activity
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

/** Separate test-APK application window, standing in for a messenger's editor. */
class EditorActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val editor = EditText(this).apply {
            hint = "Dictation regression editor"
            setSingleLine(false)
            setText("Keep this text: ")
            setSelection(text.length)
        }
        setContentView(editor)
        editor.requestFocus()
        editor.postDelayed({ getSystemService(InputMethodManager::class.java).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT) }, 400)
    }
}

package tribixbite.cleverkeys

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout

/** Debug-only editor for tests that send real touches through the running IME. */
class OpenJevTestEditorActivity : Activity() {
    lateinit var primary: EditText
    lateinit var secondary: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Dark)
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        primary = EditText(this).apply {
            id = android.R.id.edit
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            contentDescription = "OpenJev primary test editor"
            minLines = 3
        }
        secondary = EditText(this).apply {
            id = android.R.id.edit + 1
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            contentDescription = "OpenJev secondary test editor"
        }
        layout.addView(primary)
        layout.addView(secondary)
        setContentView(layout)
        focus(primary)
    }

    fun focus(editor: EditText) {
        editor.requestFocus()
        editor.post {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        }
    }
}

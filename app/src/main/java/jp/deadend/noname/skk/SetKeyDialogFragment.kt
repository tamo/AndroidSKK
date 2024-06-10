package jp.deadend.noname.skk

import android.app.Dialog
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.preference.PreferenceDialogFragmentCompat

class SetKeyDialogFragment : PreferenceDialogFragmentCompat() {
    private var mValue = SetKeyPreference.DEFAULT_VALUE
    private lateinit var mTextView: TextView

    override fun onBindDialogView(view: View) {
        super.onBindDialogView(view)

        val pref = preference
        if (pref is SetKeyPreference) { mValue = pref.value }
        mTextView = view.findViewById(R.id.textView) ?: return
        mTextView.text = getKeyName(mValue)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)

        dialog.setOnKeyListener { _, keyCode, event ->
            when (keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ENTER -> false
                KeyEvent.KEYCODE_HOME -> true
                else -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        mValue = encodeKey(event)
                        if (mValue != SetKeyPreference.DEFAULT_VALUE) {
                            mTextView.text = getKeyName(mValue)
                        }
                    }
                    true
                }
            }
        }

        return dialog
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        if (positiveResult) {
            val pref = preference
            if (pref is SetKeyPreference) { pref.value = mValue }
        }
    }

    companion object {
        fun newInstance(key: String): SetKeyDialogFragment {
            val fragment = SetKeyDialogFragment()
            val args = Bundle().apply {
                putString(ARG_KEY, key)
            }
            fragment.arguments = args

            return fragment
        }

        private const val SHIFT_PRESSED = 1
        private const val ALT_PRESSED   = 2
        private const val CTRL_PRESSED  = 4
        private const val META_PRESSED  = 8

        fun encodeKey(event: KeyEvent): Int {
            val keycode = event.keyCode
            var meta = 0
            if (event.metaState and KeyEvent.META_SHIFT_MASK != 0) {
                meta = meta or SHIFT_PRESSED
            }
            if (event.metaState and KeyEvent.META_ALT_MASK != 0) {
                meta = meta or ALT_PRESSED
            }
            if (event.metaState and KeyEvent.META_CTRL_MASK != 0) {
                meta = meta or CTRL_PRESSED
            }
            if (event.metaState and KeyEvent.META_META_MASK != 0) {
                meta = meta or META_PRESSED
            }

            return keycode shl 4 or meta
        }

        private fun getKeyName(key: Int): String {
            val result = StringBuilder()
            if (key and META_PRESSED != 0)  result.append("META+")
            if (key and CTRL_PRESSED != 0)  result.append("CTRL+")
            if (key and ALT_PRESSED != 0)   result.append("ALT+")
            if (key and SHIFT_PRESSED != 0) result.append("SHIFT+")

            when (key ushr 4) { // extract the keycode
                KeyEvent.KEYCODE_A -> result.append("A")
                KeyEvent.KEYCODE_B -> result.append("B")
                KeyEvent.KEYCODE_C -> result.append("C")
                KeyEvent.KEYCODE_D -> result.append("D")
                KeyEvent.KEYCODE_E -> result.append("E")
                KeyEvent.KEYCODE_F -> result.append("F")
                KeyEvent.KEYCODE_G -> result.append("G")
                KeyEvent.KEYCODE_H -> result.append("H")
                KeyEvent.KEYCODE_I -> result.append("I")
                KeyEvent.KEYCODE_J -> result.append("J")
                KeyEvent.KEYCODE_K -> result.append("K")
                KeyEvent.KEYCODE_L -> result.append("L")
                KeyEvent.KEYCODE_M -> result.append("M")
                KeyEvent.KEYCODE_N -> result.append("N")
                KeyEvent.KEYCODE_O -> result.append("O")
                KeyEvent.KEYCODE_P -> result.append("P")
                KeyEvent.KEYCODE_Q -> result.append("Q")
                KeyEvent.KEYCODE_R -> result.append("R")
                KeyEvent.KEYCODE_S -> result.append("S")
                KeyEvent.KEYCODE_T -> result.append("T")
                KeyEvent.KEYCODE_U -> result.append("U")
                KeyEvent.KEYCODE_V -> result.append("V")
                KeyEvent.KEYCODE_W -> result.append("W")
                KeyEvent.KEYCODE_X -> result.append("X")
                KeyEvent.KEYCODE_Y -> result.append("Y")
                KeyEvent.KEYCODE_Z -> result.append("Z")

                KeyEvent.KEYCODE_0 -> result.append("0")
                KeyEvent.KEYCODE_1 -> result.append("1")
                KeyEvent.KEYCODE_2 -> result.append("2")
                KeyEvent.KEYCODE_3 -> result.append("3")
                KeyEvent.KEYCODE_4 -> result.append("4")
                KeyEvent.KEYCODE_5 -> result.append("5")
                KeyEvent.KEYCODE_6 -> result.append("6")
                KeyEvent.KEYCODE_7 -> result.append("7")
                KeyEvent.KEYCODE_8 -> result.append("8")
                KeyEvent.KEYCODE_9 -> result.append("9")

                KeyEvent.KEYCODE_NUM -> result.append("NUM")
                KeyEvent.KEYCODE_SYM -> result.append("SYM")
                KeyEvent.KEYCODE_SPACE -> result.append("SPACE")
                KeyEvent.KEYCODE_DEL -> result.append("DEL")
                KeyEvent.KEYCODE_ENTER -> result.append("ENTER")
                KeyEvent.KEYCODE_TAB -> result.append("TAB")
                KeyEvent.KEYCODE_AT -> result.append("@")
                KeyEvent.KEYCODE_PERIOD -> result.append(".")
                KeyEvent.KEYCODE_COMMA -> result.append(",")
                KeyEvent.KEYCODE_APOSTROPHE -> result.append("'")
                KeyEvent.KEYCODE_EQUALS -> result.append("=")
                KeyEvent.KEYCODE_GRAVE -> result.append("`")
                KeyEvent.KEYCODE_MINUS -> result.append("-")
                KeyEvent.KEYCODE_PLUS -> result.append("+")
                KeyEvent.KEYCODE_SEMICOLON -> result.append(";")
                KeyEvent.KEYCODE_SLASH -> result.append("/")
                KeyEvent.KEYCODE_STAR -> result.append("*")

                KeyEvent.KEYCODE_DPAD_CENTER -> result.append("DPAD CENTER")
                KeyEvent.KEYCODE_DPAD_DOWN -> result.append("DPAD DOWN")
                KeyEvent.KEYCODE_DPAD_LEFT -> result.append("DPAD LEFT")
                KeyEvent.KEYCODE_DPAD_RIGHT -> result.append("DPAD RIGHT")
                KeyEvent.KEYCODE_DPAD_UP -> result.append("DPAD UP")
                KeyEvent.KEYCODE_MENU -> result.append("MENU")
                KeyEvent.KEYCODE_BACK -> result.append("BACK")
                KeyEvent.KEYCODE_CALL -> result.append("CALL")
                KeyEvent.KEYCODE_ENDCALL -> result.append("ENDCALL")
                KeyEvent.KEYCODE_CAMERA -> result.append("CAMERA")
                KeyEvent.KEYCODE_FOCUS -> result.append("FOCUS")
                KeyEvent.KEYCODE_SEARCH -> result.append("SEARCH")
                KeyEvent.KEYCODE_VOLUME_UP -> result.append("Volume UP")
                KeyEvent.KEYCODE_VOLUME_DOWN -> result.append("Volume DOWN")

                else -> result.append("Unknown")
            }

            return result.toString()
        }
    }
}
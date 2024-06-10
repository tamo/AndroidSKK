package jp.deadend.noname.skk

import android.content.Context
import android.content.res.TypedArray
import android.util.AttributeSet
import android.view.KeyEvent
import androidx.preference.DialogPreference

class SetKeyPreference(context: Context, attrs: AttributeSet?) : DialogPreference(context, attrs) {
    var value: Int = DEFAULT_VALUE
        set(v) {
            field = v
            persistInt(v)
        }

    init {
        dialogLayoutResource = R.layout.dialogpreference_set_key
        setPositiveButtonText(android.R.string.ok)
        setNegativeButtonText(android.R.string.cancel)
        dialogIcon = null
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int) = a.getInt(index, DEFAULT_VALUE)

    override fun onSetInitialValue(defaultValue: Any?) {
        value = getPersistedInt(defaultValue as? Int ?: DEFAULT_VALUE)
    }

    companion object {
        const val DEFAULT_VALUE = KeyEvent.KEYCODE_UNKNOWN shl 4
    }
}

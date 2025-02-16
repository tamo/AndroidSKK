package jp.deadend.noname.skk

import android.view.KeyEvent

//const val DEFAULT_VALUE = KeyEvent.KEYCODE_UNKNOWN shl 4

private const val SHIFT_PRESSED = 1
private const val ALT_PRESSED = 2
private const val CTRL_PRESSED = 4
private const val META_PRESSED = 8

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

fun getKeyName(key: Int): String {
    val rawKeyCode = key ushr 4
    if (KeyEvent.isModifierKey(rawKeyCode)) return ""

    val result = StringBuilder()
    if (key and META_PRESSED != 0)  result.append("META+")
    if (key and CTRL_PRESSED != 0)  result.append("CTRL+")
    if (key and ALT_PRESSED != 0)   result.append("ALT+")
    if (key and SHIFT_PRESSED != 0) result.append("SHIFT+")

    // extract the keycode
    result.append(
        KeyEvent.keyCodeToString(rawKeyCode)
            .removePrefix("KEYCODE_")
    )

    return result.toString()
}

package jp.deadend.noname.skk

import android.view.KeyCharacterMap
import android.view.KeyEvent

//const val DEFAULT_VALUE = KeyEvent.KEYCODE_UNKNOWN

private const val SHIFT_PRESSED = 1
private const val ALT_PRESSED = 2
private const val CTRL_PRESSED = 4
private const val META_PRESSED = 8

private val charMap: KeyCharacterMap by lazy {
    KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
}

fun encodeKey(event: KeyEvent): Int {
    val uc = event.getUnicodeChar(event.metaState and KeyEvent.META_SHIFT_MASK)
    val lower = Character.toLowerCase(uc)
    var meta = 0
    if (uc != 0 && uc != lower) {
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

    return meta shl 28 or if (lower != 0) lower else charMap.get(event.keyCode, 0)
}

fun encodeKey(charCode: Int): Int {
    val meta = if (Character.isUpperCase(charCode)) SHIFT_PRESSED else 0
    val codeLower = if (meta and SHIFT_PRESSED != 0) Character.toLowerCase(charCode) else charCode
    return meta shl 28 or codeLower
}

// returns Pair<lowerCharCode, isShifted>
fun decodeKey(keyCode: Int): Pair<Int, Boolean> {
    return keyCode and 0xFFFFFFF to ((keyCode shr 28 and SHIFT_PRESSED) != 0)
}

fun getKeyName(key: Int): String {
    val charCode = key and 0xFFFFFFF
    if (charCode == 0) return ""

    val result = StringBuilder()
    val meta = key shr 28
    if (meta and META_PRESSED != 0) result.append("META+")
    if (meta and CTRL_PRESSED != 0) result.append("CTRL+")
    if (meta and ALT_PRESSED != 0) result.append("ALT+")
    if (meta and SHIFT_PRESSED != 0) result.append("SHIFT+")

    val ev = charMap.getEvents(charArrayOf(Char(charCode)))?.get(0) ?: return ""
    val kc = KeyEvent.keyCodeToString(ev.keyCode).removePrefix("KEYCODE_")
    result.append(kc)

    return result.toString()
}

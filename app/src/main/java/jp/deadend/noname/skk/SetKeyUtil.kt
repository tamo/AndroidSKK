package jp.deadend.noname.skk

import android.view.KeyCharacterMap
import android.view.KeyEvent

private const val META_SHIFT = 32 - 5
internal const val RAW_KEYCODE = 1 shl (META_SHIFT + 4)
internal const val META_PRESSED = 1 shl (META_SHIFT + 3)
internal const val CTRL_PRESSED = 1 shl (META_SHIFT + 2)
internal const val ALT_PRESSED = 1 shl (META_SHIFT + 1)
internal const val SHIFT_PRESSED = 1 shl (META_SHIFT + 0)
private const val CHAR_CODE_MASK = SHIFT_PRESSED - 1

private var deviceId = KeyCharacterMap.VIRTUAL_KEYBOARD
private var charMap = KeyCharacterMap.load(deviceId)

fun encodeKey(event: KeyEvent): Int {
    if (deviceId != event.deviceId) {
        android.util.Log.d("SKK", "encodeKey switched charMap to device $deviceId")
        deviceId = event.deviceId
        charMap = event.keyCharacterMap
    }

    val uc = event.getUnicodeChar(event.metaState and KeyEvent.META_SHIFT_MASK)
    val lower = Character.toLowerCase(uc)
    val isKeycode = lower == 0 && !KeyEvent.isModifierKey(event.keyCode)
    val c = if (isKeycode) event.keyCode else lower
    val meta = when {
        // 大文字以外はシフトを押していてもシフトなしの記号として扱うことで、キーボード依存をなくす
        uc != 0 -> if (uc != lower) SHIFT_PRESSED else 0
        event.metaState and KeyEvent.META_SHIFT_MASK != 0 -> SHIFT_PRESSED
        else -> 0
    } or if (event.metaState and KeyEvent.META_ALT_MASK != 0) ALT_PRESSED
    else 0 or if (event.metaState and KeyEvent.META_CTRL_MASK != 0) CTRL_PRESSED
    else 0 or if (event.metaState and KeyEvent.META_META_MASK != 0) META_PRESSED
    else 0 or if (isKeycode) RAW_KEYCODE else 0

    return meta or c
}

fun encodeKey(charCode: Int): Int {
    val meta = if (Character.isUpperCase(charCode)) SHIFT_PRESSED else 0
    val codeLower = if (meta and SHIFT_PRESSED != 0) Character.toLowerCase(charCode) else charCode
    return meta or codeLower
}

// returns Pair<lowerCharCode, isShifted>
fun decodeKey(keyCode: Int): Pair<Int, Boolean> {
    return keyCode and CHAR_CODE_MASK to ((keyCode and SHIFT_PRESSED) != 0)
}

fun getKeyName(key: Int): String {
    val charCode = key and CHAR_CODE_MASK
    if (charCode == 0) return "" // モディファイアのみの場合

    return buildString {
        if (key and META_PRESSED != 0) append("META+")
        if (key and CTRL_PRESSED != 0) append("CTRL+")
        if (key and ALT_PRESSED != 0) append("ALT+")
        if (key and SHIFT_PRESSED != 0) append("SHIFT+")

        val keyCode = if (key and RAW_KEYCODE != 0) charCode
        else charMap.getEvents(charArrayOf(Char(charCode)))?.firstOrNull()?.keyCode ?: 0
        val name = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
        append(name)
    }
}

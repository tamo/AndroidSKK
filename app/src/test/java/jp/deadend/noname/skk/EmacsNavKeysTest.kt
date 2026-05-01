package jp.deadend.noname.skk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EmacsNavKeysTest {

    // --- 無効値の検証 ---

    @Test
    fun testNavKeyDisabled_isZero() {
        // 0 = KEYCODE_UNKNOWN, 実際には押せないので番兵値として安全
        assertEquals(0, NAV_KEY_DISABLED)
    }

    @Test
    fun testNavKeyDisabled_doesNotEqualAnyDefaultKey() {
        // デフォルトキーはすべて 0 より大きいこと（無効値と衝突しない）
        assertNotEquals(NAV_KEY_DISABLED, NAV_LINE_START_KEY_DEFAULT)
        assertNotEquals(NAV_KEY_DISABLED, NAV_LINE_END_KEY_DEFAULT)
        assertNotEquals(NAV_KEY_DISABLED, NAV_FORWARD_KEY_DEFAULT)
        assertNotEquals(NAV_KEY_DISABLED, NAV_BACKWARD_KEY_DEFAULT)
    }

    // --- デフォルトキーエンコード値の検証 ---
    // エンコード値 = modifierBits (CTRL = 4) shl 28 or keyCode

    @Test
    fun testDefaultKeyEncoding_navLineStart_isCtrlA() {
        assertEquals(NAV_LINE_START_KEY_DEFAULT, 4 shl 28 or 'a'.code)
    }

    @Test
    fun testDefaultKeyEncoding_navLineEnd_isCtrlE() {
        assertEquals(NAV_LINE_END_KEY_DEFAULT, 4 shl 28 or 'e'.code)
    }

    @Test
    fun testDefaultKeyEncoding_navForward_isCtrlF() {
        assertEquals(NAV_FORWARD_KEY_DEFAULT, 'f'.code or (4 shl 28))
    }

    @Test
    fun testDefaultKeyEncoding_navBackward_isCtrlB() {
        assertEquals(NAV_BACKWARD_KEY_DEFAULT, 'b'.code or (4 shl 28))
    }

    // --- resolveEmacsNavAction() のロジック検証 ---

    @Test
    fun testResolveNavAction_transientState_consumesKey() {
        // 変換中（SKKKanjiState 等）: isTransient=true → CONSUME（無視して飲み込む）
        assertEquals(
            EmacsNavAction.CONSUME,
            resolveEmacsNavAction(isTransient = true, isAsciiState = false, emacsNavInAscii = true)
        )
    }

    @Test
    fun testResolveNavAction_transientAsciiState_consumeTakesPriority() {
        // isTransient が ASCII 判定より優先される（理論上はありえないが安全性確認）
        assertEquals(
            EmacsNavAction.CONSUME,
            resolveEmacsNavAction(isTransient = true, isAsciiState = true, emacsNavInAscii = false)
        )
    }

    @Test
    fun testResolveNavAction_hiraganaState_navigates() {
        // ひらがなモード: isTransient=false, isAscii=false → NAVIGATE
        assertEquals(
            EmacsNavAction.NAVIGATE,
            resolveEmacsNavAction(isTransient = false, isAsciiState = false, emacsNavInAscii = true)
        )
    }

    @Test
    fun testResolveNavAction_katakanaState_navigates() {
        // カタカナモード: isTransient=false, isAscii=false → NAVIGATE
        assertEquals(
            EmacsNavAction.NAVIGATE,
            resolveEmacsNavAction(
                isTransient = false,
                isAsciiState = false,
                emacsNavInAscii = false
            )
        )
    }

    @Test
    fun testResolveNavAction_asciiState_navInAsciiEnabled_navigates() {
        // ASCII モードで A案（emacsNavInAscii=true）: IME がインターセプトして NAVIGATE
        assertEquals(
            EmacsNavAction.NAVIGATE,
            resolveEmacsNavAction(isTransient = false, isAsciiState = true, emacsNavInAscii = true)
        )
    }

    @Test
    fun testResolveNavAction_asciiState_navInAsciiDisabled_passesThrough() {
        // ASCII モードで B案（emacsNavInAscii=false）: アプリに委ねる PASS_THROUGH
        assertEquals(
            EmacsNavAction.PASS_THROUGH,
            resolveEmacsNavAction(isTransient = false, isAsciiState = true, emacsNavInAscii = false)
        )
    }
}

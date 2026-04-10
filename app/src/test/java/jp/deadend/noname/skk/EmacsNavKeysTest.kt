package jp.deadend.noname.skk

import org.junit.Assert.assertEquals
import org.junit.Test

class EmacsNavKeysTest {

    // --- 無効値の検証 ---

    @Test
    fun testNavKeyDisabled_isZero() {
        // 0 = KEYCODE_UNKNOWN shl 4, 実際には押せないので番兵値として安全
        assertEquals(0, NAV_KEY_DISABLED)
    }

    @Test
    fun testNavKeyDisabled_doesNotEqualAnyDefaultKey() {
        // デフォルトキーはすべて 0 より大きいこと（無効値と衝突しない）
        assert(NAV_LINE_START_KEY_DEFAULT != NAV_KEY_DISABLED)
        assert(NAV_LINE_END_KEY_DEFAULT   != NAV_KEY_DISABLED)
        assert(NAV_FORWARD_KEY_DEFAULT    != NAV_KEY_DISABLED)
        assert(NAV_BACKWARD_KEY_DEFAULT   != NAV_KEY_DISABLED)
    }

    // --- デフォルトキーエンコード値の検証 ---
    // エンコード値 = keyCode shl 4 or modifierBits (CTRL = 4)

    @Test
    fun testDefaultKeyEncoding_navLineStart_isCtrlA() {
        // KEYCODE_A = 29: 29 shl 4 or 4 = 464 + 4 = 468
        assertEquals(NAV_LINE_START_KEY_DEFAULT, 29 shl 4 or 4)
    }

    @Test
    fun testDefaultKeyEncoding_navLineEnd_isCtrlE() {
        // KEYCODE_E = 33: 33 shl 4 or 4 = 528 + 4 = 532
        assertEquals(NAV_LINE_END_KEY_DEFAULT, 33 shl 4 or 4)
    }

    @Test
    fun testDefaultKeyEncoding_navForward_isCtrlF() {
        // KEYCODE_F = 34: 34 shl 4 or 4 = 544 + 4 = 548
        assertEquals(NAV_FORWARD_KEY_DEFAULT, 34 shl 4 or 4)
    }

    @Test
    fun testDefaultKeyEncoding_navBackward_isCtrlB() {
        // KEYCODE_B = 30: 30 shl 4 or 4 = 480 + 4 = 484
        assertEquals(NAV_BACKWARD_KEY_DEFAULT, 30 shl 4 or 4)
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
            resolveEmacsNavAction(isTransient = false, isAsciiState = false, emacsNavInAscii = false)
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

package jp.deadend.noname.skk

// 0 = 未設定（無効）。KEYCODE_UNKNOWN(0) shl 4 = 0 は実際には押せないキーなので安全な番兵値
const val NAV_KEY_DISABLED = 0

// デフォルトエンコード値: keyCode shl 4 or modifierBits (CTRL = 4)
const val NAV_LINE_START_KEY_DEFAULT = 468  // Ctrl+A: KEYCODE_A(29) shl 4 or 4
const val NAV_LINE_END_KEY_DEFAULT = 532  // Ctrl+E: KEYCODE_E(33) shl 4 or 4
const val NAV_FORWARD_KEY_DEFAULT = 548  // Ctrl+F: KEYCODE_F(34) shl 4 or 4
const val NAV_BACKWARD_KEY_DEFAULT = 484  // Ctrl+B: KEYCODE_B(30) shl 4 or 4

enum class EmacsNavAction { NAVIGATE, CONSUME, PASS_THROUGH }

/**
 * Emacs ナビキーが押されたときの振る舞いを決定する純粋関数。
 *
 * - isTransient=true (▽モード・候補選択中等): キーを飲み込んで無視 (CONSUME)
 * - isAsciiState=true かつ emacsNavInAscii=false (B案): アプリに委ねる (PASS_THROUGH)
 * - それ以外: カーソル移動を実行 (NAVIGATE)
 */
fun resolveEmacsNavAction(
    isTransient: Boolean,
    isAsciiState: Boolean,
    emacsNavInAscii: Boolean
): EmacsNavAction = when {
    isTransient -> EmacsNavAction.CONSUME
    isAsciiState && !emacsNavInAscii -> EmacsNavAction.PASS_THROUGH
    else -> EmacsNavAction.NAVIGATE
}

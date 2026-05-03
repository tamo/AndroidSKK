package jp.deadend.noname.skk

// 0 = 未設定（無効）。KEYCODE_UNKNOWN(0) は実際には押せないキーなので安全な番兵値
const val NAV_KEY_DISABLED = 0

const val NAV_LINE_START_KEY_DEFAULT = CTRL_PRESSED or 'a'.code
const val NAV_LINE_END_KEY_DEFAULT = CTRL_PRESSED or 'e'.code
const val NAV_FORWARD_KEY_DEFAULT = CTRL_PRESSED or 'f'.code
const val NAV_BACKWARD_KEY_DEFAULT = CTRL_PRESSED or 'b'.code

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

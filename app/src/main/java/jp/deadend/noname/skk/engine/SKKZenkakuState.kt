package jp.deadend.noname.skk.engine

import jp.deadend.noname.skk.R
import jp.deadend.noname.skk.hankaku2zenkaku
import jp.deadend.noname.skk.isShifted
import jp.deadend.noname.skk.lowerCode

// 全角英数モード
object SKKZenkakuState : SKKState {
    override val isTemporaryView = true
    override val isJapanese = false
    override val icon = R.drawable.ic_full_alphabet

    override fun handleKanaKey(context: SKKEngine) {
        context.changeState(SKKHiraganaState)
    }

    override fun handleEnter(context: SKKEngine): Boolean = false

    override fun processKey(context: SKKEngine, keyCode: Int) {
        val lower = keyCode.lowerCode
        val charCode = if (keyCode.isShifted) Character.toUpperCase(lower) else lower
        context.commitTextSKK(hankaku2zenkaku(Char(charCode).toString()))
    }

    override fun afterBackspace(context: SKKEngine) {}

    override fun handleCancel(context: SKKEngine, reconvert: Boolean): Boolean {
        return SKKHiraganaState.handleCancel(context, reconvert)
    }

    override fun changeToFlick(context: SKKEngine): Boolean {
        context.changeState(context.kanaState, true) // こちらはキーボードも変更
        return true
    }
}

package jp.deadend.noname.skk.engine

import jp.deadend.noname.skk.R
import jp.deadend.noname.skk.hankaku2zenkaku

// import jp.deadend.noname.skk.skkPrefs

// 全角英数モード
object SKKZenkakuState : SKKState {
    override val isTransient = false
    override val icon =
        R.drawable.ic_full_alphabet

    override fun handleKanaKey(context: SKKEngine) {
        context.changeState(SKKHiraganaState)
    }

    override fun processKey(context: SKKEngine, pcode: Int) {
        hankaku2zenkaku(Char(pcode).toString())?.let {
            context.commitTextSKK(it)
        }
    }

    override fun afterBackspace(context: SKKEngine) {}

    override fun handleCancel(context: SKKEngine): Boolean {
        return SKKHiraganaState.handleCancel(context)
    }

    override fun changeToFlick(context: SKKEngine): Boolean {
        context.changeState(context.kanaState, true) // こちらはキーボードも変更
        return true
    }
}

package jp.deadend.noname.skk.engine

import jp.deadend.noname.skk.R
import jp.deadend.noname.skk.hiragana2katakana
import jp.deadend.noname.skk.skkPrefs

// カタカナモード
object SKKKatakanaState : SKKState {
    override val isTransient = false
    override val icon =
        R.drawable.ic_katakana

    override fun handleKanaKey(context: SKKEngine) {
        if (skkPrefs.toggleKanaKey) {
            context.changeState(SKKASCIIState, true)
        } else {
            context.changeState(SKKHiraganaState)
        }
    }

    override fun processKey(context: SKKEngine, pcode: Int) {
        if (context.changeInputMode(pcode)) return
        SKKHiraganaState.processKana(context, pcode) { engine, hchr ->
            val str = hiragana2katakana(hchr)
            if (str != null) engine.commitTextSKK(str)
            engine.mComposing.setLength(0)
        }
    }

    override fun afterBackspace(context: SKKEngine) {
        SKKHiraganaState.afterBackspace(context)
    }

    override fun handleCancel(context: SKKEngine): Boolean {
        return SKKHiraganaState.handleCancel(context)
    }

    override fun changeToFlick(context: SKKEngine): Boolean {
        return false
    }
}

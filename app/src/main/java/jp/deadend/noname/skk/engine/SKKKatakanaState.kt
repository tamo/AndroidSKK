package jp.deadend.noname.skk.engine

import jp.deadend.noname.skk.R
import jp.deadend.noname.skk.hiragana2katakana
import jp.deadend.noname.skk.skkPrefs

object SKKKatakanaState : SKKState {
    override val icon = R.drawable.ic_katakana

    override fun handleKanaKey(context: SKKEngine) {
        if (skkPrefs.toggleKanaKey) {
            context.changeState(SKKASCIIState, true)
        } else {
            context.changeState(SKKHiraganaState)
        }
    }

    override fun handleEnter(context: SKKEngine): Boolean = SKKHiraganaState.handleEnter(context)

    override fun processKey(context: SKKEngine, keyCode: Int) {
        if (context.changeInputMode(keyCode)) return
        SKKHiraganaState.processKana(context, keyCode) { engine, hiraganaChar ->
            val str = hiragana2katakana(hiraganaChar)
            engine.commitTextSKK(str)
            engine.mComposing.setLength(0)
        }
    }

    override fun afterBackspace(context: SKKEngine) {
        SKKHiraganaState.afterBackspace(context)
    }

    override fun handleCancel(context: SKKEngine, reconvert: Boolean): Boolean {
        return SKKHiraganaState.handleCancel(context, reconvert)
    }

    override fun changeToFlick(context: SKKEngine): Boolean {
        return false
    }
}

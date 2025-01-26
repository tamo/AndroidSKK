package jp.deadend.noname.skk.engine

import jp.deadend.noname.skk.R
import jp.deadend.noname.skk.hirakana2katakana
import jp.deadend.noname.skk.skkPrefs
import jp.deadend.noname.skk.zenkaku2hankaku

// 半角ｶﾀｶﾅモード
object SKKHanKanaState : SKKState {
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
            val str = zenkaku2hankaku(hirakana2katakana(hchr))
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

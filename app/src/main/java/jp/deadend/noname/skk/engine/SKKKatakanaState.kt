package jp.deadend.noname.skk.engine

import android.os.Build
import jp.deadend.noname.skk.R
import jp.deadend.noname.skk.hirakana2katakana

// カタカナモード
object SKKKatakanaState : SKKState {
    override val isTransient = false
    override val icon = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        R.drawable.ic_katakana
    } else {
        R.drawable.immodeic_katakana
    }

    override fun handleKanaKey(context: SKKEngine) {
        context.changeState(SKKHiraganaState)
    }

    override fun processKey(context: SKKEngine, pcode: Int) {
        if (context.changeInputMode(pcode, false)) return
        SKKHiraganaState.processKana(context, pcode) { engine, hchr ->
            val str = hirakana2katakana(hchr)
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
}

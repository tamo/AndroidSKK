package jp.deadend.noname.skk.engine

import android.os.Build
import jp.deadend.noname.skk.R
import jp.deadend.noname.skk.hankaku2zenkaku

// Abbrevモード(▽モード)
object SKKAbbrevState : SKKState {
    override val isTransient = true
    override val icon = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        R.drawable.ic_abbrev
    } else {
        R.drawable.immodeic_eng2jp
    }

    override fun handleKanaKey(context: SKKEngine) {
        context.changeState(SKKHiraganaState, change = false, recover = true)
    }

    override fun processKey(context: SKKEngine, pcode: Int) {
        context.apply {
            // スペースで変換するかそのままComposingに積む
            when (pcode) {
                ' '.code -> if (mKanjiKey.isNotEmpty()) conversionStart(mKanjiKey)
                -1010 -> {
                    // 全角変換
                    val buf = mKanjiKey.map { hankaku2zenkaku(it.code).toChar() }.joinToString("")
                    commitTextSKK(buf, 1)
                    handleKanaKey(context)
                }

                else -> {
                    mKanjiKey.append(pcode.toChar())
                    setComposingTextSKK(mKanjiKey, 1)
                    updateSuggestions(mKanjiKey.toString())
                }
            }
        }
    }

    override fun afterBackspace(context: SKKEngine) {
        context.apply {
            setComposingTextSKK(mKanjiKey, 1)
            updateSuggestions(mKanjiKey.toString())
        }
    }

    override fun handleCancel(context: SKKEngine): Boolean {
        context.changeState(SKKHiraganaState, change = false, recover = true)
        return true
    }

    override fun changeToFlick(context: SKKEngine): Boolean {
        context.changeState(context.kanaState, change = true)
        return true
    }
}

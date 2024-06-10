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
        context.changeState(SKKHiraganaState)
    }

    override fun processKey(context: SKKEngine, pcode: Int) {
        val kanjiKey = context.mKanjiKey

        // スペースで変換するかそのままComposingに積む
        when (pcode) {
            ' '.code -> if (kanjiKey.isNotEmpty()) context.conversionStart(kanjiKey)
            -1010 -> {
                // 全角変換
                val buf = kanjiKey.map { hankaku2zenkaku(it.code).toChar() }.joinToString("")
                context.commitTextSKK(buf, 1)
                context.changeState(SKKHiraganaState)
            }
            else -> {
                kanjiKey.append(pcode.toChar())
                context.setComposingTextSKK(kanjiKey, 1)
                context.updateSuggestions(kanjiKey.toString())
            }
        }
    }

    override fun afterBackspace(context: SKKEngine) {
        val kanjiKey = context.mKanjiKey.toString()

        context.setComposingTextSKK(kanjiKey, 1)
        context.updateSuggestions(kanjiKey)
    }

    override fun handleCancel(context: SKKEngine): Boolean {
        context.changeState(SKKHiraganaState)
        return true
    }
}

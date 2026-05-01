package jp.deadend.noname.skk.engine

import jp.deadend.noname.skk.ModeKey
import jp.deadend.noname.skk.R
import jp.deadend.noname.skk.hankaku2zenkaku
import jp.deadend.noname.skk.skkPrefs

// Abbrevモード(▽モード)
object SKKAbbrevState : SKKState {
    override val isTransient = true
    override val icon =
        R.drawable.ic_abbrev

    override fun handleKanaKey(context: SKKEngine) {
        context.changeState(SKKHiraganaState)
    }

    override fun processKey(context: SKKEngine, keyCode: Int) {
        context.apply {
            // スペースで変換するかそのままComposingに積む
            when (keyCode) {
                ' '.code -> if (mKanjiKey.isNotEmpty()) conversionStart(mKanjiKey)
                skkPrefs.hankakuKanaKey, ModeKey.HANKAKU_KANA.code -> {
                    // 全角変換
                    hankaku2zenkaku(mKanjiKey.toString())?.let { zen ->
                        commitTextSKK(zen)
                    }
                    handleKanaKey(context)
                }

                skkPrefs.kanaKey, ModeKey.KANA.code -> {
                    changeState(SKKKanjiState)
                }

                else -> {
                    mKanjiKey.append(keyCode.toChar())
                    setComposingTextSKK(mKanjiKey)
                    updateSuggestions(mKanjiKey.toString())
                }
            }
        }
    }

    override fun afterBackspace(context: SKKEngine) {
        context.apply {
            setComposingTextSKK(mKanjiKey)
            updateSuggestions(mKanjiKey.toString())
        }
    }

    override fun handleCancel(context: SKKEngine): Boolean {
        context.mKanjiKey.setLength(0) // 確定させない
        context.changeState(SKKHiraganaState)
        return true
    }

    override fun changeToFlick(context: SKKEngine): Boolean {
        context.changeState(context.kanaState, true)
        return true
    }
}

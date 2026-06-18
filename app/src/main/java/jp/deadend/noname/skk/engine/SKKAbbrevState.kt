package jp.deadend.noname.skk.engine

import jp.deadend.noname.skk.R
import jp.deadend.noname.skk.hankaku2zenkaku
import jp.deadend.noname.skk.isShifted
import jp.deadend.noname.skk.lowerCode
import jp.deadend.noname.skk.skkPrefs

// 日本語辞書で英数を変換するための Preedit (▽モード)
object SKKAbbrevState : SKKState {
    override val isTransient = true
    override val canComplete = true
    override val prefix = "▽"
    override val isJapanese = false
    override val icon = R.drawable.ic_abbrev

    override fun handleKanaKey(context: SKKEngine) {
        context.changeState(SKKPreeditState)
    }

    override fun processKey(context: SKKEngine, keyCode: Int) {
        val lower = keyCode.lowerCode
        val charCode = if (keyCode.isShifted) Character.toUpperCase(lower) else lower
        context.apply {
            when (keyCode) {
                skkPrefs.hankakuKanaKey -> {
                    // 全角変換
                    commitTextSKK(hankaku2zenkaku(mKanjiKey.toString()))
                    handleKanaKey(context)
                    return
                }

                skkPrefs.kanaKey -> {
                    changeState(SKKPreeditState)
                    return
                }
            }

            // スペースで変換するかそのままComposingに積む
            when (charCode) {
                ' '.code -> if (mKanjiKey.isNotEmpty()) startConversion()

                else -> {
                    mKanjiKey.insertAtCursor(Char(keyCode).toString())
                    setComposingTextSKK()
                    complete(mKanjiKey.toString())
                }
            }
        }
    }

    override fun afterBackspace(context: SKKEngine) {
        context.apply {
            setComposingTextSKK()
            complete(mKanjiKey.toString())
        }
    }

    override fun handleCancel(context: SKKEngine, reconvert: Boolean): Boolean {
        context.mKanjiKey.clear() // 確定させない
        context.changeState(SKKHiraganaState)
        return true
    }

    override fun changeToFlick(context: SKKEngine): Boolean {
        context.changeState(context.kanaState, true)
        return true
    }
}

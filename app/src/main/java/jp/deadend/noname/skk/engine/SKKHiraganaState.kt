package jp.deadend.noname.skk.engine

import android.os.Build
import jp.deadend.noname.skk.R
import jp.deadend.noname.skk.isAlphabet
import jp.deadend.noname.skk.skkPrefs

// ひらがなモード
object SKKHiraganaState : SKKState {
    override val isTransient = false
    override val icon = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        R.drawable.ic_hiragana
    } else {
        R.drawable.immodeic_hiragana
    }

    override fun handleKanaKey(context: SKKEngine) {
        if (skkPrefs.toggleKanaKey) context.changeState(SKKASCIIState)
    }

    internal fun processKana(
            context: SKKEngine,
            pcode: Int, commitFunc:
            (SKKEngine, String) -> Unit
    ) {
        val composing = context.mComposing

        // シフトキーの状態をチェック
        val isUpper = Character.isUpperCase(pcode)
        // 大文字なら，ローマ字変換のために小文字に戻す
        val pcodeLower = if (isUpper) Character.toLowerCase(pcode) else pcode

        if (composing.length == 1) {
            val hchr = RomajiConverter.checkSpecialConsonants(composing[0], pcodeLower)
            if (hchr != null) commitFunc(context, hchr)
        }
        if (isUpper) {
            // 漢字変換候補入力の開始。KanjiModeへの移行
            context.changeState(SKKKanjiState)
            if (composing.isNotEmpty()) {
                SKKKanjiState.processKey(context, composing[0].code)
                composing.setLength(0)
            }
            SKKKanjiState.processKey(context, pcodeLower)
        } else {
            composing.append(pcodeLower.toChar())
            // 全角にする記号ならば全角，そうでなければローマ字変換
            val hchr = context.getZenkakuSeparator(composing.toString())
                    ?: RomajiConverter.convert(composing.toString())

            if (hchr != null) { // 確定できるものがあれば確定
                commitFunc(context, hchr)
            } else { // アルファベットならComposingに積む
                if (isAlphabet(pcodeLower)) {
                    context.setComposingTextSKK(composing, 1)
                } else {
                    commitFunc(context, composing.toString())
                    composing.setLength(0)
                }
            }
        }
    }

    override fun processKey(context: SKKEngine, pcode: Int) {
//        if (!context.isHiragana) { // 内部状態とキーボードの齟齬を解消
//            dlog("SKKHiraganaState.processKey(${pcode.toChar()}) while Engine.state=Katakana")
//            context.changeState(SKKKatakanaState)
//            return SKKKatakanaState.processKey(context, pcode)
//        }
        if (context.changeInputMode(pcode)) return
        processKana(context, pcode) { engine, hchr ->
            engine.commitTextSKK(hchr, 1)
            engine.mComposing.setLength(0)
        }
    }

    override fun afterBackspace(context: SKKEngine) {
        context.setComposingTextSKK(context.mComposing, 1)
    }

    override fun handleCancel(context: SKKEngine): Boolean {
        if (context.isRegistering) {
            context.cancelRegister()
            return true
        } else {
            return context.reConversion()
        }
    }

    override fun changeToFlick(context: SKKEngine): Boolean {
        return false
    }
}

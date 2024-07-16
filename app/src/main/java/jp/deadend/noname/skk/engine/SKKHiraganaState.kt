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
        // シフトキーの状態をチェック
        val isUpper = Character.isUpperCase(pcode)
        // 大文字なら，ローマ字変換のために小文字に戻す
        val pcodeLower = if (isUpper) Character.toLowerCase(pcode) else pcode

        context.apply {
            val canRetry = mComposing.isNotEmpty() // 無限ループ防止
            if (mComposing.length == 1) {
                val hchr = RomajiConverter.checkSpecialConsonants(mComposing[0], pcodeLower)
                if (hchr != null) commitFunc(context, hchr)
            }
            if (isUpper) {
                // 漢字変換候補入力の開始。KanjiModeへの移行
                // すでに composing がある場合はそこから KanjiMode だったものとする (mA = Ma)
                changeState(SKKKanjiState, false)
                SKKKanjiState.processKey(context, pcodeLower)
            } else {
                mComposing.append(pcodeLower.toChar())
                // 全角にする記号ならば全角，そうでなければローマ字変換
                val hchr = getZenkakuSeparator(mComposing.toString())
                    ?: RomajiConverter.convert(mComposing.toString())

                if (hchr != null) { // 確定できるものがあれば確定
                    commitFunc(context, hchr)
                } else { // アルファベットならComposingに積む
                    if (isAlphabet(pcodeLower)) {
                        if (!RomajiConverter.isIntermediateRomaji(mComposing.toString())) {
                            mComposing.setLength(0) // これまでの composing は typo とみなす
                            if (canRetry) return processKana(context, pcode, commitFunc) // 「ca」などもあるので再突入
                        }
                        setComposingTextSKK(mComposing, 1)
                    } else {
                        commitFunc(context, pcodeLower.toChar().toString())
                    }
                }
            }
        }
    }

    override fun processKey(context: SKKEngine, pcode: Int) {
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
        context.apply {
            if (isRegistering) {
                cancelRegister()
                return true
            } else {
                return reConversion()
            }
        }
    }

    override fun changeToFlick(context: SKKEngine): Boolean {
        return false
    }
}

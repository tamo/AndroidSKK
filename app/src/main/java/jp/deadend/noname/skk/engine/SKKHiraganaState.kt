package jp.deadend.noname.skk.engine

import jp.deadend.noname.skk.R
import jp.deadend.noname.skk.isAlphabet
import jp.deadend.noname.skk.skkPrefs

// ひらがなモード
object SKKHiraganaState : SKKState {
    override val isTransient = false
    override val icon =
        R.drawable.ic_hiragana

    override fun handleKanaKey(context: SKKEngine) {
        if (skkPrefs.toggleKanaKey) context.changeState(SKKASCIIState, true)
    }

    internal fun processKana(
        context: SKKEngine,
        keyCode: Int, commitFunc:
            (SKKEngine, String) -> Unit
    ) {
        // シフトキーの状態をチェック
        val isUpper = Character.isUpperCase(keyCode)
        // 大文字なら，ローマ字変換のために小文字に戻す
        val codeLower = if (isUpper) Character.toLowerCase(keyCode) else keyCode

        context.apply {
            val canRetry = mComposing.isNotEmpty() // 無限ループ防止
            if (mComposing.length == 1) {
                val hiraganaChar = RomajiConverter.checkSpecialConsonants(mComposing[0], codeLower)
                if (hiraganaChar != null) commitFunc(context, hiraganaChar)
            }
            if (isUpper) {
                // 漢字変換候補入力の開始。KanjiModeへの移行
                // すでに composing がある場合はそこから KanjiMode だったものとする (mA = Ma)
                changeState(SKKKanjiState)
                SKKKanjiState.processKey(context, codeLower)
            } else {
                mComposing.append(codeLower.toChar())
                // 全角にする記号ならば全角，そうでなければローマ字変換
                val hiraganaChar = getZenkakuSeparator(mComposing.toString())
                    ?: RomajiConverter.convert(mComposing.toString())

                if (hiraganaChar.isNotEmpty()) { // 確定できるものがあれば確定
                    commitFunc(context, hiraganaChar)
                } else { // アルファベットならComposingに積む
                    if (isAlphabet(codeLower)) {
                        if (!RomajiConverter.isIntermediateRomaji(mComposing.toString())) {
                            mComposing.setLength(0) // これまでの composing は typo とみなす
                            if (canRetry) return processKana(
                                context,
                                keyCode,
                                commitFunc
                            ) // 「ca」などもあるので再突入
                        }
                        setComposingTextSKK(mComposing)
                    } else {
                        commitFunc(context, codeLower.toChar().toString())
                    }
                }
            }
        }
    }

    override fun processKey(context: SKKEngine, keyCode: Int) {
        if (context.changeInputMode(keyCode)) return
        processKana(context, keyCode) { engine, hiraganaChar ->
            engine.commitTextSKK(hiraganaChar)
            engine.mComposing.setLength(0)
        }
    }

    override fun afterBackspace(context: SKKEngine) {
        context.setComposingTextSKK(context.mComposing)
    }

    override fun handleCancel(context: SKKEngine): Boolean {
        context.apply {
            if (isRegistering) {
                cancelRegister()
                return true
            } else {
                return reConvert()
            }
        }
    }

    override fun changeToFlick(context: SKKEngine): Boolean {
        return false
    }
}

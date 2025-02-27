package jp.deadend.noname.skk.engine

import jp.deadend.noname.skk.createTrimmedBuilder
import jp.deadend.noname.skk.skkPrefs

// 送り仮名入力中(▽モード，*つき)
object SKKOkuriganaState : SKKState {
    override val isTransient = true
    override val icon = 0

    override fun handleKanaKey(context: SKKEngine) {
        context.apply {
            if (skkPrefs.toggleKanaKey) {
                changeState(SKKASCIIState, true)
            } else {
                mComposing.setLength(0)
                changeState(SKKHiraganaState)
            }
        }
    }

    override fun processKey(context: SKKEngine, keyCode: Int) {
        // シフトキーの状態をチェック
        val isUpper = Character.isUpperCase(keyCode)
        // 大文字なら，ローマ字変換のために小文字に戻す
        val codeLower = if (isUpper) Character.toLowerCase(keyCode) else keyCode

        context.apply {
            // l, L, q, / による暗黙の確定
            if (changeInputMode(keyCode)) return

            if (mComposing.length == 1 && mOkurigana.isEmpty()) {
                // 「ん」か「っ」を処理したらここで終わり
                val hiraganaChar = RomajiConverter.checkSpecialConsonants(mComposing[0], codeLower)
                if (hiraganaChar != null) {
                    mOkurigana = hiraganaChar
                    setComposingTextSKK(
                        createTrimmedBuilder(mKanjiKey)
                            .append('*').append(hiraganaChar).append(codeLower.toChar())
                    )
                    mComposing.setLength(0)
                    mComposing.append(codeLower.toChar())
                    return
                }
            }
            // 送りがなが確定すれば変換，そうでなければComposingに積む
            mComposing.append(codeLower.toChar())
            val hiraganaChar = RomajiConverter.convert(mComposing.toString())
            if (mOkurigana.isNotEmpty()) { //「ん」か「っ」がある場合
                if (hiraganaChar.isNotEmpty()) {
                    mComposing.setLength(0)
                    mOkurigana += hiraganaChar
                    conversionStart(mKanjiKey)
                } else {
                    setComposingTextSKK(
                        createTrimmedBuilder(mKanjiKey)
                            .append('*').append(mOkurigana).append(mComposing)
                    )
                }
            } else {
                if (hiraganaChar.isNotEmpty()) {
                    mComposing.setLength(0)
                    mOkurigana = hiraganaChar
                    conversionStart(mKanjiKey)
                } else {
                    if (!RomajiConverter.isIntermediateRomaji(mComposing.toString())) {
                        mComposing.setLength(0) // これまでの composing は typo とみなしてやり直す
                        mKanjiKey.deleteCharAt(mKanjiKey.lastIndex)
                        changeState(SKKKanjiState)
                        SKKKanjiState.processKey(context, Character.toUpperCase(keyCode))
                        return
                    }
                    setComposingTextSKK(
                        createTrimmedBuilder(mKanjiKey).append('*').append(mComposing)
                    )
                }
            }
        }
    }

    override fun afterBackspace(context: SKKEngine) {
        context.apply {
            mComposing.setLength(0) // 元から空のはず
            mKanjiKey.deleteCharAt(mKanjiKey.lastIndex)
            if (mOkurigana.isNotEmpty()) mKanjiKey.append(mOkurigana) // 「っ」とか
            mOkurigana = ""
            setComposingTextSKK(mKanjiKey)
            changeState(SKKKanjiState)
        }
    }

    override fun handleCancel(context: SKKEngine): Boolean {
        context.apply {
            if (skkPrefs.preferFlick) mComposing.setLength(0) // Flickでアルファベットが残っても困る
            mOkurigana = ""
            if (mKanjiKey.isNotEmpty()) mKanjiKey.deleteCharAt(mKanjiKey.lastIndex) // composing と同じ子音アルファベットのはず
            changeState(SKKKanjiState)
            setComposingTextSKK("${mKanjiKey}${mComposing}")
        }

        return true
    }

    override fun changeToFlick(context: SKKEngine): Boolean {
        return false
    }
}

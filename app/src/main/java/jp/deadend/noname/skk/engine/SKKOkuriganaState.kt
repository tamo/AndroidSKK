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
                // 確定
                changeState(SKKHiraganaState)
                mComposing.setLength(0)
                commitTextSKK(mKanjiKey, 1)
            }
        }
    }

    override fun processKey(context: SKKEngine, pcode: Int) {
        // シフトキーの状態をチェック
        val isUpper = Character.isUpperCase(pcode)
        // 大文字なら，ローマ字変換のために小文字に戻す
        val pcodeLower = if (isUpper) Character.toLowerCase(pcode) else pcode

        context.apply {
            // l, L, / による暗黙の確定
            if (changeInputMode(pcode)) {
                mComposing.setLength(0)
                commitTextSKK(mKanjiKey, 1)
            }
            if (mComposing.length == 1 || mOkurigana == null) {
                // 「ん」か「っ」を処理したらここで終わり
                val hchr = RomajiConverter.checkSpecialConsonants(mComposing[0], pcodeLower)
                if (hchr != null) {
                    mOkurigana = hchr
                    setComposingTextSKK(
                        createTrimmedBuilder(mKanjiKey)
                            .append('*').append(hchr).append(pcodeLower.toChar()), 1
                    )
                    mComposing.setLength(0)
                    mComposing.append(pcodeLower.toChar())
                    return
                }
            }
            // 送りがなが確定すれば変換，そうでなければComposingに積む
            mComposing.append(pcodeLower.toChar())
            val hchr = RomajiConverter.convert(mComposing.toString())
            if (mOkurigana != null) { //「ん」か「っ」がある場合
                if (hchr != null) {
                    mComposing.setLength(0)
                    mOkurigana += hchr
                    conversionStart(mKanjiKey)
                } else {
                    setComposingTextSKK(
                        createTrimmedBuilder(mKanjiKey)
                            .append('*').append(mOkurigana).append(mComposing), 1
                    )
                }
            } else {
                if (hchr != null) {
                    mComposing.setLength(0)
                    mOkurigana = hchr
                    conversionStart(mKanjiKey)
                } else {
                    if (!RomajiConverter.isIntermediateRomaji(mComposing.toString())) {
                        mComposing.setLength(0) // これまでの composing は typo とみなしてやり直す
                        mKanjiKey.deleteCharAt(mKanjiKey.lastIndex)
                        changeState(SKKKanjiState)
                        SKKKanjiState.processKey(context, Character.toUpperCase(pcode))
                        return
                    }
                    setComposingTextSKK(
                        createTrimmedBuilder(mKanjiKey).append('*').append(mComposing), 1
                    )
                }
            }
        }
    }

    override fun afterBackspace(context: SKKEngine) {
        context.apply {
            mComposing.setLength(0) // 元から空のはず
            mKanjiKey.deleteCharAt(mKanjiKey.lastIndex)
            if (!mOkurigana.isNullOrEmpty()) mKanjiKey.append(mOkurigana) // 「っ」とか
            mOkurigana = null
            setComposingTextSKK(mKanjiKey, 1)
            changeState(SKKKanjiState)
        }
    }

    override fun handleCancel(context: SKKEngine): Boolean {
        context.apply {
            if (skkPrefs.preferFlick) mComposing.setLength(0) // Flickでアルファベットが残っても困る
            mOkurigana = null
            mKanjiKey.deleteCharAt(mKanjiKey.lastIndex) // composing と同じ子音アルファベットのはず
            changeState(SKKKanjiState)
            setComposingTextSKK("${mKanjiKey}${mComposing}", 1)
        }

        return true
    }

    override fun changeToFlick(context: SKKEngine): Boolean {
        return false
    }
}

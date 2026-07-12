package jp.deadend.noname.skk.engine

import jp.deadend.noname.skk.SKKLog
import jp.deadend.noname.skk.encodeKey
import jp.deadend.noname.skk.lowerCode
import jp.deadend.noname.skk.skkPrefs

// 送り仮名入力中(▽モード，*つき)
object SKKOkuriganaState : SKKState {
    override val isTransient = true
    override val isPreedit = true
    override val prefix = "▽"

    override fun onEnter(context: SKKEngine, oldState: SKKState) {
        super.onEnter(context, oldState)
        context.mCandidates.reset()
    }

    override fun handleKanaKey(context: SKKEngine) {
        context.apply {
            if (skkPrefs.toggleKanaKey) {
                changeState(SKKASCIIState, true)
            } else {
                mRoman.clear()
                changeState(SKKHiraganaState)
            }
        }
    }

    override fun handleEnter(context: SKKEngine): Boolean {
        context.changeState(context.kanaState)
        return true
    }

    override fun processKey(context: SKKEngine, keyCode: Int) {
        val codeLower = keyCode.lowerCode

        context.apply {
            // l, L, q, / による暗黙の確定
            if (changeInputMode(keyCode)) return

            if (mRoman.length == 1 && mOkurigana.isEmpty()) {
                // 「ん」か「っ」を処理したらここで終わり
                RomajiConverter.checkSpecialConsonants(mRoman[0], codeLower)?.let { hira ->
                    mOkurigana = hira
                    mRoman.clear()
                    mRoman.append(Char(codeLower))
                    setComposingOkuri(mOkurigana + mRoman)
                    return
                }
            }

            // 送りがなが確定すれば変換，そうでなければComposingに積む
            mRoman.append(Char(codeLower))
            val hiraganaChar = RomajiConverter.convert(mRoman.toString())
            when {
                mOkurigana.isNotEmpty() -> { //「ん」か「っ」がある場合
                    if (hiraganaChar.isNotEmpty()) {
                        mRoman.clear()
                        mOkurigana += hiraganaChar
                        if (mOkurigana.first() == 'っ') mKanjiKey.roman =
                            RomajiConverter.getConsonantForVoiced(hiraganaChar.last().toString())
                        startConversion()
                    } else
                        setComposingOkuri(mOkurigana + mRoman)
                }

                hiraganaChar == "っ" -> { // IXtu など
                    mKanjiKey.roman = 't' // あとから getConsonantForVoiced で修正される
                    mOkurigana = "っ"
                    mRoman.clear()
                    setComposingOkuri(mOkurigana)
                }

                hiraganaChar.isNotEmpty() -> {
                    mRoman.clear()
                    mOkurigana = hiraganaChar
                    startConversion()
                }

                !RomajiConverter.isIntermediateRomaji(mRoman.toString()) -> {
                    mRoman.clear() // これまでの composing は typo とみなしてやり直す
                    mKanjiKey.roman = null
                    changeState(SKKPreeditState)
                    SKKPreeditState.processKey(
                        context, encodeKey(Character.toUpperCase(keyCode))
                    )
                }

                else -> setComposingOkuri(mRoman)
            }
        }
    }

    private fun SKKEngine.setComposingOkuri(composing: CharSequence) {
        setComposingTextSKK("${mKanjiKey.entry}*$composing")
    }

    override fun handleBackspace(context: SKKEngine): Boolean {
        val previousLength = context.mRoman.length
        context.handleDelete()
        val isComposingDeleted = context.mRoman.length < previousLength
        updateAfterDelete(context, isComposingDeleted)
        return true
    }

    override fun handleForwardDel(context: SKKEngine): Boolean {
        context.handleDelete(true)
        return true
    }

    private fun updateAfterDelete(context: SKKEngine, isComposingDeleted: Boolean = false) {
        context.apply {
            SKKLog.d("SKKOkuriganaState.updateAfterDelete($isComposingDeleted): $mKanjiKey + $mOkurigana + $mRoman")
            if (isComposingDeleted && (mRoman.isNotEmpty() || mOkurigana.isNotEmpty())) {
                setComposingOkuri(mOkurigana + mRoman)
                return
            }

            // ▽ (*なし) に戻る
            mKanjiKey.roman = null // 送りありのアルファベット部分
            if (mOkurigana.isNotEmpty()) SKKLog.e("mOkurigana must be empty")
            setComposingTextSKK()
            changeState(SKKPreeditState)
        }
    }

    override fun handleCancel(context: SKKEngine, reconvert: Boolean): Boolean {
        context.apply {
            if (keyboardType != "qwerty") mRoman.clear() // Flickでアルファベットが残っても困る
            mKanjiKey.roman = null
            mKanjiKey.append(mOkurigana)
            mOkurigana = ""
            changeState(SKKPreeditState)
            setComposingTextSKK()
        }

        return true
    }

    override fun changeToFlick(context: SKKEngine): Boolean {
        return false
    }
}

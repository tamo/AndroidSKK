package jp.deadend.noname.skk.engine

import jp.deadend.noname.skk.SKKLog
import jp.deadend.noname.skk.createTrimmedBuilder
import jp.deadend.noname.skk.encodeKey
import jp.deadend.noname.skk.isAlphabet
import jp.deadend.noname.skk.lowerCode
import jp.deadend.noname.skk.skkPrefs

// 送り仮名入力中(▽モード，*つき)
object SKKOkuriganaState : SKKState {
    override val isTransient = true
    override val isPreedit = true
    override val canComplete = true
    override val prefix = "▽"

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

    override fun handleEnter(context: SKKEngine): Boolean {
        context.changeState(context.kanaState)
        return true
    }

    override fun processKey(context: SKKEngine, keyCode: Int) {
        val codeLower = keyCode.lowerCode

        context.apply {
            // l, L, q, / による暗黙の確定
            if (changeInputMode(keyCode)) return

            if (mComposing.length == 1 && mOkurigana.isEmpty()) {
                // 「ん」か「っ」を処理したらここで終わり
                RomajiConverter.checkSpecialConsonants(mComposing[0], codeLower)?.let { hira ->
                    mOkurigana = hira
                    mComposing.setLength(0)
                    mComposing.append(Char(codeLower))
                    setComposingOkuri(mOkurigana + mComposing)
                    return
                }
            }

            // 送りがなが確定すれば変換，そうでなければComposingに積む
            mComposing.append(Char(codeLower))
            val hiraganaChar = RomajiConverter.convert(mComposing.toString())
            when {
                mOkurigana.isNotEmpty() -> { //「ん」か「っ」がある場合
                    if (hiraganaChar.isNotEmpty()) {
                        mComposing.setLength(0)
                        mOkurigana += hiraganaChar
                        startConversion()
                    } else
                        setComposingOkuri(mOkurigana + mComposing)
                }

                hiraganaChar == "っ" -> { // IXtu など
                    mKanjiKey.deleteLast().append('t') // 「ゃゅょ」で始まる場合はないはず
                    mOkurigana = "っ"
                    mComposing.setLength(0)
                    setComposingOkuri(mOkurigana)
                }

                hiraganaChar.isNotEmpty() -> {
                    mComposing.setLength(0)
                    mOkurigana = hiraganaChar
                    startConversion()
                }

                !RomajiConverter.isIntermediateRomaji(mComposing.toString()) -> {
                    mComposing.setLength(0) // これまでの composing は typo とみなしてやり直す
                    mKanjiKey.deleteAtCursor()
                    changeState(SKKPreeditState)
                    SKKPreeditState.processKey(
                        context, encodeKey(Character.toUpperCase(keyCode))
                    )
                }

                else -> setComposingOkuri(mComposing)
            }
        }
    }

    private fun SKKEngine.setComposingOkuri(composing: CharSequence) {
        val tmpText = createTrimmedBuilder(mKanjiKey.entry)
            .append('*').append(composing)
        setComposingTextSKK(tmpText)
    }

    override fun afterBackspace(context: SKKEngine, isComposingDeleted: Boolean) {
        context.apply {
            SKKLog.d("SKKOkuriganaState.afterBackspace($isComposingDeleted): $mKanjiKey + $mOkurigana + $mComposing")
            if (isComposingDeleted && (mComposing.isNotEmpty() || mOkurigana.isNotEmpty())) {
                setComposingOkuri(mOkurigana + mComposing)
                return
            }

            // ▽ (*なし) に戻る
            mKanjiKey.deleteLast() // 送りありのアルファベット部分
            if (mOkurigana.isNotEmpty()) SKKLog.e("mOkurigana must be empty")
            setComposingTextSKK()
            changeState(SKKPreeditState)
        }
    }

    override fun handleCancel(context: SKKEngine, reconvert: Boolean): Boolean {
        context.apply {
            if (skkPrefs.softKeyboardType != "qwerty") mComposing.setLength(0) // Flickでアルファベットが残っても困る
            if (isAlphabet(mKanjiKey.last().code)) mKanjiKey.deleteLast() // composing と同じ子音アルファベットのはず
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

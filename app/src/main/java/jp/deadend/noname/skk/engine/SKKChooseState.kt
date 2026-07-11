package jp.deadend.noname.skk.engine

import android.view.KeyEvent
import jp.deadend.noname.skk.isAlphabet
import jp.deadend.noname.skk.isShifted
import jp.deadend.noname.skk.lowerCode
import jp.deadend.noname.skk.skkPrefs

// 変換候補選択中(▼モード)
object SKKChooseState : SKKConfirmingState() {
    override val isTransient = true
    override val isPreedit = true
    override val hasCandidates = true
    override val prefix = "▼"

    override fun handleKanaKey(context: SKKEngine) {
        if (!declineUnregister(context)) context.apply {
            pickCurrentCandidate() // kanaState になる (カタカナかもしれない)
            if (skkPrefs.toggleKanaKey) {
                changeState(SKKASCIIState, true)
            } else {
                changeState(SKKHiraganaState)
            }
        }
    }

    override fun handleEnter(context: SKKEngine): Boolean {
        if (!declineUnregister(context)) context.pickCurrentCandidate()
        return true
    }

    override fun handleBackspace(context: SKKEngine): Boolean {
        if (!declineUnregister(context)) {
            if (context.mRoman.isNotEmpty()) {
                context.mRoman.deleteCharAt(context.mRoman.lastIndex)
                context.mCandidates.updateComposingText()
            } else {
                context.pickCurrentCandidate(backspace = true)
            }
        }
        return true
    }

    override fun handleForwardDel(context: SKKEngine): Boolean {
        if (!declineUnregister(context)) {
            context.pickCurrentCandidate()
            return false
        }
        return true
    }

    override fun processKey(context: SKKEngine, keyCode: Int) {
        if (super.beforeProcessKey(context, keyCode)) return
        val lower = keyCode.lowerCode
        val charCode = if (keyCode.isShifted) Character.toUpperCase(lower) else lower
        context.apply {
            when (keyCode) {
                skkPrefs.asciiKey, skkPrefs.zenkakuKey, skkPrefs.abbrevKey -> {
                    // 暗黙の確定
                    pickCurrentCandidate()
                    changeInputMode(keyCode)
                    return
                }
            }
            when (charCode) {
                ' '.code -> moveCandidateCursor(true)
                'x'.code -> moveCandidateCursor(false)
                'X'.code -> pickCurrentCandidate(unregister = true)
                '>'.code -> {
                    // 接尾辞入力
                    pickCurrentCandidate()
                    changeState(SKKPreeditState) // Abbrevキーボードのことは無視
                    mKanjiKey.roman = '>'
                    setComposingTextSKK()
                }

                ':'.code -> changeState(SKKNarrowingState) // Abbrevキーボードのことは無視

                else -> {
                    // 暗黙の確定
                    pickCurrentCandidate()
                    kanaState.processKey(context, keyCode)
                }
            }
        }
    }


    override fun handleDpad(context: SKKEngine, keyCode: Int): Boolean {
        if (declineUnregister(context)) return true
        context.apply {
            when (keyCode) {
                KeyEvent.KEYCODE_MOVE_HOME -> mCandidates.setCandidateCursor(0)
                KeyEvent.KEYCODE_DPAD_LEFT -> moveCandidateCursor(false)
                KeyEvent.KEYCODE_DPAD_RIGHT -> moveCandidateCursor(true)
                KeyEvent.KEYCODE_MOVE_END -> mCandidates.mList?.run {
                    mCandidates.setCandidateCursor(lastIndex)
                }

                else -> return false
            }
        }
        return true
    }

    override fun handleCancel(context: SKKEngine, reconvert: Boolean): Boolean {
        if (!declineUnregister(context)) context.apply {
            when {
                mCandidates.isSpecial -> {
                    val wasSymbol = mCandidates.mQuery != "emoji"
                    changeState(kanaState) // ASCII から special は来ないはず
                    if (wasSymbol) symbolCandidates() // 戻る先は Preedit
                    return true
                }

                mKanjiKey.isEmpty() -> { // どういうとき？
                    changeState(kanaState)
                    return true
                }

                isAlphabet(mKanjiKey[0].code) -> // 厳密な判定ではないが
                    changeState(SKKAbbrevState)

                else -> { // 漢字変換中
                    mRoman.clear() // 最初から空のはずだけど念のため
                    changeState(SKKPreeditState) // Abbrev の可能性はない
                    mKanjiKey.roman?.let { roman ->
                        mKanjiKey.roman = null
                        if (roman != '>' && keyboardType == "qwerty") { // Flickではアルファベットが残ると困る
                            mRoman.append(roman)
                        }
                    }
                    mKanjiKey.append(mOkurigana)
                    mOkurigana = ""
                }
            }
            setComposingTextSKK()
            complete(mKanjiKey.toString())
        }
        return true
    }

    override fun transformLastChar(context: SKKEngine, type: String): Boolean = true.also {
        if (!super.transformLastChar(context, type)) context.run {
            if (mRoman.isNotEmpty()) return@run
            if (mOkurigana.isEmpty()) return@run
            val okurigana = mOkurigana // ▼合い (okurigana = い)
            val newOkurigana = RomajiConverter.transform(okurigana, type).second

            if (type == SKKEngine.TRANS_SHIFT) {
                handleCancel(this, false)
                return@run
            }

            // 例外: 送りがなが「っ」になる場合は，どのみち必ず「た行」の音なのでmKanjiKeyはそのまま
            // 「ゃゅょ」で送りがなが始まる場合はないはず
            if (type != SKKEngine.TRANS_SMALL) {
                mKanjiKey.roman = RomajiConverter.getConsonantForVoiced(newOkurigana)
            }
            mOkurigana = newOkurigana
            startConversion() //変換やりなおし
        }
    }
}

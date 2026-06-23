package jp.deadend.noname.skk.engine

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
    override val icon = 0

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
                    mKanjiKey.append('>')
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

    override fun afterBackspace(context: SKKEngine) {
        if (!declineUnregister(context)) context.pickCurrentCandidate(backspace = true)
    }

    override fun handleCancel(context: SKKEngine, reconvert: Boolean): Boolean {
        if (!declineUnregister(context)) context.apply {
            when {
                mCandidates.isSpecial -> {
                    val wasSymbol = mCandidates.mQuery != "emoji"
                    mKanjiKey.clear() // 暗黙の確定を回避
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
                    mComposing.setLength(0) // 最初から空のはずだけど念のため
                    mOkurigana = "" // これは入っている可能性がある
                    changeState(SKKPreeditState) // Abbrev の可能性はない
                    val maybeComposing = mKanjiKey.lastOrNull() ?: Char(0)
                    if (isAlphabet(maybeComposing.code)) {
                        mKanjiKey.deleteLast() // 送りがなのアルファベットを削除
                        if (!skkPrefs.preferFlick) { // Flickではアルファベットが残ると困る
                            mComposing.append(maybeComposing)
                        }
                    }
                }
            }
            setComposingTextSKK()
            complete(mKanjiKey.toString())
        }
        return true
    }
}

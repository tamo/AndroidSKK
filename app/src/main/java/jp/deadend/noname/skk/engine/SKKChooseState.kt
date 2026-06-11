package jp.deadend.noname.skk.engine

import jp.deadend.noname.skk.isAlphabet
import jp.deadend.noname.skk.isShifted
import jp.deadend.noname.skk.lowerCode
import jp.deadend.noname.skk.skkPrefs

// 変換候補選択中(▼モード)
object SKKChooseState : SKKConfirmingState {
    override val isTransient = true
    override val hasCandidates = true
    override val prefix = "▼"
    override val icon = 0

    override var pendingLambda: (() -> Unit)? = null
    override var oldComposingText = ""

    override fun handleKanaKey(context: SKKEngine) {
        super.handleKanaKey(context)
        context.apply {
            pickCurrentCandidate() // kanaState になる (カタカナかもしれない)
            if (skkPrefs.toggleKanaKey) {
                changeState(SKKASCIIState, true)
            } else {
                changeState(SKKHiraganaState)
            }
        }
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
        super.afterBackspace(context)
        context.apply {
            pickCurrentCandidate(backspace = true)
        }
    }

    override fun handleCancel(context: SKKEngine, reconvert: Boolean): Boolean {
        super.handleCancel(context, reconvert)
        context.apply {
            if (mKanjiKey.isEmpty()) { // どういうとき？
                changeState(kanaState)
            } else {
                if (isAlphabet(mKanjiKey[0].code)) { // Abbrev モード
                    changeState(SKKAbbrevState)
                } else { // 漢字変換中
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
                setComposingTextSKK()
                complete(mKanjiKey.toString())
            }
        }
        return true
    }

    override fun changeToFlick(context: SKKEngine): Boolean {
        return false
    }
}

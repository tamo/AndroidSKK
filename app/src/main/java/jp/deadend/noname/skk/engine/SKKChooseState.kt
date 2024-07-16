package jp.deadend.noname.skk.engine

import jp.deadend.noname.skk.isAlphabet
import jp.deadend.noname.skk.skkPrefs

// 変換候補選択中(▼モード)
object SKKChooseState : SKKState {
    override val isTransient = true
    override val icon = 0

    override fun handleKanaKey(context: SKKEngine) {
        context.apply {
            pickCurrentCandidate() // kanaState になる (カタカナかもしれない)
            if (skkPrefs.toggleKanaKey) {
                changeState(SKKASCIIState)
            } else {
                changeState(SKKHiraganaState, change = false, recover = true)
            }
        }
    }

    override fun processKey(context: SKKEngine, pcode: Int) {
        context.apply {
            when (pcode) {
                ' '.code -> chooseAdjacentCandidate(true)
                'x'.code -> chooseAdjacentCandidate(false)
                '>'.code -> {
                    // 接尾辞入力
                    pickCurrentCandidate()
                    changeState(SKKKanjiState, false) // Abbrevキーボードのことは無視
                    mKanjiKey.append('>')
                    setComposingTextSKK(mKanjiKey, 1)
                }

                'l'.code, 'L'.code, '/'.code -> {
                    // 暗黙の確定
                    pickCurrentCandidate()
                    changeInputMode(pcode)
                }

                ':'.code -> changeState(SKKNarrowingState, false) // Abbrevキーボードのことは無視
                else -> {
                    // 暗黙の確定
                    pickCurrentCandidate()
                    kanaState.processKey(context, pcode)
                }
            }
        }
    }

    override fun afterBackspace(context: SKKEngine) {
        context.apply {
            pickCurrentCandidate(backspace = true)
        }
    }

    override fun handleCancel(context: SKKEngine): Boolean {
        context.apply {
            if (mKanjiKey.isEmpty()) { // どういうとき？
                changeState(kanaState, change = false, recover = true)
            } else {
                if (isAlphabet(mKanjiKey[0].code)) { // Abbrevモード
                    changeState(SKKAbbrevState)
                } else { // 漢字変換中
                    mComposing.setLength(0) // 最初から空のはずだけど念のため
                    mOkurigana = null // これは入っている可能性がある
                    changeState(SKKKanjiState, change = false) // Abbrevの可能性はない
                    val maybeComposing = mKanjiKey.lastOrNull() ?: 0.toChar()
                    if (isAlphabet(maybeComposing.code)) {
                        mKanjiKey.deleteCharAt(mKanjiKey.lastIndex) // 送りがなのアルファベットを削除
                        if (!skkPrefs.toggleKanaKey) {
                            mComposing.append(maybeComposing)
                        }
                    }
                }
                setComposingTextSKK("${mKanjiKey}${mComposing}", 1)
                updateSuggestions(mKanjiKey.toString())
            }
        }
        return true
    }

    override fun changeToFlick(context: SKKEngine): Boolean {
        return false
    }
}

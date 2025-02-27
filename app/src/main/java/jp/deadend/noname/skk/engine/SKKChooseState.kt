package jp.deadend.noname.skk.engine

import jp.deadend.noname.skk.isAlphabet
import jp.deadend.noname.skk.skkPrefs

// 変換候補選択中(▼モード)
object SKKChooseState : SKKConfirmingState {
    override val isTransient = true
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
        context.apply {
            when (keyCode) {
                ' '.code -> chooseAdjacentCandidate(true)
                'x'.code -> chooseAdjacentCandidate(false)
                'X'.code -> pickCurrentCandidate(unregister = true)
                '>'.code -> {
                    // 接尾辞入力
                    pickCurrentCandidate()
                    changeState(SKKKanjiState) // Abbrevキーボードのことは無視
                    mKanjiKey.append('>')
                    setComposingTextSKK(mKanjiKey)
                }

                'l'.code, 'L'.code, '/'.code -> {
                    // 暗黙の確定
                    pickCurrentCandidate()
                    changeInputMode(keyCode)
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

    override fun handleCancel(context: SKKEngine): Boolean {
        super.handleCancel(context)
        context.apply {
            if (mKanjiKey.isEmpty()) { // どういうとき？
                changeState(kanaState)
            } else {
                if (isAlphabet(mKanjiKey[0].code)) { // Abbrevモード
                    changeState(SKKAbbrevState)
                } else { // 漢字変換中
                    mComposing.setLength(0) // 最初から空のはずだけど念のため
                    mOkurigana = "" // これは入っている可能性がある
                    changeState(SKKKanjiState) // Abbrevの可能性はない
                    val maybeComposing = mKanjiKey.lastOrNull() ?: 0.toChar()
                    if (isAlphabet(maybeComposing.code)) {
                        mKanjiKey.deleteCharAt(mKanjiKey.lastIndex) // 送りがなのアルファベットを削除
                        if (!skkPrefs.preferFlick) { // Flickではアルファベットが残ると困る
                            mComposing.append(maybeComposing)
                        }
                    }
                }
                setComposingTextSKK("${mKanjiKey}${mComposing}")
                updateSuggestions(mKanjiKey.toString())
            }
        }
        return true
    }

    override fun changeToFlick(context: SKKEngine): Boolean {
        return false
    }
}

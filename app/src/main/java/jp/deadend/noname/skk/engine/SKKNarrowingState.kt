package jp.deadend.noname.skk.engine

import jp.deadend.noname.skk.decodeKey
import jp.deadend.noname.skk.skkPrefs

object SKKNarrowingState : SKKConfirmingState {
    override var isSequential = false
    override val isTransient = true
    override val icon = 0
    override val hasCandidates = true
    override val prefix = "▼"
    override var pendingLambda: (() -> Unit)? = null
    override var oldComposingText = ""

    internal val mHint = StringBuilder()
    internal var mOriginalCandidates: List<String>? = null
    internal var mSpaceUsed = false // xを前候補にするためのフラグ
    internal var isASCII = false

    override fun handleKanaKey(context: SKKEngine) {
        super.handleKanaKey(context)
        SKKChooseState.handleKanaKey(context)
    }

    override fun processKey(context: SKKEngine, keyCode: Int) {
        if (super.beforeProcessKey(context, keyCode)) return
        val (lower, shifted) = decodeKey(keyCode)
        val charCode = if (shifted) Character.toUpperCase(lower) else lower
        context.apply {
            when (keyCode) {
                skkPrefs.zenkakuKey, skkPrefs.abbrevKey -> {
                    // 暗黙の確定
                    pickCurrentCandidate()
                    changeInputMode(keyCode)
                    return
                }

                skkPrefs.asciiKey -> if (!isASCII) {
                    isASCII = true
                    changeSoftKeyboard(SKKASCIIState)
                    return
                }

                skkPrefs.kanaKey -> if (isASCII) {
                    // おそらくここは通らず changeToFlick 経由で日本語キーボードに戻る
                    isASCII = false
                    changeSoftKeyboard(SKKHiraganaState)
                    return
                }
            }

            if (isASCII) {
                // このモードでは ' ' も 'X' も利かない
                mHint.append(Char(charCode))
                mComposing.setLength(0)
                narrowCandidates(mHint.toString())
                return
            }

            when (charCode) {
                ' '.code -> {
                    mSpaceUsed = true
                    chooseAdjacentCandidate(true)
                }

                'X'.code -> pickCurrentCandidate(unregister = true)

                else -> if (mSpaceUsed && keyCode == 'x'.code) {
                    chooseAdjacentCandidate(false)
                } else {
                    SKKHiraganaState
                        .processKana(
                            this,
                            Character.toLowerCase(keyCode),
                            false
                        ) { _, hiraganaChar ->
                            mHint.append(hiraganaChar)
                            mComposing.setLength(0)
                            narrowCandidates(mHint.toString())
                        }
                }
            }
        }
    }

    override fun afterBackspace(context: SKKEngine) {
        super.afterBackspace(context)
        context.apply {
            if (mHint.isEmpty()) {
                conversionStart(context.mKanjiKey)
            } else {
                if (mComposing.isNotEmpty()) {
                    mComposing.deleteCharAt(mComposing.lastIndex)
                    setCurrentCandidateToComposing()
                } else {
                    mHint.deleteCharAt(mHint.lastIndex)
                    narrowCandidates(mHint.toString())
                }
            }
        }
    }

    override fun handleCancel(context: SKKEngine): Boolean {
        super.handleCancel(context)
        context.conversionStart(context.mKanjiKey)
        return true
    }

    override fun changeToFlick(context: SKKEngine): Boolean {
        isASCII = false
        return false
    }
}
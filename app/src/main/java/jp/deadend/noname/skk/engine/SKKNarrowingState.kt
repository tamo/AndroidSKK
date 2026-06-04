package jp.deadend.noname.skk.engine

import jp.deadend.noname.skk.decodeKey
import jp.deadend.noname.skk.skkPrefs

object SKKNarrowingState : SKKConfirmingState {
    override val isTransient = true
    override val hasCandidates = true
    override val prefix = "▼"
    override val icon = 0
    override var isSequential = false

    override var pendingLambda: (() -> Unit)? = null
    override var oldComposingText = ""

    internal val mHint = StringBuilder()
    internal var mOriginalCandidates: List<String>? = null
    internal var mSpaceUsed = false // xを前候補にするためのフラグ
    internal var isASCII = false // isJapanese とは違って可変な内部フラグ

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
                mCandidates.narrow(mHint.toString())
                return
            }

            when (charCode) {
                ' '.code -> {
                    mSpaceUsed = true
                    moveCandidateCursor(true)
                }

                'X'.code -> pickCurrentCandidate(unregister = true)

                else -> if (mSpaceUsed && keyCode == 'x'.code) {
                    moveCandidateCursor(false)
                } else {
                    SKKHiraganaState
                        .processKana(
                            this,
                            Character.toLowerCase(keyCode),
                            false
                        ) { _, hiraganaChar ->
                            mHint.append(hiraganaChar)
                            mComposing.setLength(0)
                            mCandidates.narrow(mHint.toString())
                        }
                }
            }
        }
    }

    override fun afterBackspace(context: SKKEngine) {
        super.afterBackspace(context)
        context.apply {
            if (mHint.isEmpty()) {
                startConversion()
            } else {
                if (mComposing.isNotEmpty()) {
                    mComposing.deleteCharAt(mComposing.lastIndex)
                    mCandidates.updateComposingText()
                } else {
                    mHint.deleteCharAt(mHint.lastIndex)
                    mCandidates.narrow(mHint.toString())
                }
            }
        }
    }

    override fun handleCancel(context: SKKEngine, reconvert: Boolean): Boolean {
        super.handleCancel(context, reconvert)
        context.startConversion()
        return true
    }

    override fun changeToFlick(context: SKKEngine): Boolean {
        isASCII = false
        return false
    }
}
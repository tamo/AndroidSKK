package jp.deadend.noname.skk.engine

import jp.deadend.noname.skk.isShifted
import jp.deadend.noname.skk.lowerCode
import jp.deadend.noname.skk.skkPrefs

object SKKNarrowingState : SKKConfirmingState() {
    override val isTransient = true
    override val hasCandidates = true
    override val prefix = "▼"

    internal val mHint = SKKEngine.KanjiKey()
    internal var mOriginalCandidates: List<String>? = null
    private var mSpaceUsed = false // xを前候補にするためのフラグ
    private var isASCII = false // isJapanese とは違って可変な内部フラグ

    override fun onEnter(context: SKKEngine, oldState: SKKState) {
        super.onEnter(context, oldState)
        mHint.clear()
        mOriginalCandidates = null
        mSpaceUsed = false
        isASCII = false
        context.mCandidates.updateComposingText()
    }

    override fun setComposingText(context: SKKEngine, ct: StringBuilder) {
        val hintWithCursor = if (mHint.cursor == mHint.length) "${mHint}${context.mComposing}"
        else "${mHint.take(mHint.cursor)}[${context.mComposing}]${mHint.drop(mHint.cursor)}"
        ct.append(" hint: $hintWithCursor")
    }

    override fun handleKanaKey(context: SKKEngine) {
        if (!declineUnregister(context)) SKKChooseState.handleKanaKey(context)
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
                    // ソフトキーからはここを通らず changeToFlick 経由で日本語キーボードに戻る
                    isASCII = false
                    changeSoftKeyboard(SKKHiraganaState)
                    return
                }
            }

            if (isASCII) {
                // このモードでは ' ' も 'X' も利かない
                mHint.insertAtCursor(Char(charCode).toString())
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
                    SKKHiraganaState.processKana(
                        this, Character.toLowerCase(keyCode)
                    ) { _, hiraganaChar ->
                        mHint.insertAtCursor(hiraganaChar)
                        mComposing.setLength(0)
                        mCandidates.narrow(mHint.toString())
                    }
                    mCandidates.updateComposingText()
                }
            }
        }
    }

    override fun afterBackspace(context: SKKEngine, isComposingDeleted: Boolean) {
        if (!declineUnregister(context)) context.apply {
            when {
                mHint.isEmpty() && !mCandidates.isSpecial -> // 絵文字や記号は変換ではない
                    startConversion()

                mComposing.isNotEmpty() -> {
                    mComposing.deleteCharAt(mComposing.lastIndex)
                    mCandidates.updateComposingText()
                }

                else -> {
                    mHint.deleteAtCursor()
                    mCandidates.narrow(mHint.toString())
                }
            }
        }
    }

    override fun handleCancel(context: SKKEngine, reconvert: Boolean): Boolean {
        if (!declineUnregister(context)) context.apply {
            when {
                !mCandidates.isSpecial -> startConversion()

                !mHint.isEmpty() -> { // 絵文字や記号は変換ではないので
                    mHint.clear() // 初回はヒントを消すだけにして
                    mCandidates.narrow(mHint.toString())
                }

                else -> // 消えたら絵文字自体を中止
                    changeState(kanaState) // ASCII からは来ていない前提
            }
        }
        return true
    }

    override fun changeToFlick(context: SKKEngine): Boolean {
        if (declineUnregister(context)) return true
        isASCII = false
        return false
    }

    override fun transformLastChar(context: SKKEngine, type: String): Boolean = true.also {
        if (!super.transformLastChar(context, type)) context.run {
            if (type == SKKEngine.TRANS_SHIFT) return@run

            mHint.insertAtCursor(mComposing.toString())
            mComposing.clear()

            if (mHint.isEmpty()) return@run

            val newLastChar = RomajiConverter.transform(
                mHint.substring(mHint.cursor - 1, mHint.cursor), type
            ).second
            // この transform に 2 文字が渡ることはない

            mHint.deleteAtCursor()
            mHint.insertAtCursor(newLastChar)
            mCandidates.narrow(mHint.toString())
        }
    }
}

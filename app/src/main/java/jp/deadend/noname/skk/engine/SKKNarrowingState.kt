package jp.deadend.noname.skk.engine

import jp.deadend.noname.skk.isShifted
import jp.deadend.noname.skk.lowerCode
import jp.deadend.noname.skk.skkPrefs

object SKKNarrowingState : SKKConfirmingState() {
    override val isTransient = true
    override val hasCandidates = true
    override val prefix = "▼"

    private val mHint = SKKEngine.KanjiKey()
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

    override fun onExit(context: SKKEngine, newState: SKKState) {
        if (isASCII) context.changeSoftKeyboard(context.kanaState)
        super.onExit(context, newState)
    }

    override val setComposingText = fun(context: SKKEngine, ct: StringBuilder) {
        val hintWithCursor = if (mHint.cursor == mHint.length) "${mHint}${context.mRoman}"
        else "${mHint.take(mHint.cursor)}[${context.mRoman}]${mHint.drop(mHint.cursor)}"
        ct.append(" hint: $hintWithCursor")
    }

    override fun handleKanaKey(context: SKKEngine) {
        if (!declineUnregister(context)) SKKChooseState.handleKanaKey(context)
    }

    override fun handleEnter(context: SKKEngine): Boolean {
        if (!declineUnregister(context)) context.pickCurrentCandidate()
        return true
    }

    override fun handleBackspace(context: SKKEngine): Boolean {
        if (!declineUnregister(context)) context.apply {
            when {
                mHint.isEmpty() && !mCandidates.isSpecial -> // 絵文字や記号は変換ではない
                    startConversion()

                mRoman.isNotEmpty() -> {
                    mRoman.deleteCharAt(mRoman.lastIndex)
                    mCandidates.updateComposingText()
                }

                else -> {
                    mHint.deleteAtCursor()
                    mCandidates.narrow(mHint.toString())
                }
            }
        }
        return true
    }

    override fun handleForwardDel(context: SKKEngine): Boolean {
        if (!declineUnregister(context)) context.apply {
            mHint.deleteAfterCursor(all = false)
            mCandidates.narrow(mHint.toString())
        }
        return true
    }

    override fun processKey(context: SKKEngine, keyCode: Int) {
        if (super.beforeProcessKey(context, keyCode)) return
        val lower = keyCode.lowerCode
        val charCode = if (keyCode.isShifted) Character.toUpperCase(lower) else lower
        context.apply {
            when (keyCode) {
                skkPrefs.zenkakuKey -> {
                    // 暗黙の確定
                    pickCurrentCandidate()
                    changeInputMode(keyCode)
                    return
                }

                skkPrefs.asciiKey, skkPrefs.abbrevKey -> if (!isASCII) {
                    isASCII = true
                    if (skkPrefs.softKeyboardType != "flick") changeSoftKeyboard(SKKASCIIState)
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
                mRoman.clear()
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
                        mRoman.clear()
                        mCandidates.narrow(mHint.toString())
                    }
                    mCandidates.updateComposingText()
                }
            }
        }
    }


    override fun handleDpad(context: SKKEngine, keyCode: Int): Boolean =
        if (declineUnregister(context)) true
        else context.handleDpadTransient(keyCode, mHint)

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

            mHint.insertAtCursor(mRoman.toString())
            mRoman.clear()

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

package jp.deadend.noname.skk.engine

import android.text.SpannableString
import com.android.volley.toolbox.JsonArrayRequest
import com.android.volley.toolbox.Volley
import jp.deadend.noname.skk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.util.ArrayDeque

class SKKEngine(
        private val mService: SKKService,
        private var mDicts: List<SKKDictionaryInterface>,
        private val mUserDict: SKKUserDictionary,
        private val mASCIIDict: SKKUserDictionary,
        private val mEmojiDict: SKKUserDictionary
) {
    var state: SKKState = SKKHiraganaState
        private set
    internal var kanaState: SKKState = SKKHiraganaState
    internal var oldState: SKKState = SKKHiraganaState
    private var cameFromFlick: Boolean = skkPrefs.preferFlick

    // 候補のリスト．KanjiStateとAbbrevStateでは補完リスト，ChooseStateでは変換候補リストになる
    private var mCandidatesList: List<String>? = null
    private var mCompletionList: List<String>? = null
    private var mCurrentCandidateIndex = 0
    private var mCandidateKanjiKey = ""
    private var mUpdateSuggestionsJob: Job = Job()
    private var mSuggestionsSuspended: Boolean = false

    // ひらがなや英単語などの入力途中
    internal val mComposing = StringBuilder()
    // 漢字変換のキー 送りありの場合最後がアルファベット 変換中は不変
    internal val mKanjiKey = StringBuilder()
    // 送りがな 「っ」や「ん」が含まれる場合だけ二文字になる
    internal var mOkurigana: String? = null
    // 実際に表示されているもの
    internal val mComposingText = StringBuilder()

    // 全角で入力する記号リスト
    private val mZenkakuSeparatorMap = mutableMapOf(
        "-" to "ー", "!" to "！", "?" to "？", "~" to "〜",
        "[" to "「", "]" to "」", "(" to "（", ")" to "）"
    )
    val isRegistering: Boolean
        get() = !mRegistrationStack.isEmpty()

    // 単語登録のための情報
    private val mRegistrationStack = ArrayDeque<RegistrationInfo>()
    private class RegistrationInfo(val key: String, val okurigana: String?) {
        val entry = StringBuilder()
    }

    // 再変換のための情報
    private class ConversionInfo(
            val candidate: String,
            val list: List<String>,
            val index: Int,
            val kanjiKey: String,
            val okurigana: String?
    )
    private var mLastConversion: ConversionInfo? = null

    internal var isPersonalizedLearning = true

    init { setZenkakuPunctuationMarks("en") }

    fun reopenDictionaries(dics: List<SKKDictionaryInterface>) {
        for (dic in mDicts) when {
            dic === mUserDict -> dic.reopen()
            dic === mEmojiDict -> dic.reopen()
            else -> dic.close()
        }
        mDicts = dics
        mASCIIDict.reopen()
    }

    fun setZenkakuPunctuationMarks(type: String) {
        when (type) {
            "en" -> {
                mZenkakuSeparatorMap["."] = "．"
                mZenkakuSeparatorMap[","] = "，"
            }
            "jp" -> {
                mZenkakuSeparatorMap["."] = "。"
                mZenkakuSeparatorMap[","] = "、"
            }
            "jp_en" -> {
                mZenkakuSeparatorMap["."] = "。"
                mZenkakuSeparatorMap[","] = "，"
            }
            else -> {
                mZenkakuSeparatorMap["."] = "．"
                mZenkakuSeparatorMap[","] = "，"
            }
        }
    }

    fun commitUserDictChanges() {
        mUserDict.commitChanges()
        mASCIIDict.commitChanges()
        //mEmojiDict.commitChanges() // 現状ではEmojiに書き込むことがない
    }

    fun lockUserDict() {
        mUserDict.mIsLocked = true
        mASCIIDict.mIsLocked = true
        //mEmojiDict.mIsLocked = true
    }

    fun unlockUserDict() {
        mUserDict.mIsLocked = false
        mASCIIDict.mIsLocked = false
        //mEmojiDict.mIsLocked = false
    }

    fun processKey(pcode: Int) {
        state.processKey(this, pcode)
    }

    fun handleKanaKey() {
        state.handleKanaKey(this)
    }

    fun handleBackKey(): Boolean {
        if (!mRegistrationStack.isEmpty()) {
            mRegistrationStack.removeFirst()
        }

        if (state.isTransient) {
            reset() // 確定なし
            changeState(kanaState)
            return true
        } else if (!mRegistrationStack.isEmpty()) {
            reset()
            return true
        }

        return false
    }

    fun handleEnter(): Boolean {
        when (state) {
            SKKChooseState, SKKNarrowingState -> pickCandidate(mCurrentCandidateIndex)
            SKKKanjiState, SKKOkuriganaState, SKKAbbrevState -> changeState(kanaState)
            SKKEmojiState -> pickSuggestion(mCurrentCandidateIndex)
            else -> {
                if (mComposing.isEmpty()) {
                    if (!mRegistrationStack.isEmpty()) {
                        registerWord()
                    } else {
                        return false
                    }
                } else {
                    commitTextSKK(
                        if (state === SKKHanKanaState) zenkaku2hankaku(mComposing.toString())!!
                        else mComposing
                    )
                    mComposing.setLength(0)
                }
            }
        }

        return true
    }

    fun handleBackspace(): Boolean {
        when (state) {
            SKKNarrowingState, SKKChooseState, SKKEmojiState -> {
                state.afterBackspace(this)
                return true
            }
        }

        val clen = mComposing.length
        val klen = mKanjiKey.length

        // 変換中のものがない場合
        if (clen == 0 && klen == 0) {
            if (state == SKKKanjiState || state == SKKAbbrevState) {
                changeState(kanaState)
                return true
            }
            val firstEntry = mRegistrationStack.peekFirst()?.entry
            if (firstEntry != null) {
                if (firstEntry.isNotEmpty()) {
                    firstEntry.deleteCharAt(firstEntry.length - 1)
                    setComposingTextSKK("")
                }
            } else {
                return state.isTransient
            }
        }

        if (clen > 0) {
            mComposing.deleteCharAt(clen - 1)
        } else if (klen > 0) {
            mKanjiKey.deleteCharAt(klen - 1)
        }
        state.afterBackspace(this)

        return true
    }

    fun handleCancel(): Boolean {
        return state.handleCancel(this)
    }

    /**
     * commitTextのラッパー 登録作業中なら登録内容に追加し，表示を更新
     * @param text
     */
    fun commitTextSKK(text: CharSequence) {
        val ic = mService.currentInputConnection ?: return

        val firstEntry = mRegistrationStack.peekFirst()?.entry
        if (firstEntry != null) {
            firstEntry.append(text)
            setComposingTextSKK("")
        } else {
            ic.commitText(text, 1)
            mComposingText.setLength(0)
        }
    }

    fun resetOnStartInput() {
        mComposing.setLength(0)
        mKanjiKey.setLength(0)
        mOkurigana = null
        mCandidatesList = null
        mCandidateKanjiKey = ""
        when {
            state.isTransient -> {
                changeState(kanaState)
                mService.showStatusIcon(state.icon)
            }
            state === SKKASCIIState -> mService.hideStatusIcon()
            else -> mService.showStatusIcon(state.icon)
        }

        // onStartInput()では，WebViewのときsetComposingText("", 1)すると落ちるようなのでやめる
    }

    fun chooseAdjacentSuggestion(isForward: Boolean) {
        val candList = mCandidatesList ?: return

        if (isForward) {
            mCurrentCandidateIndex++
        } else {
            mCurrentCandidateIndex--
        }

        // 範囲外になったら反対側へ
        if (mCurrentCandidateIndex > candList.size - 1) {
            mCurrentCandidateIndex = 0
        } else if (mCurrentCandidateIndex < 0) {
            mCurrentCandidateIndex = candList.size - 1
        }

        mService.requestChooseCandidate(mCurrentCandidateIndex)
        mKanjiKey.setLength(0)
        mKanjiKey.append(candList[mCurrentCandidateIndex])
        setComposingTextSKK(mKanjiKey)
    }

    fun chooseAdjacentCandidate(isForward: Boolean) {
        val candList = mCandidatesList ?: return

        if (isForward) {
            mCurrentCandidateIndex++
        } else {
            mCurrentCandidateIndex--
        }

        // 最初の候補より戻ると変換に戻る 最後の候補より進むと登録
        if (mCurrentCandidateIndex > candList.size - 1) {
            if (state === SKKChooseState) {
                registerStart(mKanjiKey.toString())
                return
            } else if (state === SKKNarrowingState) {
                mCurrentCandidateIndex = 0
            }
        } else if (mCurrentCandidateIndex < 0) {
            if (state === SKKChooseState) {
                if (mComposing.isEmpty()) {
                    // KANJIモードに戻る
                    if (mOkurigana != null) {
                        mOkurigana = null
                        mKanjiKey.deleteCharAt(mKanjiKey.length - 1)
                    }
                    changeState(SKKKanjiState)
                    setComposingTextSKK(mKanjiKey)
                    updateSuggestions(mKanjiKey.toString())
                } else {
                    mKanjiKey.setLength(0)
                    changeState(SKKAbbrevState)
                    setComposingTextSKK(mComposing)
                    updateSuggestions(mComposing.toString())
                }

                mCurrentCandidateIndex = 0
                mUpdateSuggestionsJob.invokeOnCompletion {
                    setCurrentCandidateToComposing()
                }
                return
            } else if (state === SKKNarrowingState) {
                mCurrentCandidateIndex = candList.size - 1
            }
        }

        mService.requestChooseCandidate(mCurrentCandidateIndex)
        setCurrentCandidateToComposing()
    }

    fun pickCandidateViewManually(index: Int) {
        when (state) {
            SKKChooseState, SKKNarrowingState -> pickCandidate(index)
            SKKAbbrevState, SKKKanjiState, SKKASCIIState, SKKEmojiState -> pickSuggestion(index)
            else -> throw RuntimeException("cannot pick candidate in $state")
        }
    }

    fun prepareToMushroom(clip: String): String {
        val str = when (state) {
            SKKKanjiState, SKKAbbrevState -> mKanjiKey.toString()
            SKKASCIIState -> getPrefixASCII().ifEmpty { clip }
            else -> clip
        }

        if (state.isTransient) {
            changeState(kanaState)
        } else {
            reset()
            mRegistrationStack.clear()
        }

        return str
    }

    // 小文字大文字変換，濁音，半濁音に使う
    fun changeLastChar(type: String) {
        when {
            state === SKKKanjiState && mComposing.isEmpty() && mKanjiKey.isNotEmpty() -> {
                val s = mKanjiKey.toString() // ▽あい
                val idx = s.length - 1
                if (idx < 1 && type == LAST_CONVERSION_SHIFT) { return } // ▽あ
                val newLastChar = RomajiConverter.convertLastChar(s.substring(idx), type).second
                // この convertLastChar に 2 文字が渡ることはない

                mKanjiKey.deleteCharAt(idx)
                if (type == LAST_CONVERSION_SHIFT) {
                    mKanjiKey.append(RomajiConverter.getConsonantForVoiced(newLastChar))
                    mOkurigana = newLastChar
                    conversionStart(mKanjiKey) // ▼合い
                } else {
                    mKanjiKey.append(newLastChar)
                    setComposingTextSKK(mKanjiKey)
                    updateSuggestions(mKanjiKey.toString())
                }
            }
            state === SKKNarrowingState && mComposing.isEmpty() && SKKNarrowingState.mHint.isNotEmpty() -> {
                val hint = SKKNarrowingState.mHint // ▼藹 hint: わ
                val idx = hint.length - 1
                if (type == LAST_CONVERSION_SHIFT) { return }
                val newLastChar = RomajiConverter.convertLastChar(hint.substring(idx), type).second
                // この convertLastChar にも 2 文字が渡ることはない

                hint.deleteCharAt(idx)
                hint.append(newLastChar)
                narrowCandidates(hint.toString())
            }
            state === SKKChooseState -> {
                val okuri = mOkurigana ?: return // ▼合い (okuri = い)
                val newOkuri = RomajiConverter.convertLastChar(okuri, type).second

                if (type == LAST_CONVERSION_SHIFT) {
                    handleCancel() // ▽あ (mOkurigana = null)
                    mKanjiKey.append(newOkuri) // ▽あい
                    setComposingTextSKK(mKanjiKey)
                    updateSuggestions(mKanjiKey.toString())
                    return
                }
                // 例外: 送りがなが「っ」になる場合は，どのみち必ずt段の音なのでmKanjiKeyはそのまま
                // 「ゃゅょ」で送りがなが始まる場合はないはず
                if (type != LAST_CONVERSION_SMALL) {
                    mKanjiKey.deleteCharAt(mKanjiKey.length - 1)
                    mKanjiKey.append(RomajiConverter.getConsonantForVoiced(newOkuri))
                }
                mOkurigana = newOkuri
                conversionStart(mKanjiKey) //変換やりなおし
            }
            mComposing.isEmpty() && mKanjiKey.isEmpty() -> {
                val ic = mService.currentInputConnection ?: return
                val cs = ic.getTextBeforeCursor(2, 0) ?: return
                // 0〜2 文字を 0〜3 文字にするので注意!
                val newLast2Chars = RomajiConverter.convertLastChar(cs.toString(), type)
                dlog("changeLastChar: 2chars=$newLast2Chars")
                if (newLast2Chars.first.isEmpty() && newLast2Chars.second.isEmpty()) return

                val deleteTwo = (cs.length == 2 && newLast2Chars.first.isEmpty())
                val newLastChar = newLast2Chars.second

                val firstEntry = mRegistrationStack.peekFirst()?.entry
                if (firstEntry != null) {
                    if (firstEntry.isEmpty()) { return }
                    firstEntry.deleteCharAt(firstEntry.length - 1)
                    if (deleteTwo) {
                        firstEntry.deleteCharAt(firstEntry.length - 2)
                    }
                    if (type == LAST_CONVERSION_SHIFT) {
                        mKanjiKey.append(katakana2hiragana(newLastChar))
                        changeState(SKKKanjiState)
                        setComposingTextSKK(mKanjiKey)
                        updateSuggestions(mKanjiKey.toString())
                    } else {
                        firstEntry.append(newLastChar)
                        setComposingTextSKK("")
                    }
                } else {
                    // 同じ部分を消してまた書くのは避ける
                    when {
                        cs.isEmpty() -> if (type != LAST_CONVERSION_SHIFT) return
                        !deleteTwo -> {
                            if (cs.last() == newLastChar.last() && type != LAST_CONVERSION_SHIFT) return
                            else ic.deleteSurroundingText(1, 0)
                        }
                        else -> ic.deleteSurroundingText(2, 0)
                    }
                    if (type == LAST_CONVERSION_SHIFT) {
                        mKanjiKey.append(katakana2hiragana(newLastChar))
                        changeState(SKKKanjiState) // Abbrevから来ることはないはず
                        setComposingTextSKK(mKanjiKey)
                        updateSuggestions(mKanjiKey.toString())
                    } else {
                        ic.commitText(
                            if (state === SKKHanKanaState) zenkaku2hankaku(newLastChar)
                            else newLastChar,
                            1
                        )
                        mComposingText.setLength(0)
                    }
                }
            }
        }
    }

    internal fun getZenkakuSeparator(key: String) = mZenkakuSeparatorMap[key]

    /**
     * setComposingTextのラッパー 変換モードマーク等を追加する
     * @param text
     */
    internal fun setComposingTextSKK(text: CharSequence) {
        val ic = mService.currentInputConnection ?: return
        val ct = mComposingText
        ct.setLength(0)

        if (!mRegistrationStack.isEmpty()) {
            val depth = mRegistrationStack.size
            repeat(depth) { ct.append("[") }
            ct.append("登録")
            repeat(depth) { ct.append("]") }

            val regInfo = mRegistrationStack.peekFirst()
            if (regInfo != null) {
                val key = if (kanaState === SKKHiraganaState) regInfo.key else hirakana2katakana(regInfo.key)!!
                val okuri = if (kanaState === SKKHiraganaState) regInfo.okurigana else hirakana2katakana(regInfo.okurigana)
                // 半角カナ変換はあとで
                if (okuri == null) {
                    ct.append(key)
                } else {
                    ct.append(key.substring(0, key.length - 1))
                    ct.append("*")
                    ct.append(okuri)
                }
                ct.append("：")
                ct.append(regInfo.entry) // ここはカナ変換しない
            }
        }

        if (skkPrefs.prefixMark) {
            if (!isPersonalizedLearning) {
                ct.append("㊙")
            }
            if (state === SKKAbbrevState || state === SKKKanjiState || state === SKKOkuriganaState) {
                ct.append("▽")
            } else if (state === SKKChooseState || state === SKKNarrowingState) {
                ct.append("▼")
            }
        } else if (text.isEmpty()) {
            ct.append(" ")
        }
        ct.append(if (mService.isHiragana) text else hirakana2katakana(text.toString(), reversed = true))
        if (state === SKKNarrowingState) {
            ct.append(" hint: ", SKKNarrowingState.mHint, mComposing)
        }

        ic.setComposingText(ct, 1)
    }

    /***
     * 変換スタート
     * 送りありの場合，事前に送りがなをmOkuriganaにセットしておく
     * @param key 辞書のキー 送りありの場合最後はアルファベット
     */
    internal fun conversionStart(key: StringBuilder) {
        if (key.isEmpty()) {
            changeState(kanaState) // ASCIIには戻れない…
            return
        }
        val str = key.toString()

        changeState(SKKChooseState)

        val list = findCandidates(str) ?: Regex("\\d+(\\.\\d+)?").find(str)?.let { num ->
            findCandidates(str.replaceRange(num.range, "#"))
        }
        if (list == null) {
            registerStart(str)
            return
        }

        mCandidatesList = list
        mCurrentCandidateIndex = 0
        mCandidateKanjiKey = str
        mService.setCandidates(list, str, skkPrefs.candidatesNormalLines)
        setCurrentCandidateToComposing()
    }

    internal fun narrowCandidates(hint: String) {
        val candidates = SKKNarrowingState.mOriginalCandidates ?: mCandidatesList ?: return
        if (SKKNarrowingState.mOriginalCandidates == null) {
            SKKNarrowingState.mOriginalCandidates = mCandidatesList
        }

        val narrowed = if (hint.isEmpty()) candidates else {
            val hintKanjis = findCandidates(hint)
                ?.joinToString(
                    separator = "",
                    transform = { processConcatAndMore(removeAnnotation(it), "") }
                ) ?: ""

            // mCandidateKanjiKey("かんじ") -> candidates("漢字", "幹事", "監事", "感じ")
            // hint("おとこ") -> hintKanjis("男漢♂")
            candidates.filter { str -> /* str("漢字; 注釈も含む") */
                str.any { ch -> hintKanjis.contains(ch) } /* hintKanjisは注釈を除いてある */
                        || str.contains(hint) /* ひらがなかカタカナでヒントを含むstrもOK */
                        || hirakana2katakana(hint)?.let { str.contains(it) } ?: false
            }.let { nList ->
                if (mCandidateKanjiKey == "emoji")
                    nList.map { removeAnnotation(it) }
                else nList
            }
        }

        if (narrowed.isNotEmpty()) {
            mCandidatesList = narrowed
            mCurrentCandidateIndex = 0
            mService.setCandidates(
                narrowed,
                mCandidateKanjiKey,
                if (mCandidateKanjiKey == "emoji")
                    skkPrefs.candidatesEmojiLines
                else skkPrefs.candidatesNormalLines
            )
        }
        setCurrentCandidateToComposing()
    }

    internal fun reConversion(): Boolean {
        val lastConv = mLastConversion ?: return false

        val s = lastConv.candidate
        dlog("last conversion: $s")
        if (mService.prepareReConversion(s)) {
            mUserDict.rollBack()

            changeState(SKKChooseState)

            mComposing.setLength(0)
            mKanjiKey.setLength(0)
            mKanjiKey.append(lastConv.kanjiKey)
            mOkurigana = lastConv.okurigana
            mCandidatesList = lastConv.list
            mCurrentCandidateIndex = lastConv.index
            mCandidateKanjiKey = lastConv.kanjiKey
            mService.setCandidates(mCandidatesList, mCandidateKanjiKey, skkPrefs.candidatesNormalLines)
            mService.requestChooseCandidate(mCurrentCandidateIndex)
            setCurrentCandidateToComposing()

            return true
        }

        return false
    }

    internal fun updateSuggestions(str: String) {
        if (mSuggestionsSuspended) return
        mUpdateSuggestionsJob.cancel()
        mUpdateSuggestionsJob.invokeOnCompletion {
            mUpdateSuggestionsJob = MainScope().launch(Dispatchers.Default) {
                val set = mutableSetOf<Pair<String, String>>()

                if (str.isNotEmpty())
                    for (dic in mDicts) { addFound(this@launch, set, str, dic) }
                Regex("\\d+(\\.\\d+)?").find(str)?.let {
                    val replacedStr = str.replaceRange(it.range, "#")
                    for (dic in mDicts) { addFound(this@launch, set, replacedStr, dic) }
                }

                set.distinctBy { it.second }
                    .let { uniqueSet ->
                        mCompletionList = uniqueSet.map { it.first }
                        mCandidatesList = uniqueSet.map { it.second }
                    }

                mCandidateKanjiKey = str
                mCurrentCandidateIndex = 0
                withContext(Dispatchers.Main) {
                    if (str == "emoji")
                        mService.setCandidates(
                            mCandidatesList?.map { removeAnnotation(it) },
                            str,
                            skkPrefs.candidatesEmojiLines
                        )
                    else
                        mService.setCandidates(mCandidatesList, str, skkPrefs.candidatesNormalLines)
                }
            }
            mUpdateSuggestionsJob.start()
        }
    }

    internal fun suspendSuggestions() {
        mUpdateSuggestionsJob.cancel()
        mSuggestionsSuspended = true
    }
    internal fun resumeSuggestions() {
        mSuggestionsSuspended = false
    }

    private suspend fun addFound(
        scope: CoroutineScope,
        target: MutableSet<Pair<String, String>>,
        key: String,
        dic: SKKDictionaryInterface
    ) {
        val dictionary = when {
            dic === mUserDict -> {
                if (key == "emoji" || state === SKKASCIIState) mASCIIDict else mUserDict
            }
            dic === mEmojiDict -> {
                if (key == "emoji" || key == "えもじ") {
                    mEmojiDict.findKeys(scope, "").forEach { suggestion ->
                        target.add(key to suggestion.second)
                    }
                }
                return
            }
            else -> dic
        }
        if (mService.isHiragana) {
            target.addAll(dictionary.findKeys(scope, key))
        } else for (suggestion in dictionary.findKeys(scope, key)) {
            val hiraSuggestion = suggestion.first
            target.add(hiraSuggestion to hirakana2katakana(hiraSuggestion, reversed = true)!!)
        }
    }

    internal fun updateSuggestionsASCII() {
        if (state !== SKKASCIIState) return
        MainScope().launch(Dispatchers.Default) {
            delay(50) // バックスペースなどの処理が間に合っていないことがあるので
            updateSuggestions(getPrefixASCII())
        }
    }

    private fun getPrefixASCII(): String {
        val ic = mService.currentInputConnection ?: return ""
        val tbc = ic.getTextBeforeCursor(ASCII_WORD_MAX_LENGTH, 0) ?: return ""
        return tbc.split(Regex("[^a-zA-Z0-9]")).last()
    }

    private fun deletePrefixASCII(expected: String): Boolean {
        val ic = mService.currentInputConnection ?: return false
        val tbc = ic.getTextBeforeCursor(expected.length, 0) ?: return false
        val wbc = tbc.split(Regex("[^a-zA-Z0-9]")).last()
        return if (expected.startsWith(wbc)) {
            ic.deleteSurroundingText(wbc.length, 0)
            true
        } else false
    }

    private fun deleteSuffixASCII() {
        val ic = mService.currentInputConnection ?: return
        val tac = ic.getTextAfterCursor(ASCII_WORD_MAX_LENGTH, 0) ?: return
        val wac = tac.split(Regex("[^a-zA-Z0-9]")).first()
        ic.deleteSurroundingText(0, wac.length)
    }

    private fun registerStart(str: String) {
        mRegistrationStack.addFirst(RegistrationInfo(str, mOkurigana))
        reset()
        changeState(kanaState)
        //setComposingTextSKK("", 1);
    }

    private fun registerWord() {
        val regInfo = mRegistrationStack.removeFirst()
        if (regInfo.entry.isNotEmpty()) {
            var regEntryStr = regInfo.entry.toString()
            if (regEntryStr.indexOf(';') != -1 || regEntryStr.indexOf('/') != -1) {
                // セミコロンとスラッシュのエスケープ
                regEntryStr = (
                        "(concat \""
                        + regEntryStr.replace(";", "\\073").replace("/", "\\057")
                        + "\")"
                        )
            }
            // if (isPersonalizedLearning) のチェックはこの場合しないでおく
            mUserDict.addEntry(regInfo.key, regEntryStr, regInfo.okurigana)
            mUserDict.commitChanges()
            // entry は生で登録するが okurigana はひらがな
            val okuri = regInfo.okurigana ?: ""
            commitTextSKK(regInfo.entry.toString() + when (kanaState) {
                SKKHiraganaState -> okuri
                SKKKatakanaState -> hirakana2katakana(okuri)
                SKKHanKanaState -> zenkaku2hankaku(hirakana2katakana(okuri))
                else -> throw RuntimeException("kanaState: $kanaState")
            })
        }
        reset()
        if (!mRegistrationStack.isEmpty()) setComposingTextSKK("")
    }

    internal fun cancelRegister() {
        val regInfo = mRegistrationStack.removeFirst()
        mKanjiKey.setLength(0)
        mKanjiKey.append(regInfo.key)
        mComposing.setLength(0)
        val maybeComposing = mKanjiKey.lastOrNull() ?: 0.toChar()
        if (isAlphabet(maybeComposing.code)) {
            mKanjiKey.deleteCharAt(mKanjiKey.lastIndex)
            if (!skkPrefs.preferFlick) { // Flickでアルファベットがあっても困る
                mComposing.append(maybeComposing)
            }
        }
        changeState(SKKKanjiState)
        setComposingTextSKK("${mKanjiKey}${mComposing}")
        updateSuggestions(mKanjiKey.toString())
    }

    internal fun googleTransliterate() {
        if (mRegistrationStack.isEmpty()) {
            if (mKanjiKey.isEmpty()) return
        } else {
            // candidate から選択しただけで登録されるので
            val regInfo = mRegistrationStack.removeFirst() ?: return
            mComposing.setLength(0)
            mKanjiKey.setLength(0)
            mKanjiKey.append(regInfo.key)
            mOkurigana = regInfo.okurigana
        }
        dlog("googleTransliterate mKanjiKey=${mKanjiKey} mOkurigana=${mOkurigana}")

        changeState(SKKKanjiState)
        val query = if (mKanjiKey.isNotEmpty() && isAlphabet(mKanjiKey.last().code)) {
            val trimmedKanjiKey = mKanjiKey.substring(0, mKanjiKey.lastIndex)
            setComposingTextSKK("${trimmedKanjiKey}*${mOkurigana ?: ""}")
            "${trimmedKanjiKey}${mOkurigana ?: ""}"
        } else {
            setComposingTextSKK(mKanjiKey.toString())
            mKanjiKey.toString() // たぶん送り仮名は存在しないはず
        }
        val volleyQueue = Volley.newRequestQueue(mService)
        volleyQueue.add(
            JsonArrayRequest(
                "https://www.google.com/transliterate?langpair=ja-Hira|ja&text=${query},",
                { response ->
                    dlog(" googleTransliterate response=${response.toString(4)}")
                    val list = mutableListOf<String>()
                    try {
                        val jsonArray = response.getJSONArray(0).getJSONArray(1)
                        if (jsonArray.length() == 0) { throw JSONException("no array") }
                        var i = 0
                        while (i < jsonArray.length()) {
                            val item = StringBuilder(jsonArray.get(i).toString())
                            if (!mOkurigana.isNullOrEmpty() && item.length > mOkurigana!!.length) {
                                item.deleteRange(item.length - mOkurigana!!.length, item.length)
                            } // 本当は合致しているか確認するべきかもしれない
                            list.add(item.toString())
                            i++
                        }
                    } catch (e: JSONException) {
                        dlog(" googleTransliterate JSON error: ${e.message}")
                        list.addAll(arrayListOf(
                            "(エラー)",
                            hirakana2katakana(mKanjiKey.toString()) ?: mKanjiKey.toString()
                        ))
                    } finally {
                        changeState(SKKChooseState)
                        mCandidatesList = list
                        mCurrentCandidateIndex = 0
                        mCandidateKanjiKey = mKanjiKey.toString()
                        mService.setCandidates(list, mKanjiKey.toString(), skkPrefs.candidatesNormalLines)
                        setCurrentCandidateToComposing()
                    }
                },
                { e -> dlog(" googleTransliterate API error: ${e.message}") }
            )
        )
    }

    internal fun symbolCandidates(sequential: Boolean) {
        changeState(SKKEmojiState)
        SKKEmojiState.isSequential = sequential
        val set = mutableSetOf<Pair<String, String>>()
        runBlocking {
            addFound(this, set, "/きごう", mASCIIDict)
        }
        mCandidatesList = set.map { it.second } +
                "\"#$%&'()=^~¥|@`[{;+*]},<.>\\_←↓↑→“”‘’『』【】！＂＃＄％＆＇（）－＝＾～￥｜＠｀［｛；＋：＊］｝，＜．＞／？＼＿、。"
                    .toCharArray()
                    .map { it.toString() }
        mCompletionList = mCandidatesList!!.map { "/きごう" }
        mCurrentCandidateIndex = 0
        mCandidateKanjiKey = "/きごう"
        mService.setCandidates(mCandidatesList, "/きごう", skkPrefs.candidatesNormalLines)
    }

    internal fun emojiCandidates(sequential: Boolean) {
        changeState(SKKEmojiState)
        SKKEmojiState.isSequential = sequential
        updateSuggestions("emoji")
    }

    private fun findCandidates(key: String): List<String>? {
        val userEntry = mUserDict.getEntry(key)
        val (userOkList, userRestList) = (userEntry?.candidates ?: listOf()).partition { s ->
            mOkurigana == null
                    || userEntry!!.okuriBlocks.any { it.first == mOkurigana && it.second == s }
        }

        val list: List<String> = mDicts.asSequence().mapNotNull { dict ->
            when {
                dict === mUserDict -> userOkList

                dict === mEmojiDict -> if (key == "えもじ") runBlocking {
                    mEmojiDict.findKeys(this, "").map { it.second }
                } else null

                else -> dict.getCandidates(key)
            }
        }.fold(listOf<String>()) { acc, list -> acc + list }
            .plus(userRestList) //送りがなブロックにマッチしない場合も、無ければ最後に追加
            .distinct()
            .toMutableList()

        return list
            .ifEmpty {
                dlog("Dictionary: Can't find Kanji for $key")
                null
            }
    }

    private fun getCandidate(index: Int): String? = mCandidatesList?.let {
        processConcatAndMore(removeAnnotation(it[index]), mCandidateKanjiKey)
    }

    fun setCurrentCandidateToComposing() {
        getCandidate(mCurrentCandidateIndex)?.let { candidate ->
            setComposingTextSKK(candidate + (mOkurigana ?: ""))
        }
    }

    internal fun pickCurrentCandidate(backspace: Boolean = false, unregister: Boolean = false) {
        pickCandidate(mCurrentCandidateIndex, backspace, unregister)
    }

    private fun pickCandidate(index: Int, backspace: Boolean = false, unregister: Boolean = false) {
        if (state !in listOf(SKKChooseState, SKKNarrowingState, SKKEmojiState)) return
        if (mCandidateKanjiKey == "emoji") {
            mKanjiKey.setLength(0)
            mKanjiKey.append(mCandidateKanjiKey)
            mCompletionList = mCandidatesList?.map { "emoji" }
            pickSuggestion(index, unregister)
            if (unregister) return
        }
        val candList = mCandidatesList ?: return
        val candidate = StringBuilder(getCandidate(index) ?: return)

        // 「だい4かい」ではなく「だい#かい」で登録する
        val kanjiKey = Regex("\\d+(\\.\\d+)?").find(mKanjiKey)?.let { d ->
            mKanjiKey.replaceRange(d.range, "#")
        } ?: mKanjiKey

        if (unregister) {
            val confirmingState = state as SKKConfirmingState
            confirmingState.oldComposingText = mComposingText.toString()
            val unannotated = removeAnnotation(candList[index])
            setComposingTextSKK("削除? (y/N) /$unannotated/${mOkurigana?.let { "[$mOkurigana/$unannotated/]/" } ?: ""}")
            confirmingState.pendingLambda = {
                mUserDict.removeEntry(kanjiKey.toString(), candList[index], mOkurigana)
                reset()
                changeState(kanaState)
            }
            return
        }

        if (isPersonalizedLearning) {
            mUserDict.addEntry(kanjiKey.toString(), candList[index], mOkurigana)
            // ユーザー辞書登録時はエスケープや注釈を消さない
        }

        val okuri = StringBuilder(
            (if (kanaState === SKKHiraganaState) mOkurigana else hirakana2katakana(mOkurigana)) ?: ""
        )
        if (backspace) {
            if (okuri.isNotEmpty()) okuri.deleteCharAt(okuri.lastIndex)
            else candidate.deleteCharAt(candidate.lastIndex)
        }
        val concat = candidate.toString() + okuri.toString()
        val text = when (kanaState) {
            SKKHiraganaState -> concat
            SKKKatakanaState -> hirakana2katakana(concat, reversed = true) ?: ""
            SKKHanKanaState -> zenkaku2hankaku(hirakana2katakana(concat)) ?: ""
            else -> throw RuntimeException("kanaState: $kanaState")
        } // カナかなは互換性あるけど半角カナと全角かなは互換性ない感覚があるので reverse しない
        commitTextSKK(text)
        if (mRegistrationStack.isEmpty()) {
            mLastConversion = ConversionInfo(
                text, candList, index, mKanjiKey.toString(), okuri.toString()
            )
        }

        if (state === SKKNarrowingState && SKKNarrowingState.isSequential) { return }
        reset()
        changeState(kanaState)
    }

    private fun pickSuggestion(index: Int, unregister: Boolean = false) {
        val number = Regex("\\d+(\\.\\d+)?").find(mKanjiKey)?.value ?: "#"
        val candList = mCandidatesList ?: return
        val s = candList[index].replaceFirst("#", number)
        val compList = mCompletionList ?: return
        val c = compList[index]

        when (state) {
            SKKAbbrevState -> {
                setComposingTextSKK(s)
                mKanjiKey.setLength(0)
                mKanjiKey.append(s)
                conversionStart(mKanjiKey)
            }

            SKKKanjiState -> {
                val hira = if (kanaState === SKKHiraganaState) s else katakana2hiragana(s)!!
                setComposingTextSKK(hira) // 向こうでカタカナにするので
                val li = hira.length - 1
                val last = hira.codePointAt(li)
                if (isAlphabet(last)) {
                    mKanjiKey.setLength(0)
                    mKanjiKey.append(hira.substring(0, li))
                    mComposing.setLength(0)
                    processKey(Character.toUpperCase(last))
                } else {
                    mKanjiKey.setLength(0)
                    mKanjiKey.append(hira)
                    mComposing.setLength(0)
                    conversionStart(mKanjiKey)
                }
            }

            // 絵文字のときだけ Narrowing でここに来る
            SKKASCIIState, SKKEmojiState, SKKNarrowingState -> {
                if (isPersonalizedLearning || unregister) {
                    val lambda = {
                        var newEntry = "/160/$s"
                        val key = if (state === SKKASCIIState) c else "emoji"
                        (mASCIIDict.getEntry(key)?.let { entry ->
                            entry.candidates.asSequence() // freq1, val1, freq2, val2
                                .zipWithNext() // (freq1, val1), (val1, freq2), (freq2, val2)
                                .filterIndexed { i, _ -> i % 2 == 0 } // (freq1, val1), (freq2, val2)
                                .mapNotNull {
                                    if (it.second == s) {
                                        newEntry = "/${it.first.toInt().coerceAtLeast(160)}/$s"
                                        null
                                    } else it
                                }
                                .fold("/") { str, pair -> "$str${pair.first}/${pair.second}/" }
                        } ?: "")
                            .let {
                                if (unregister) newEntry = ""
                                dlog("replaceEntry($key, $newEntry$it)")
                                mASCIIDict.replaceEntry(key, newEntry + it)
                            }
                    }
                    if (unregister) {
                        val confirmingState = state as SKKConfirmingState
                        confirmingState.oldComposingText = mComposingText.toString()
                        val unannotated = removeAnnotation(candList[index])
                        setComposingTextSKK("削除? (y/N) /$unannotated/${mOkurigana?.let { "[$mOkurigana/$unannotated/]/" } ?: ""}")
                        confirmingState.pendingLambda = {
                            lambda()
                            reset()
                            changeState(oldState)
                        }
                        return
                    } else {
                        lambda()
                    }
                }
                when (state) {
                    SKKASCIIState -> {
                        if (deletePrefixASCII(c)) {
                            commitTextSKK(removeAnnotation(s))
                            deleteSuffixASCII()
                            reset()
                        }
                    }
                    SKKEmojiState -> {
                        commitTextSKK(removeAnnotation(s))
                        if (SKKEmojiState.isSequential) { return }
                        reset() // 暗黙の確定がされないように
                        changeState(oldState)
                    }
                }
            }
        }
    }

    private fun reset() {
        mComposing.setLength(0)
        mKanjiKey.setLength(0)
        mOkurigana = null
        mCandidatesList = null
        mCandidateKanjiKey = ""
        mService.clearCandidatesView()
        if (mService.currentInputConnection.getSelectedText(0).isNullOrEmpty()) {
            mService.currentInputConnection.setComposingText(SpannableString(""), 1)
            // SpannableStringしないと BaseInputConnection の replaceTextInternal() で
            // SpannableStringBuilder SPAN_EXCLUSIVE_EXCLUSIVE spans cannot have a zero length
            // というエラーを出してしまう
        }
        mComposingText.setLength(0)
    }

    internal fun changeInputMode(pcode: Int): Boolean {
        // 入力モード変更操作．変更したらtrue
        when (pcode) {
            'q'.code -> {
                changeState(if (kanaState === SKKHiraganaState) SKKKatakanaState else SKKHiraganaState)
                return true
            }
            17 /* Ctrl-Q */ -> {
                changeState(SKKHanKanaState)
                return true
            }
            'l'.code ->  {
                if (mComposing.length != 1 || mComposing[0] != 'z') {
                    if (state === SKKKanjiState) {
                        changeState(SKKAbbrevState)
                    } else {
                        changeState(SKKASCIIState, true)
                    }
                    return true
                }
            } // 「→」を入力するための例外
            'L'.code -> {
                changeState(SKKZenkakuState)
                return true
            }
            '/'.code -> if (mComposing.isEmpty()) {
                changeState(SKKAbbrevState)
                return true
            }
        }

        return false
    }

    internal fun commitComposing() {
        if (mKanjiKey.isNotEmpty()) {
            if (!isAlphabet(mKanjiKey.first().code) && isAlphabet(mKanjiKey.last().code)) {
                mKanjiKey.deleteCharAt(mKanjiKey.lastIndex)
            }
            commitTextSKK(when (kanaState) {
                SKKKatakanaState -> hirakana2katakana(mKanjiKey.toString()) ?: ""
                SKKHanKanaState -> zenkaku2hankaku(hirakana2katakana(mKanjiKey.toString())) ?: ""
                else -> mKanjiKey.toString()
            })
        }
        if (mComposing.toString() == "n") {
            commitTextSKK(when (kanaState) {
                SKKKatakanaState -> "ン"
                SKKHanKanaState -> "ﾝ"
                else -> "ん"
            })
        }
        reset()
    }

    internal fun changeState(state: SKKState, force: Boolean = false) {
        val willBeTemporaryView = state in listOf(SKKAbbrevState, SKKZenkakuState)
        val wasTemporaryView = mService.isTemporaryView
        val inCompatibleStates = (
                this.state in listOf(SKKAbbrevState, SKKKanjiState) &&
                        state in listOf(SKKAbbrevState, SKKKanjiState)
                )

        if (this.state != SKKEmojiState)
            oldState = this.state
        else mKanjiKey.setLength(0) // mKanjiKey=="emoji"は基本的に無意味なので

        this.state = state

        val prevInputView = if (cameFromFlick) kanaState else SKKASCIIState
        if (!state.isTransient || force || willBeTemporaryView || wasTemporaryView) {
            if (!state.isTransient) {
                commitComposing() // 暗黙の確定
            } else if (!inCompatibleStates) {
                reset()
            }
            when {
                force || willBeTemporaryView -> changeSoftKeyboard(state)
                wasTemporaryView -> mService.changeSoftKeyboard(prevInputView) // cameFromFlick に記録しない
            }
            if (mRegistrationStack.isNotEmpty()) setComposingTextSKK("")
            // reset()で一旦消してるので， 登録中はここまで来てからComposingText復活
        }

        when (state) {
            SKKHiraganaState, SKKKatakanaState, SKKHanKanaState ->
                mService.kanaState = state
            SKKNarrowingState -> {
                SKKNarrowingState.mHint.setLength(0)
                SKKNarrowingState.mOriginalCandidates = null
                SKKNarrowingState.mSpaceUsed = false
                SKKNarrowingState.isSequential = false
                setCurrentCandidateToComposing()
            }
        }
        if (state.icon != 0) {
            mService.showStatusIcon(state.icon)
        } else if (state == SKKASCIIState) {
            mService.hideStatusIcon()
        }
    }
    private fun changeSoftKeyboard(state: SKKState) {
        // 仮名からASCII以外の一時的なキーボードになるときや明示的変更のとき記録して後で戻れるようにしておく
        when (state) {
            SKKAbbrevState, SKKZenkakuState   -> cameFromFlick = mService.isFlickWidth
            SKKASCIIState                     -> cameFromFlick = false
            SKKHiraganaState,
            SKKKatakanaState, SKKHanKanaState -> cameFromFlick = true
            SKKEmojiState                     -> return
        }
        mService.changeSoftKeyboard(state)
    }


    companion object {
        const val LAST_CONVERSION_SMALL = "small"
        const val LAST_CONVERSION_DAKUTEN = "daku"
        const val LAST_CONVERSION_HANDAKUTEN = "handaku"
        const val LAST_CONVERSION_TRANS = "trans"
        const val LAST_CONVERSION_SHIFT = "shift"
        const val ASCII_WORD_MAX_LENGTH = 32
    }
}

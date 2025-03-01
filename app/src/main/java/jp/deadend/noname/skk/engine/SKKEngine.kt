package jp.deadend.noname.skk.engine

import android.text.SpannableString
import com.android.volley.toolbox.JsonArrayRequest
import com.android.volley.toolbox.Volley
import jp.deadend.noname.skk.SKKDictionaryInterface
import jp.deadend.noname.skk.SKKService
import jp.deadend.noname.skk.SKKUserDictionary
import jp.deadend.noname.skk.dLog
import jp.deadend.noname.skk.hankaku2zenkaku
import jp.deadend.noname.skk.hiragana2katakana
import jp.deadend.noname.skk.isAlphabet
import jp.deadend.noname.skk.katakana2hiragana
import jp.deadend.noname.skk.processConcatAndMore
import jp.deadend.noname.skk.removeAnnotation
import jp.deadend.noname.skk.skkPrefs
import jp.deadend.noname.skk.zenkaku2hankaku
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
    private var mDictList: List<SKKDictionaryInterface>,
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
    private var mCandidateList: List<String>? = null
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
    internal var mOkurigana = ""

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
    private var kanaStateBeforeRegistration: SKKState = SKKHiraganaState

    private class RegistrationInfo(val key: String, val okurigana: String) {
        val entry = StringBuilder()
    }

    // 再変換のための情報
    private class ConversionInfo(
        val candidate: String,
        val list: List<String>,
        val index: Int,
        val kanjiKey: String,
        val okurigana: String
    )

    private var mLastConversion: ConversionInfo? = null

    internal var isPersonalizedLearning = true

    init {
        setZenkakuPunctuationMarks("en")
    }

    fun reopenDictionaries(dictList: List<SKKDictionaryInterface>) {
        for (dict in mDictList) when (dict) {
            mUserDict -> {
                mUserDict.reopen()
                mASCIIDict.reopen() // ASCII は mDictList に入れていない
            }

            mEmojiDict -> mEmojiDict.reopen()
            else -> dict.close()
        }
        mDictList = dictList
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

    fun closeUserDict() {
        mUserDict.close()
        mASCIIDict.close()
        mEmojiDict.close()
    }

    fun processKey(keyCode: Int) = state.processKey(this, keyCode)

    fun handleKanaKey() = state.handleKanaKey(this)

    fun handleBackKey(): Boolean {
        if (!mRegistrationStack.isEmpty()) {
            mRegistrationStack.removeFirst()
        }

        return when {
            state.isTransient -> {
                reset() // 確定なし
                changeState(kanaState)
                true
            }

            !mRegistrationStack.isEmpty() -> {
                reset()
                true
            }

            else -> false
        }
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

        // 変換中のものがない場合
        if (mComposing.isEmpty() && mKanjiKey.isEmpty()) {
            if (state == SKKKanjiState || state == SKKAbbrevState) {
                changeState(kanaState)
                return true
            }
            mRegistrationStack.peekFirst()?.entry.let { firstEntry ->
                if (firstEntry == null) return state.isTransient
                if (firstEntry.isNotEmpty()) {
                    firstEntry.deleteCharAt(firstEntry.lastIndex)
                    setComposingTextSKK("")
                } // else 何もしない
            }
        }

        if (mComposing.isNotEmpty()) {
            mComposing.deleteCharAt(mComposing.lastIndex)
        } else if (mKanjiKey.isNotEmpty()) {
            mKanjiKey.deleteCharAt(mKanjiKey.lastIndex)
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

        mRegistrationStack.peekFirst()?.entry.let { firstEntry ->
            if (firstEntry == null) {
                ic.commitText(text, 1)
                mComposingText.setLength(0)
                return
            }
            firstEntry.append(text)
            setComposingTextSKK("")
        }
    }

    fun resetOnStartInput() {
        mComposing.setLength(0)
        mKanjiKey.setLength(0)
        mOkurigana = ""
        mCandidateList = null
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
        val candidateList = mCandidateList ?: return

        if (isForward) {
            mCurrentCandidateIndex++
        } else {
            mCurrentCandidateIndex--
        }

        // 範囲外になったら反対側へ
        if (mCurrentCandidateIndex > candidateList.size - 1) {
            mCurrentCandidateIndex = 0
        } else if (mCurrentCandidateIndex < 0) {
            mCurrentCandidateIndex = candidateList.size - 1
        }

        mService.requestChooseCandidate(mCurrentCandidateIndex)
        mKanjiKey.setLength(0)
        mKanjiKey.append(candidateList[mCurrentCandidateIndex])
        setComposingTextSKK(mKanjiKey)
    }

    fun chooseAdjacentCandidate(isForward: Boolean) {
        val candidateList = mCandidateList ?: return

        if (isForward) {
            mCurrentCandidateIndex++
        } else {
            mCurrentCandidateIndex--
        }

        // 最初の候補より戻ると変換に戻る 最後の候補より進むと登録
        if (mCurrentCandidateIndex > candidateList.size - 1) when (state) {
            SKKChooseState -> {
                registerStart(mKanjiKey.toString())
                return
            }

            SKKNarrowingState -> {
                mCurrentCandidateIndex = 0
            }
        } else if (mCurrentCandidateIndex < 0) when (state) {
            SKKChooseState -> {
                if (mComposing.isEmpty()) {
                    // KANJIモードに戻る
                    if (mOkurigana.isNotEmpty()) {
                        mOkurigana = ""
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
            }

            SKKNarrowingState -> {
                mCurrentCandidateIndex = candidateList.size - 1
            }
        }

        mService.requestChooseCandidate(mCurrentCandidateIndex)
        setCurrentCandidateToComposing()
    }

    fun pickCandidatesViewManually(index: Int, unregister: Boolean = false) {
        val state = state
        if (state is SKKConfirmingState) {
            state.apply {
                pendingLambda?.let {
                    it.invoke()
                    pendingLambda = null
                    return
                }
            }
        }
        when (state) {
            SKKChooseState, SKKNarrowingState ->
                pickCandidate(index, unregister = unregister)

            SKKAbbrevState, SKKKanjiState, SKKASCIIState, SKKEmojiState, SKKOkuriganaState ->
                pickSuggestion(index, unregister)

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
                if (idx < 1 && type == LAST_CONVERSION_SHIFT) return // ▽あ
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
                if (type == LAST_CONVERSION_SHIFT) return
                val newLastChar = RomajiConverter.convertLastChar(hint.substring(idx), type).second
                // この convertLastChar にも 2 文字が渡ることはない

                hint.deleteCharAt(idx)
                hint.append(newLastChar)
                narrowCandidates(hint.toString())
            }

            state === SKKChooseState -> {
                if (mOkurigana.isEmpty()) return
                val okurigana = mOkurigana // ▼合い (okurigana = い)
                val newOkurigana = RomajiConverter.convertLastChar(okurigana, type).second

                if (type == LAST_CONVERSION_SHIFT) {
                    handleCancel() // ▽あ (mOkurigana = null)
                    mKanjiKey.append(newOkurigana) // ▽あい
                    setComposingTextSKK(mKanjiKey)
                    updateSuggestions(mKanjiKey.toString())
                    return
                }
                // 例外: 送りがなが「っ」になる場合は，どのみち必ずt段の音なのでmKanjiKeyはそのまま
                // 「ゃゅょ」で送りがなが始まる場合はないはず
                if (type != LAST_CONVERSION_SMALL) {
                    mKanjiKey.deleteCharAt(mKanjiKey.length - 1)
                    mKanjiKey.append(RomajiConverter.getConsonantForVoiced(newOkurigana))
                }
                mOkurigana = newOkurigana
                conversionStart(mKanjiKey) //変換やりなおし
            }

            mComposing.isEmpty() && mKanjiKey.isEmpty() -> {
                val ic = mService.currentInputConnection ?: return
                val cs = ic.getTextBeforeCursor(2, 0) ?: return
                // 0〜2 文字を 0〜3 文字にするので注意!
                val newLast2Chars = RomajiConverter.convertLastChar(cs.toString(), type)
                dLog("changeLastChar: 2chars=$newLast2Chars")
                if (newLast2Chars.first.isEmpty() && newLast2Chars.second.isEmpty()) return

                val deleteTwo = (cs.length == 2 && newLast2Chars.first.isEmpty())
                val newLastChar = newLast2Chars.second

                val firstEntry = mRegistrationStack.peekFirst()?.entry
                if (firstEntry != null) {
                    if (firstEntry.isEmpty()) return
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

        if (mRegistrationStack.isNotEmpty()) {
            val depth = mRegistrationStack.size
            repeat(depth) { ct.append("[") }
            ct.append("登録")
            repeat(depth) { ct.append("]") }

            mRegistrationStack.peekFirst()?.let { regInfo ->
                val key =
                    if (kanaState === SKKHiraganaState) regInfo.key
                    else hiragana2katakana(regInfo.key)!!
                val okurigana =
                    if (kanaState === SKKHiraganaState) regInfo.okurigana
                    else hiragana2katakana(regInfo.okurigana).orEmpty()
                // 半角カナ変換はあとで
                if (okurigana.isEmpty()) {
                    ct.append(key)
                } else {
                    ct.append(key.substring(0, key.length - 1))
                    ct.append("*")
                    ct.append(okurigana)
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
        ct.append(
            if (mService.isHiragana) text else hiragana2katakana(
                text.toString(),
                reversed = true
            )
        )
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

        val list = findCandidates(str).ifEmpty {
            findCandidates(str.replace(Regex("\\d+(\\.\\d+)?"), "#")).ifEmpty {
                return registerStart(str)
            }
        }

        mCandidateList = list
        mCurrentCandidateIndex = 0
        mCandidateKanjiKey = str
        mService.setCandidates(list, str, skkPrefs.candidatesNormalLines)
        setCurrentCandidateToComposing()
    }

    internal fun narrowCandidates(hint: String) {
        if (SKKNarrowingState.mOriginalCandidates == null) {
            SKKNarrowingState.mOriginalCandidates = mCandidateList
        }
        val candidates = SKKNarrowingState.mOriginalCandidates ?: return

        val narrowed = if (hint.isEmpty()) candidates else {
            val hintKanjiSequence = findCandidates(hint).joinToString("") {
                processConcatAndMore(removeAnnotation(it), "")
            }

            // mCandidateKanjiKey("かんじ") -> candidates("漢字", "幹事", "監事", "感じ")
            // hint("おとこ") -> hintKanjiSequence("男漢♂")
            candidates.filter { str -> /* str("漢字; 注釈も含む") */
                str.any { ch -> hintKanjiSequence.contains(ch) } /* hintKanjiSequenceは注釈なし */
                        || str.contains(hint) /* ひらがなかカタカナでヒントを含むstrもOK */
                        || hiragana2katakana(hint).let { !it.isNullOrEmpty() && str.contains(it) }
            }.let { nList ->
                if (mCandidateKanjiKey == "emoji")
                    nList.map { removeAnnotation(it) }
                else nList
            }
        }

        if (narrowed.isNotEmpty()) {
            mCandidateList = narrowed
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

    internal fun reConvert(): Boolean {
        val lastConv = mLastConversion ?: return false

        val s = lastConv.candidate
        dLog("last conversion: $s")
        if (mService.prepareReConversion(s)) {
            mUserDict.rollBack()

            changeState(SKKChooseState)

            mComposing.setLength(0)
            mKanjiKey.setLength(0)
            mKanjiKey.append(lastConv.kanjiKey)
            mOkurigana = lastConv.okurigana
            mCandidateList = lastConv.list
            mCurrentCandidateIndex = lastConv.index
            mCandidateKanjiKey = lastConv.kanjiKey
            mService.setCandidates(
                mCandidateList,
                mCandidateKanjiKey,
                skkPrefs.candidatesNormalLines
            )
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
                    for (dict in mDictList) {
                        addFound(this@launch, set, str, dict)
                    }
                str.replace(Regex("\\d+(\\.\\d+)?"), "#").let {
                    if (it != str) for (dict in mDictList) {
                        addFound(this@launch, set, it, dict)
                    }
                }

                set.distinctBy { it.second }
                    .let { uniqueSet ->
                        mCompletionList = uniqueSet.map { it.first }
                        mCandidateList = uniqueSet.map { it.second }
                    }

                mCandidateKanjiKey = str
                mCurrentCandidateIndex = 0
                withContext(Dispatchers.Main) {
                    if (str == "emoji")
                        mService.setCandidates(
                            mCandidateList?.map { removeAnnotation(it) },
                            str,
                            skkPrefs.candidatesEmojiLines
                        )
                    else
                        mService.setCandidates(
                            mCandidateList,
                            str,
                            skkPrefs.candidatesNormalLines
                        )
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
        dict: SKKDictionaryInterface
    ) {
        val dictionary = when (dict) {
            mUserDict -> {
                if (key == "emoji" || state === SKKASCIIState) mASCIIDict else mUserDict
            }

            mEmojiDict -> {
                if (key == "emoji" || key == "えもじ") {
                    mEmojiDict.findKeys(scope, "").forEach { suggestion ->
                        target.add(key to suggestion.second)
                    }
                }
                return
            }

            else -> dict
        }
        if (mService.isHiragana) {
            target.addAll(dictionary.findKeys(scope, key))
        } else dictionary.findKeys(scope, key).map { suggestion ->
            target.add(suggestion.first to hiragana2katakana(suggestion.second, reversed = true)!!)
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
        kanaStateBeforeRegistration = kanaState
        changeState(SKKHiraganaState) // 辞書にカタカナで登録してしまわないように
    }

    private fun registerWord() {
        val regInfo = mRegistrationStack.removeFirst()
        if (regInfo.entry.isNotEmpty()) {
            val regEntryStr = regInfo.entry.toString().let {
                // セミコロンとスラッシュのエスケープ (なので登録で注釈を付けることはできない)
                if (it.contains(';') || it.contains('/')) {
                    "(concat \"${
                        it.replace(";", "\\073")
                            .replace("/", "\\057")
                        //  .replace("#", "\\043") をしてしまうと数値変換が登録できなくなる
                    }\")"
                } else it
            }
            // if (isPersonalizedLearning) のチェックはこの場合しないでおく
            mUserDict.addEntry(
                regInfo.key, regEntryStr, regInfo.okurigana
            )
            (regInfo.entry.toString() + regInfo.okurigana).let {
                commitTextSKK(
                    when (kanaStateBeforeRegistration) {
                        SKKHiraganaState -> it
                        SKKKatakanaState -> hiragana2katakana(it, reversed = true).orEmpty()
                        SKKHanKanaState -> zenkaku2hankaku(hiragana2katakana(it)).orEmpty()
                        // 登録した内容が半角化できる文字を含んでいる場合は、やりすぎになるが無視
                        else -> throw RuntimeException("kanaState: $kanaStateBeforeRegistration")
                    }
                )
            }
        }
        reset()
        if (mRegistrationStack.isNotEmpty()) {
            setComposingTextSKK("")
        } else {
            changeState(kanaStateBeforeRegistration)
        }
    }

    internal fun cancelRegister() {
        changeState(kanaStateBeforeRegistration)
        val regInfo = mRegistrationStack.removeFirst()
        mKanjiKey.setLength(0)
        mKanjiKey.append(regInfo.key)
        mComposing.setLength(0)
        mKanjiKey.lastOrNull()?.let { maybeComposing ->
            if (isAlphabet(maybeComposing.code)) {
                mKanjiKey.deleteCharAt(mKanjiKey.lastIndex)
                if (!skkPrefs.preferFlick) { // Flickでアルファベットがあっても困る
                    mComposing.append(maybeComposing)
                }
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
        dLog("googleTransliterate mKanjiKey=${mKanjiKey} mOkurigana=${mOkurigana}")

        changeState(SKKKanjiState)
        val query = if (mKanjiKey.isNotEmpty() && isAlphabet(mKanjiKey.last().code)) {
            val trimmedKanjiKey = mKanjiKey.substring(0, mKanjiKey.lastIndex)
            setComposingTextSKK("${trimmedKanjiKey}*${mOkurigana}")
            "${trimmedKanjiKey}${mOkurigana}"
        } else {
            setComposingTextSKK(mKanjiKey.toString())
            mKanjiKey.toString() // たぶん送り仮名は存在しないはず
        }
        val volleyQueue = Volley.newRequestQueue(mService)
        volleyQueue.add(
            JsonArrayRequest(
                "https://www.google.com/transliterate?langpair=ja-Hira|ja&text=${query},",
                { response ->
                    dLog(" googleTransliterate response=${response.toString(4)}")
                    val list = mutableListOf<String>()
                    try {
                        val jsonArray = response.getJSONArray(0).getJSONArray(1)
                        if (jsonArray.length() == 0) {
                            throw JSONException("no array")
                        }
                        var i = 0
                        while (i < jsonArray.length()) {
                            val item = StringBuilder(jsonArray.get(i).toString())
                            if (mOkurigana.isNotEmpty() && item.length > mOkurigana.length) {
                                item.deleteRange(item.length - mOkurigana.length, item.length)
                            } // 本当は合致しているか確認するべきかもしれない
                            list.add(item.toString())
                            i++
                        }
                    } catch (e: JSONException) {
                        dLog(" googleTransliterate JSON error: ${e.message}")
                        list.addAll(
                            arrayListOf(
                                "(エラー)",
                                hiragana2katakana(mKanjiKey.toString()) ?: mKanjiKey.toString()
                            )
                        )
                    } finally {
                        changeState(SKKChooseState)
                        mCandidateList = list
                        mCurrentCandidateIndex = 0
                        mCandidateKanjiKey = mKanjiKey.toString()
                        mService.setCandidates(
                            list,
                            mKanjiKey.toString(),
                            skkPrefs.candidatesNormalLines
                        )
                        setCurrentCandidateToComposing()
                    }
                },
                { e -> dLog(" googleTransliterate API error: ${e.message}") }
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
        mCandidateList = set.map { it.second } +
                "\"#$%&'()=^~¥|@`[{;+*]},<.>\\_←↓↑→“”‘’『』【】！＂＃＄％＆＇（）－＝＾～￥｜＠｀［｛；＋：＊］｝，＜．＞／？＼＿、。"
                    .toCharArray()
                    .map { it.toString() }
        mCompletionList = mCandidateList!!.map { "/きごう" }
        mCurrentCandidateIndex = 0
        mCandidateKanjiKey = "/きごう"
        mService.setCandidates(mCandidateList, "/きごう", skkPrefs.candidatesNormalLines)
    }

    internal fun emojiCandidates(sequential: Boolean) {
        changeState(SKKEmojiState)
        SKKEmojiState.isSequential = sequential
        updateSuggestions("emoji")
    }

    private fun findCandidates(key: String): List<String> {
        val userEntry = mUserDict.getEntry(key)
        dLog("user dictionary: $key -> ${userEntry?.candidates} with ${userEntry?.okuriganaBlocks}")
        val (userOkList, userRestList) = (userEntry?.candidates ?: listOf()).partition { s ->
            mOkurigana.isEmpty() || userEntry!!.okuriganaBlocks.any {
                it.first == katakana2hiragana(hankaku2zenkaku(mOkurigana)) && it.second == s
                // 送り仮名ブロックを直接使って変換するのではなく、この判定にだけ使っている
                // なので、送り仮名ブロックだけで存在していても無意味である
            }
        }

        val list: List<String> = mDictList.asSequence().mapNotNull { dict ->
            when (dict) {
                mUserDict -> userOkList

                mEmojiDict -> if (key == "えもじ") runBlocking {
                    mEmojiDict.findKeys(this, "").map { it.second }
                } else null

                else -> dict.getCandidates(key)
            }
        }.fold(listOf<String>()) { acc, list -> acc + list }
            .plus(userRestList) //送りがなブロックにマッチしない場合も、無ければ最後に追加
            .distinct()
            .toMutableList()

        if (list.isEmpty()) dLog("Dictionary: Can't find Kanji for $key")

        return list
    }

    private fun getCandidate(index: Int): String? = mCandidateList?.let {
        processConcatAndMore(removeAnnotation(it[index]), mCandidateKanjiKey)
    }

    fun setCurrentCandidateToComposing() {
        getCandidate(mCurrentCandidateIndex)?.let { candidate ->
            setComposingTextSKK(candidate + mOkurigana)
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
            mCompletionList = mCandidateList?.map { "emoji" }
            pickSuggestion(index, unregister)
            return
        }
        val candidateList = mCandidateList ?: return
        val candidate = StringBuilder(getCandidate(index) ?: return)

        if (unregister) {
            val confirmingState = state as SKKConfirmingState
            confirmingState.oldComposingText = mComposingText.toString()
            val unannotated = removeAnnotation(candidateList[index])
            val entryString =
                "/$unannotated/${
                    if (mOkurigana.isNotEmpty()) "[$mOkurigana/$unannotated/]/" else ""
                }"
            setComposingTextSKK("削除? (y/N) $entryString")
            mService.setCandidates(listOf("削除する $entryString"), "", 1)
            confirmingState.pendingLambda = {
                mUserDict.removeEntry(mKanjiKey.toString(), candidateList[index], mOkurigana)
                reset()
                changeState(kanaState)
            }
            return
        }

        if (isPersonalizedLearning) {
            mUserDict.addEntry(mKanjiKey.toString(), candidateList[index], mOkurigana)
            // ユーザー辞書登録時はエスケープや注釈を消さない
        }

        if (backspace) {
            if (mOkurigana.isNotEmpty()) mOkurigana = mOkurigana.dropLast(1)
            else candidate.deleteCharAt(candidate.lastIndex)
        }
        val concat = candidate.toString() + mOkurigana
        val text = when (kanaState) {
            SKKHiraganaState -> concat
            SKKKatakanaState -> hiragana2katakana(concat, reversed = true).orEmpty()
            SKKHanKanaState -> zenkaku2hankaku(hiragana2katakana(concat)).orEmpty()
            else -> throw RuntimeException("kanaState: $kanaState")
        } // カナかなは互換性あるけど半角カナと全角かなは互換性ない感覚があるので reverse しない
        commitTextSKK(text)
        if (mRegistrationStack.isEmpty()) {
            mLastConversion = ConversionInfo(
                text, candidateList, index, mKanjiKey.toString(), mOkurigana
            )
        }

        if (state === SKKNarrowingState && SKKNarrowingState.isSequential) return
        reset()
        changeState(kanaState)
    }

    private fun pickSuggestion(index: Int, unregister: Boolean = false) {
        var number = Regex("\\d+(\\.\\d+)?").find(mKanjiKey)
        val rawSuggestion = mCandidateList?.get(index) ?: return
        val s = rawSuggestion.map { ch ->
            if (ch != '#' || number == null) ch.toString() else {
                number!!.let {
                    number = it.next()
                    it.value
                }
            }
        }.joinToString("")
        val c = mCompletionList?.get(index) ?: return

        when (state) {
            SKKAbbrevState -> {
                setComposingTextSKK(s)
                mKanjiKey.setLength(0)
                mKanjiKey.append(s)
                conversionStart(mKanjiKey)
            }

            SKKKanjiState, SKKOkuriganaState -> {
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

            // 絵文字か記号のときだけ Narrowing でここに来る
            SKKASCIIState, SKKEmojiState, SKKNarrowingState -> {
                if (isPersonalizedLearning || unregister) {
                    val lambda = {
                        var newEntry = "/160/$s"
                        val key = when {
                            state === SKKASCIIState -> c
                            c == "/きごう" -> c
                            else -> "emoji"
                        }
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
                        }.orEmpty())
                            .let {
                                if (unregister) newEntry = ""
                                dLog("replaceEntry($key, $newEntry$it)")
                                mASCIIDict.replaceEntry(key, newEntry + it)
                            }
                    }
                    if (unregister) {
                        val confirmingState = state as SKKConfirmingState
                        confirmingState.oldComposingText = mComposingText.toString()
                        val unannotated = removeAnnotation(rawSuggestion)
                        val entryString =
                            "/$unannotated/${
                                if (mOkurigana.isNotEmpty()) "[$mOkurigana/$unannotated/]/" else ""
                            }"
                        setComposingTextSKK("削除? (y/N) $entryString")
                        mService.setCandidates(listOf("削除する $entryString"), "", 1)
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

                    SKKEmojiState, SKKNarrowingState -> {
                        commitTextSKK(removeAnnotation(s))
                        if (SKKEmojiState.isSequential) return
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
        mOkurigana = ""
        mCandidateList = null
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

    internal fun changeInputMode(keyCode: Int): Boolean {
        // 入力モード変更操作．変更したらtrue
        when (keyCode) {
            'q'.code -> {
                changeState(if (kanaState === SKKHiraganaState) SKKKatakanaState else SKKHiraganaState)
                return true
            }

            17 /* Ctrl-Q */ -> {
                changeState(SKKHanKanaState)
                return true
            }

            'l'.code -> {
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
            commitTextSKK(
                when (kanaState) {
                    SKKKatakanaState ->
                        hiragana2katakana(mKanjiKey.toString()).orEmpty()

                    SKKHanKanaState ->
                        zenkaku2hankaku(hiragana2katakana(mKanjiKey.toString())).orEmpty()

                    else -> mKanjiKey.toString()
                }
            )
        }
        if (mComposing.toString() == "n") {
            commitTextSKK(
                when (kanaState) {
                    SKKKatakanaState -> "ン"
                    SKKHanKanaState -> "ﾝ"
                    else -> "ん"
                }
            )
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
            SKKAbbrevState, SKKZenkakuState -> cameFromFlick = mService.isFlickWidth
            SKKASCIIState -> cameFromFlick = false
            SKKHiraganaState,
            SKKKatakanaState, SKKHanKanaState -> cameFromFlick = true

            SKKEmojiState -> return
        }
        mService.changeSoftKeyboard(state)
    }


    companion object {
        const val LAST_CONVERSION_SMALL = "small"
        const val LAST_CONVERSION_DAKUTEN = "dakuten"
        const val LAST_CONVERSION_HANDAKUTEN = "handakuten"
        const val LAST_CONVERSION_TRANS = "trans"
        const val LAST_CONVERSION_SHIFT = "shift"
        const val ASCII_WORD_MAX_LENGTH = 32
    }
}

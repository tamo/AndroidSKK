package jp.deadend.noname.skk.engine

import com.android.volley.toolbox.JsonArrayRequest
import com.android.volley.toolbox.Volley
import jp.deadend.noname.skk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.util.ArrayDeque

class SKKEngine(
        private val mService: SKKService,
        private var mDicts: List<SKKDictionary>,
        private val mUserDict: SKKUserDictionary,
        private val mASCIIDict: SKKUserDictionary
) {
    var state: SKKState = SKKHiraganaState
        private set
    internal val kanaState: SKKState
        get() = if (mService.isHiragana) SKKHiraganaState else SKKKatakanaState
    internal var cameFromFlick: Boolean = skkPrefs.preferFlick

    // 候補のリスト．KanjiStateとAbbrevStateでは補完リスト，ChooseStateでは変換候補リストになる
    private var mCandidatesList: List<String>? = null
    private var mCurrentCandidateIndex = 0
    private var mUpdateSuggestionsJob: Job = Job()

    // ひらがなや英単語などの入力途中
    internal val mComposing = StringBuilder()
    // 漢字変換のキー 送りありの場合最後がアルファベット 変換中は不変
    internal val mKanjiKey = StringBuilder()
    // 送りがな 「っ」や「ん」が含まれる場合だけ二文字になる
    internal var mOkurigana: String? = null
    // 実際に表示されているもの
    internal val mComposingText = StringBuilder()
    internal var mCursorPosition = 1

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

    fun reopenDictionaries(dics: List<SKKDictionary>) {
        for (dic in mDicts) { dic.close() }
        mDicts = dics
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
            mService.onFinishRegister()
        }

        if (state.isTransient) {
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
            SKKKanjiState, SKKOkuriganaState, SKKAbbrevState -> {
                commitTextSKK(
                    if (mService.isHiragana) mKanjiKey else hirakana2katakana(mKanjiKey.toString())!!,
                    1
                )
                changeState(kanaState)
            }
            else -> {
                if (mComposing.isEmpty()) {
                    if (!mRegistrationStack.isEmpty()) {
                        registerWord()
                    } else {
                        return false
                    }
                } else {
                    commitTextSKK(mComposing, 1)
                    mComposing.setLength(0)
                }
            }
        }

        return true
    }

    fun handleBackspace(): Boolean {
        if (state == SKKNarrowingState || state == SKKChooseState) {
            state.afterBackspace(this)
            return true
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
                    setComposingTextSKK("", 1)
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
     * @param newCursorPosition
     */
    fun commitTextSKK(text: CharSequence, newCursorPosition: Int) {
        val ic = mService.currentInputConnection ?: return

        val firstEntry = mRegistrationStack.peekFirst()?.entry
        if (firstEntry != null) {
            firstEntry.append(text)
            setComposingTextSKK("", newCursorPosition)
        } else {
            ic.commitText(text, newCursorPosition)
            mComposingText.setLength(0)
            mCursorPosition = newCursorPosition
        }
    }

    fun resetOnStartInput() {
        mComposing.setLength(0)
        mKanjiKey.setLength(0)
        mOkurigana = null
        mCandidatesList = null
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
        setComposingTextSKK(mKanjiKey, 1)
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
                    setComposingTextSKK(mKanjiKey, 1)
                    updateSuggestions(mKanjiKey.toString())
                } else {
                    mKanjiKey.setLength(0)
                    changeState(SKKAbbrevState)
                    setComposingTextSKK(mComposing, 1)
                    updateSuggestions(mComposing.toString())
                }

                mCurrentCandidateIndex = 0
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
            SKKAbbrevState, SKKKanjiState, SKKASCIIState -> pickSuggestion(index)
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
            state === SKKKanjiState && mComposing.isEmpty() -> {
                val s = mKanjiKey.toString() // ▽あい
                val idx = s.length - 1
                if (idx < 0) { return } // ▽
                if (idx < 1 && type == LAST_CONVERSION_SHIFT) { return } // ▽あ
                val newLastChar = RomajiConverter.convertLastChar(s.substring(idx), type) ?: return

                mKanjiKey.deleteCharAt(idx)
                if (type == LAST_CONVERSION_SHIFT) {
                    mKanjiKey.append(RomajiConverter.getConsonantForVoiced(newLastChar))
                    mOkurigana = newLastChar
                    conversionStart(mKanjiKey) // ▼合い
                } else {
                    mKanjiKey.append(newLastChar)
                    setComposingTextSKK(mKanjiKey, 1)
                    updateSuggestions(mKanjiKey.toString())
                }
            }
            state === SKKNarrowingState && mComposing.isEmpty() -> {
                val hint = SKKNarrowingState.mHint // ▼藹 hint: わ
                val idx = hint.length - 1
                if (idx < 0) { return } // ▼藹 hint:
                if (type == LAST_CONVERSION_SHIFT) { return }
                val newLastChar = RomajiConverter.convertLastChar(hint.substring(idx), type) ?: return

                hint.deleteCharAt(idx)
                hint.append(newLastChar)
                narrowCandidates(hint.toString())
            }
            state === SKKChooseState -> {
                val okuri = mOkurigana ?: return // ▼合い (okuri = い)
                val newOkuri = RomajiConverter.convertLastChar(okuri, type) ?: return

                if (type == LAST_CONVERSION_SHIFT) {
                    handleCancel() // ▽あ (mOkurigana = null)
                    mKanjiKey.append(newOkuri) // ▽あい
                    setComposingTextSKK(mKanjiKey, 1)
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
                val cs = ic.getTextBeforeCursor(1, 0) ?: return
                val newLastChar = RomajiConverter.convertLastChar(cs.toString(), type) ?: return

                val firstEntry = mRegistrationStack.peekFirst()?.entry
                if (firstEntry != null) {
                    if (firstEntry.isEmpty()) { return }
                    firstEntry.deleteCharAt(firstEntry.length - 1)
                    if (type == LAST_CONVERSION_SHIFT) {
                        mKanjiKey.append(katakana2hiragana(newLastChar))
                        changeState(SKKKanjiState)
                        setComposingTextSKK(mKanjiKey, 1)
                        updateSuggestions(mKanjiKey.toString())
                    } else {
                        firstEntry.append(newLastChar)
                        setComposingTextSKK("", 1)
                    }
                } else {
                    ic.deleteSurroundingText(1, 0)
                    if (type == LAST_CONVERSION_SHIFT) {
                        mKanjiKey.append(katakana2hiragana(newLastChar))
                        changeState(SKKKanjiState) // Abbrevから来ることはないはず
                        setComposingTextSKK(mKanjiKey, 1)
                        updateSuggestions(mKanjiKey.toString())
                    } else {
                        ic.commitText(newLastChar, 1)
                        mComposingText.setLength(0)
                        mCursorPosition = 1
                    }
                }
            }
        }
    }

    internal fun getZenkakuSeparator(key: String) = mZenkakuSeparatorMap[key]

    /**
     * setComposingTextのラッパー 変換モードマーク等を追加する
     * @param text
     * @param newCursorPosition
     */
    internal fun setComposingTextSKK(text: CharSequence, newCursorPosition: Int) {
        val ic = mService.currentInputConnection ?: return
        val ct = mComposingText
        ct.setLength(0)
        mCursorPosition = newCursorPosition

        if (!mRegistrationStack.isEmpty()) {
            val depth = mRegistrationStack.size
            repeat(depth) { ct.append("[") }
            ct.append("登録")
            repeat(depth) { ct.append("]") }

            val regInfo = mRegistrationStack.peekFirst()
            if (regInfo != null) {
                val key = if (mService.isHiragana) regInfo.key else hirakana2katakana(regInfo.key)!!
                val okuri = if (mService.isHiragana) regInfo.okurigana else hirakana2katakana(regInfo.okurigana)
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
        }
        ct.append(if (mService.isHiragana) text else hirakana2katakana(text.toString(), reversed = true))
        if (state === SKKNarrowingState) {
            ct.append(" hint: ", SKKNarrowingState.mHint, mComposing)
        }

        ic.setComposingText(ct, newCursorPosition)
    }

    /***
     * 変換スタート
     * 送りありの場合，事前に送りがなをmOkuriganaにセットしておく
     * @param key 辞書のキー 送りありの場合最後はアルファベット
     */
    internal fun conversionStart(key: StringBuilder) {
        val str = key.toString()

        changeState(SKKChooseState)

        var number = "#"
        val list = findCandidates(str) ?: Regex("\\d+").find(str)?.let {
            number = it.value
            findCandidates(str.replaceRange(it.range, "#"))
        }
        if (list == null) {
            registerStart(str)
            return
        }

        mCandidatesList = list
        mCurrentCandidateIndex = 0
        mService.setCandidates(list, number)
        setCurrentCandidateToComposing()
    }

    internal fun narrowCandidates(hint: String) {
        val candidates = SKKNarrowingState.mOriginalCandidates ?: mCandidatesList ?: return
        if (SKKNarrowingState.mOriginalCandidates == null) {
            SKKNarrowingState.mOriginalCandidates = mCandidatesList
        }
        val hintKanjis = findCandidates(hint)
                ?.joinToString(
                    separator="",
                    transform={ processConcatAndEscape(removeAnnotation(it)) }
                ) ?: ""

        val narrowed = candidates.filter { str -> str.any { ch -> hintKanjis.contains(ch) } }
        if (narrowed.isNotEmpty()) {
            mCandidatesList = narrowed
            mCurrentCandidateIndex = 0
            val number = Regex("\\d+").find(mKanjiKey)?.value ?: "#"
            mService.setCandidates(narrowed, number)
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
            val number = Regex("\\d+").find(lastConv.kanjiKey)?.value ?: "#"
            mService.setCandidates(mCandidatesList, number)
            mService.requestChooseCandidate(mCurrentCandidateIndex)
            setCurrentCandidateToComposing()

            return true
        }

        return false
    }

    internal fun updateSuggestions(str: String) {
        val job = MainScope().launch(Dispatchers.Default) {
            val set = mutableSetOf<String>()

            if (str.isNotEmpty()) {
                addFound(this, set, str, (if (state === SKKASCIIState) mASCIIDict else mUserDict))
                for (dic in mDicts) { addFound(this, set, str, dic) }
            }
            val number = Regex("\\d+").find(str)?.also {
                val replacedStr = str.replaceRange(it.range, "#")
                addFound(this, set, replacedStr, (if (state === SKKASCIIState) mASCIIDict else mUserDict))
                for (dic in mDicts) { addFound(this, set, replacedStr, dic) }
            }?.value ?: "#"

            mCandidatesList = set.toList()
            mCurrentCandidateIndex = 0
            withContext(Dispatchers.Main) { mService.setCandidates(mCandidatesList, number) }
        }

        mUpdateSuggestionsJob.cancel()
        mUpdateSuggestionsJob.invokeOnCompletion {
            mUpdateSuggestionsJob = job
            mUpdateSuggestionsJob.start()
        }
    }
    private fun addFound(
        scope: CoroutineScope,
        target: MutableSet<String>,
        key: String,
        dic: SKKDictionaryInterface
    ) {
        if (mService.isHiragana) {
            target.addAll(dic.findKeys(scope, key))
        } else for (hiraSuggestion in dic.findKeys(scope, key)) {
            target.add(hirakana2katakana(hiraSuggestion, reversed = true)!!)
        }
    }

    internal fun updateSuggestionsASCII() {
        if (state !== SKKASCIIState) return
        MainScope().launch(Dispatchers.Default) {
            delay(50) // バックスペースなどの処理が間に合っていないことがあるので
            updateSuggestions(getPrefixASCII())
        }.start()
    }

    private fun getPrefixASCII(): String {
        val ic = mService.currentInputConnection ?: return ""
        val tbc = ic.getTextBeforeCursor(ASCII_WORD_MAX_LENGTH, 0) ?: return ""
        return tbc.split(Regex("\\W")).last()
    }

    private fun deleteSuffixASCII() {
        val ic = mService.currentInputConnection ?: return
        val tac = ic.getTextAfterCursor(ASCII_WORD_MAX_LENGTH, 0) ?: return
        val wac = tac.split(Regex("\\W")).first()
        ic.deleteSurroundingText(0, wac.length)
        return
    }

    private fun registerStart(str: String) {
        mRegistrationStack.addFirst(RegistrationInfo(str, mOkurigana))
        changeState(kanaState)
        //setComposingTextSKK("", 1);

        mService.onStartRegister()
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
            commitTextSKK(regInfo.entry.append(
                (if (mService.isHiragana) regInfo.okurigana else hirakana2katakana(regInfo.okurigana)) ?: ""
            ), 1) // entry は生で登録するが okurigana はひらがな
        }
        reset()
        if (!mRegistrationStack.isEmpty()) setComposingTextSKK("", 1)

        mService.onFinishRegister()
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
        setComposingTextSKK("${mKanjiKey}${mComposing}", 1)
        updateSuggestions(mKanjiKey.toString())
        mService.onFinishRegister()
    }

    internal fun googleTransliterate() {
        if (mRegistrationStack.isEmpty()) {
            if (mKanjiKey.isEmpty()) return
        } else {
            // candidate から選択しただけで登録されるので、onFinishRegister してから変換
            val regInfo = mRegistrationStack.removeFirst() ?: return
            mComposing.setLength(0)
            mKanjiKey.setLength(0)
            mKanjiKey.append(regInfo.key)
            mOkurigana = regInfo.okurigana
            mService.onFinishRegister()
        }
        dlog("googleTransliterate mKanjiKey=${mKanjiKey} mOkurigana=${mOkurigana}")

        changeState(SKKKanjiState)
        val query = if (mKanjiKey.isNotEmpty() && isAlphabet(mKanjiKey.last().code)) {
            val trimmedKanjiKey = mKanjiKey.substring(0, mKanjiKey.lastIndex)
            setComposingTextSKK("${trimmedKanjiKey}*${mOkurigana ?: ""}", 1)
            "${trimmedKanjiKey}${mOkurigana ?: ""}"
        } else {
            setComposingTextSKK(mKanjiKey.toString(), 1)
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
                        mService.setCandidates(list, "#")
                        setCurrentCandidateToComposing()
                    }
                },
                { e -> dlog(" googleTransliterate API error: ${e.message}") }
            )
        )
    }

    private fun findCandidates(key: String): List<String>? {
        val list1 = mDicts.mapNotNull { it.getCandidates(key) }
                .fold(listOf()) { acc: Iterable<String>, list: Iterable<String> -> acc.union(list) }
                .toMutableList()

        val entry = (if (state === SKKASCIIState) mASCIIDict else mUserDict).getEntry(key)
        val list2 = entry?.candidates

        if (list1.isEmpty() && list2 == null) {
            dlog("Dictoinary: Can't find Kanji for $key")
            return null
        }

        if (list2 != null) {
            var idx = 0
            for (s in list2) {
                when {
                    mOkurigana == null
                     || entry.okuriBlocks.any { it.first == mOkurigana && it.second == s } -> {
                        //個人辞書の候補を先頭に追加
                        list1.remove(s)
                        list1.add(idx, s)
                        idx++
                    }
                    !list1.contains(s) -> { list1.add(s) } //送りがなブロックにマッチしない場合も、無ければ最後に追加
                }
            }
        }

        return list1.ifEmpty { null }
    }

    fun setCurrentCandidateToComposing() {
        val candList = mCandidatesList ?: return
        val number = Regex("\\d+").find(mKanjiKey)?.value ?: "#"
        val candidate = processConcatAndEscape(
            processNumber(
                removeAnnotation(candList[mCurrentCandidateIndex]),
                number
            )
        )
        setComposingTextSKK(katakana2hiragana(candidate + (mOkurigana ?: ""))!!, 1)
        // setComposingTextSKK はひらがなを期待している
    }

    internal fun pickCurrentCandidate(backspace: Boolean = false) {
        pickCandidate(mCurrentCandidateIndex, backspace)
    }

    private fun pickCandidate(index: Int, backspace: Boolean = false) {
        if (state !== SKKChooseState && state !== SKKNarrowingState) return
        val candList = mCandidatesList ?: return
        val candidate = StringBuilder(processConcatAndEscape(removeAnnotation(candList[index])))

        var number = "#"
        val kanjiKey = Regex("\\d+").find(mKanjiKey)?.let { d ->
            number = d.value
            Regex("#[0-3]").find(candidate)?.let {
                candidate.setRange(it.range.first, it.range.last + 1, processNumber(it.value, number))
            }
            mKanjiKey.replaceRange(d.range, "#")
        } ?: mKanjiKey

        if (isPersonalizedLearning) {
            mUserDict.addEntry(kanjiKey.toString(), candList[index], mOkurigana)
            // ユーザー辞書登録時はエスケープや注釈を消さない
        }

        val okuri = StringBuilder(
            (if (mService.isHiragana) mOkurigana else hirakana2katakana(mOkurigana)) ?: ""
        )
        if (backspace) {
            if (okuri.isNotEmpty()) okuri.deleteCharAt(okuri.lastIndex)
            else candidate.deleteCharAt(candidate.lastIndex)
        }
        commitTextSKK("${candidate}${okuri}", 1)
        if (mRegistrationStack.isEmpty()) {
            mLastConversion = ConversionInfo(
                "${candidate}${okuri}", candList, index, mKanjiKey.toString(), okuri.toString()
            )
        }

        changeState(kanaState)
    }

    private fun pickSuggestion(index: Int) {
        val number = Regex("\\d+").find(mKanjiKey)?.value ?: "#"
        val candList = mCandidatesList ?: return
        val s = candList[index].replaceFirst("#", number)

        when (state) {
            SKKAbbrevState -> {
                setComposingTextSKK(s, 1)
                mKanjiKey.setLength(0)
                mKanjiKey.append(s)
                conversionStart(mKanjiKey)
            }

            SKKKanjiState -> {
                val hira = if (mService.isHiragana) s else katakana2hiragana(s)!!
                setComposingTextSKK(hira, 1) // 向こうでカタカナにするので
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

            SKKASCIIState -> {
                if (isPersonalizedLearning) {
                    val maxFreq = findCandidates(s)!!
                        .plus("160")
                        .maxOf {
                            try {
                                it.toInt()
                            } catch (_: Exception) {
                                0
                            }
                        }
                    mASCIIDict.addEntry(s, maxFreq.toString(), mOkurigana)
                    mASCIIDict.commitChanges()
                }
                commitTextSKK(s.subSequence(getPrefixASCII().length, s.length), 1)
                deleteSuffixASCII()
                reset()
            }
        }
    }

    private fun reset() {
        mComposing.setLength(0)
        mKanjiKey.setLength(0)
        mOkurigana = null
        mCandidatesList = null
        mService.clearCandidatesView()
        mService.currentInputConnection.setComposingText("", 1)
        mComposingText.setLength(0)
        mCursorPosition = 1
    }

    internal fun changeInputMode(pcode: Int): Boolean {
        // 入力モード変更操作．変更したらtrue
        when (pcode) {
            'q'.code -> {
                changeState(if (mService.isHiragana) SKKKatakanaState else SKKHiraganaState)
                return true
            }
            'l'.code ->  {
                if (mComposing.length != 1 || mComposing[0] != 'z') {
                    changeState(SKKASCIIState)
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

    internal fun changeState(state: SKKState, force: Boolean = false) {
        val willBeTemporaryView = state in listOf(SKKAbbrevState, SKKZenkakuState)
        val wasTemporaryView = mService.isTemporaryView
        this.state = state

        val prevInputView = if (cameFromFlick) kanaState else SKKASCIIState
        if (!state.isTransient || force || willBeTemporaryView || wasTemporaryView) {
            reset()
            when {
                force || willBeTemporaryView -> changeSoftKeyboard(state)
                wasTemporaryView -> mService.changeSoftKeyboard(prevInputView) // cameFromFlick に記録しない
            }
            if (!mRegistrationStack.isEmpty()) setComposingTextSKK("", 1)
            // reset()で一旦消してるので， 登録中はここまで来てからComposingText復活
        }

        when (state) {
            SKKHiraganaState -> mService.isHiragana = true
            SKKKatakanaState -> mService.isHiragana = false
            SKKNarrowingState -> {
                SKKNarrowingState.mHint.setLength(0)
                SKKNarrowingState.mOriginalCandidates = null
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
            in listOf(SKKAbbrevState, SKKZenkakuState) -> cameFromFlick = mService.isFlickJP
            SKKASCIIState                              -> cameFromFlick = false
            SKKHiraganaState, SKKKatakanaState         -> cameFromFlick = true
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

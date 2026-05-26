package jp.deadend.noname.skk.engine

import android.text.SpannableString
import com.android.volley.toolbox.JsonArrayRequest
import com.android.volley.toolbox.Volley
import jp.deadend.noname.skk.SKKDictionaryInterface
import jp.deadend.noname.skk.SKKService
import jp.deadend.noname.skk.SKKUserDictionary
import jp.deadend.noname.skk.dLog
import jp.deadend.noname.skk.encodeKey
import jp.deadend.noname.skk.fuzzy
import jp.deadend.noname.skk.hankaku2zenkaku
import jp.deadend.noname.skk.hiragana2katakana
import jp.deadend.noname.skk.isAlphabet
import jp.deadend.noname.skk.katakana2hiragana
import jp.deadend.noname.skk.processConcatAndMore
import jp.deadend.noname.skk.removeAnnotation
import jp.deadend.noname.skk.skkPrefs
import jp.deadend.noname.skk.zenkaku2hankaku
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import java.util.ArrayDeque
import kotlin.time.measureTime

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

    fun handleKatakanaKey(): Boolean = true.also {
        changeState(if (kanaState is SKKHiraganaState) SKKKatakanaState else SKKHiraganaState)
    }

    fun handleASCIIKey(): Boolean {
        if (mComposing.length != 1 || mComposing[0] != 'z') {
            // ▽モード(KanjiState)では l で Abbrev モードに遷移（SKK 原本の動作）
            if (state is SKKKanjiState) {
                changeState(SKKAbbrevState)
            } else {
                changeState(SKKASCIIState, true)
            }
            return true
        }
        // 「→」を入力するための z+l 例外はそのまま維持
        return false
    }

    fun handleZenkakuKey(): Boolean = true.also { changeState(SKKZenkakuState) }

    fun tryStartAbbrev(): Boolean {
        if (mComposing.isEmpty()) {
            changeState(SKKAbbrevState)
            return true
        }
        return false
    }

    fun handleCtrlQ(): Boolean = true.also { changeState(SKKHanKanaState) }

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
        when {
            state.hasCandidates -> pickCandidate(mCurrentCandidateIndex)
            state.isTransient && state.canSuggest -> changeState(kanaState)
            else -> {
                if (mComposing.isEmpty()) {
                    if (!mRegistrationStack.isEmpty()) {
                        registerWord()
                    } else {
                        return false
                    }
                } else {
                    commitTextSKK(
                        if (state is SKKHanKanaState) zenkaku2hankaku(mComposing.toString()).orEmpty()
                        else mComposing
                    )
                    mComposing.setLength(0)
                }
            }
        }

        return true
    }

    fun handleBackspace(): Boolean {
        if (state.hasCandidates) {
            state.afterBackspace(this)
            return true
        }

        // 変換中のものがない場合
        if (mComposing.isEmpty() && mKanjiKey.isEmpty()) {
            if (state.isTransient && state.canSuggest) {
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

            state is SKKASCIIState -> mService.hideStatusIcon()
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
        pickSuggestion(mCurrentCandidateIndex, commit = false)
    }

    fun chooseAdjacentCandidate(isForward: Boolean) {
        val candidateList = mCandidateList ?: return

        if (isForward) {
            mCurrentCandidateIndex++
        } else {
            mCurrentCandidateIndex--
        }

        // 最初の候補より戻ると変換に戻る 最後の候補より進むと登録
        // 以前は NarrowingState で最後より進むと最初に戻っていたが ChooseState 同様にした
        if (mCurrentCandidateIndex > candidateList.size - 1) {
            if (mCandidateKanjiKey == "emoji" || mCandidateKanjiKey == "/きごう") {
                mCurrentCandidateIndex = 0
            } else {
                registerStart(mKanjiKey.toString())
                return
            }
        } else if (mCurrentCandidateIndex < 0) {
            if (mCandidateKanjiKey == "emoji" || mCandidateKanjiKey == "/きごう") {
                mCurrentCandidateIndex = candidateList.size - 1
            } else when (state) {
                is SKKChooseState -> {
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

                is SKKNarrowingState -> {
                    mCurrentCandidateIndex = candidateList.size - 1
                }
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
        when {
            state.hasCandidates ->
                pickCandidate(index, unregister = unregister)

            state.canSuggest ->
                pickSuggestion(index, unregister)

            else -> throw RuntimeException("cannot pick candidate in $state")
        }
    }

    fun prepareToMushroom(clip: String): String {
        val str = when {
            state is SKKASCIIState -> getPrefixASCII().ifEmpty { clip }
            state.canSuggest -> mKanjiKey.toString()
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
            state is SKKKanjiState && mComposing.isEmpty() && mKanjiKey.isNotEmpty() -> {
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

            state.hasCandidates && mComposing.isEmpty() -> {
                if (state is SKKNarrowingState && SKKNarrowingState.mHint.isNotEmpty()) {
                    val hint = SKKNarrowingState.mHint // ▼藹 hint: わ
                    val idx = hint.length - 1
                    if (type == LAST_CONVERSION_SHIFT) return
                    val newLastChar =
                        RomajiConverter.convertLastChar(hint.substring(idx), type).second
                    // この convertLastChar にも 2 文字が渡ることはない

                    hint.deleteCharAt(idx)
                    hint.append(newLastChar)
                    narrowCandidates(hint.toString())
                } else if (state is SKKChooseState) {
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
                            if (state is SKKHanKanaState) zenkaku2hankaku(newLastChar)
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
        val ct = mComposingText
        ct.setLength(0)

        if (mRegistrationStack.isNotEmpty()) {
            val depth = mRegistrationStack.size
            repeat(depth) { ct.append("[") }
            ct.append("登録")
            repeat(depth) { ct.append("]") }

            mRegistrationStack.peekFirst()?.let { regInfo ->
                val key =
                    if (kanaState is SKKHiraganaState) regInfo.key
                    else hiragana2katakana(regInfo.key).orEmpty()
                val okurigana =
                    if (kanaState is SKKHiraganaState) regInfo.okurigana
                    else hiragana2katakana(regInfo.okurigana).orEmpty()
                // 半角カナ変換はあとで
                if (okurigana.isEmpty()) {
                    ct.append(key)
                } else {
                    ct.append(key.dropLast(1))
                    ct.append("*")
                    ct.append(okurigana)
                }
                ct.append("：")
                ct.append(regInfo.entry) // ここはカナ変換しない
            }
        }

        state.prefix?.let { prefix ->
            if (skkPrefs.prefixMark) {
                if (!isPersonalizedLearning) {
                    ct.append("㊙")
                }
                ct.append(prefix)
            } else if (text.isEmpty()) {
                ct.append(" ")
            }
        }
        ct.append(
            if (mService.isHiragana) text else hiragana2katakana(
                text.toString(),
                reversed = true
            )
        )
        if (state is SKKNarrowingState) {
            ct.append(" hint: ", SKKNarrowingState.mHint, mComposing)
        }

        // 問題になったことはないが、念のため直前で参照する
        val ic = mService.currentInputConnection ?: return
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
        setCandidates(list, str, skkPrefs.candidatesNormalLines)
        setCurrentCandidateToComposing()
    }

    internal fun narrowCandidates(hint: String) {
        dLog("narrowCandidates: hint: $hint")
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
            setCandidates(
                narrowed,
                mCandidateKanjiKey,
                if (mCandidateKanjiKey == "emoji")
                    skkPrefs.candidatesEmojiLines
                else skkPrefs.candidatesNormalLines
            )
        }
        dLog("narrowCandidates: setCurrentCandidateToComposing: $mCandidateList")
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
            setCandidates(
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
        val oldComList = mCompletionList?.toList()
        val oldCanList = mCandidateList?.toList()
        mUpdateSuggestionsJob.cancel()
        mUpdateSuggestionsJob.invokeOnCompletion {
            mUpdateSuggestionsJob = MainScope().launch(Dispatchers.Default) {
                @Throws(CancellationException::class)
                fun ensureCont() {
                    if (oldComList != mCompletionList || oldCanList != mCandidateList)
                        throw CancellationException()
                    ensureActive()
                }

                if (str.isEmpty()) {
                    delay(150) // 短時間に連続で実行されると画面がチラつく
                    ensureCont()
                    withContext(Dispatchers.Main) { mService.clearCandidatesView() }
                    return@launch
                }

                val set = mutableSetOf<Pair<String, String>>()
                val elapsed = measureTime {
                    for (dict in mDictList) {
                        // 字数を増やさずに変換できるものを最優先
                        val fuzzyFurther = if (!skkPrefs.fuzzySuggestion) false
                        else withTimeoutOrNull(1000) {
                            // 重い処理: 価値があるので数は少なくてもいいがタイムアウトが必要
                            500 > measureTime {
                                val goal: Int = skkPrefs.candidatesNormalLines * 7 / str.length
                                for (fuzzyStr in fuzzy(str)) {
                                    if (set.size >= goal) break
                                    ensureCont()
                                    if (dict.getCandidates(fuzzyStr).isNullOrEmpty()) continue
                                    val shownStr =
                                        if (mService.isHiragana) fuzzyStr
                                        else hiragana2katakana(
                                            fuzzyStr,
                                            reversed = true
                                        ).orEmpty()
                                    set.add(fuzzyStr to shownStr)
                                }
                            }.inWholeMilliseconds || set.isEmpty()
                        } ?: set.isEmpty() // タイムアウトしても未発見なら前方一致あいまい検索してみる

                        // 前方一致は軽いので無条件で実行 (数字ありと数字マスク状態で)
                        addFound(this@launch, set, str, dict)

                        str.replace(Regex("\\d+(\\.\\d+)?"), "#").let {
                            if (it != str) addFound(this@launch, set, it, dict)

                            // あいまい前方一致は意味があまりないので数は多く、最後に短時間だけ
                            val goal: Int = skkPrefs.candidatesNormalLines * 10 / str.length
                            if (fuzzyFurther) withTimeoutOrNull(500) {
                                for (fuzzyStr in fuzzy(it)) {
                                    if (set.size > goal) break
                                    ensureCont()
                                    addFound(this@launch, set, fuzzyStr, dict)
                                }
                            }
                        }
                    }
                }
                delay(150 - elapsed.inWholeMilliseconds)
                ensureCont() // 短時間に連続で実行されないよう最新のみ有効に

                val uniqueSet = set.distinctBy { it.second }
                uniqueSet.unzip().let {
                    mCompletionList = it.first
                    mCandidateList = if (str == "emoji") it.second.map { s -> removeAnnotation(s) }
                    else it.second
                }

                mCandidateKanjiKey = str
                mCurrentCandidateIndex = 0
                withContext(Dispatchers.Main) {
                    setCandidates(
                        mCandidateList,
                        str,
                        if (str == "emoji") skkPrefs.candidatesEmojiLines
                        else skkPrefs.candidatesNormalLines
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
                if (key == "emoji" || state is SKKASCIIState) mASCIIDict else mUserDict
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
        } else dictionary.findKeys(scope, key).forEach { pair ->
            target.add(pair.first to hiragana2katakana(pair.second, reversed = true).orEmpty())
        }
    }

    internal fun updateSuggestionsASCII() {
        if (state !is SKKASCIIState) return
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
            (processConcatAndMore(regInfo.entry.toString(), regInfo.key) + regInfo.okurigana).let {
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
                        setCandidates(list, mKanjiKey.toString(), skkPrefs.candidatesNormalLines)
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
        mCompletionList = mCandidateList?.map { "/きごう" }
        mCurrentCandidateIndex = 0
        mCandidateKanjiKey = "/きごう"
        setCandidates(mCandidateList, "/きごう", skkPrefs.candidatesNormalLines)
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
            mOkurigana.isEmpty() || userEntry?.okuriganaBlocks?.any {
                it.first == katakana2hiragana(hankaku2zenkaku(mOkurigana)) && it.second == s
                // 送り仮名ブロックを直接使って変換するのではなく、この判定にだけ使っている
                // なので、送り仮名ブロックだけで存在していても無意味である
            } == true
        }

        val rawList: List<String> = mDictList.asSequence().mapNotNull { dict ->
            when (dict) {
                mUserDict -> userOkList

                mEmojiDict -> if (key == "えもじ") runBlocking {
                    mEmojiDict.findKeys(this, "").map { it.second }
                } else null

                else -> dict.getCandidates(key)
            }
        }.fold(listOf<String>()) { acc, list -> acc + list }
            .plus(userRestList) //送りがなブロックにマッチしない場合も、無ければ最後に追加

        // 注釈をマージする
        val shortList = rawList.distinctBy { removeAnnotation(it) }
        val dupList = rawList.subtract(shortList.toSet()).filter { it.contains(';') }
        val list = shortList.map { candidate ->
            val oldAnnotations = candidate.split(';').drop(1).toMutableSet()
            val newAnnotations = dupList.mapNotNull {
                if (removeAnnotation(it) == removeAnnotation(candidate)) {
                    val newSet = it.split(';').drop(1).subtract(oldAnnotations)
                    oldAnnotations.addAll(newSet)
                    newSet.joinToString(";")
                } else null
            }.joinToString(";")
            candidate + if (newAnnotations.isNotEmpty()) ";$newAnnotations" else ""
        }.toMutableList()

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

    internal fun setCandidates(list: List<String>?, key: String, lines: Int) =
        mService.setCandidates(list, key, lines)

    internal fun pickCurrentCandidate(backspace: Boolean = false, unregister: Boolean = false) {
        pickCandidate(mCurrentCandidateIndex, backspace, unregister)
    }

    private fun pickCandidate(index: Int, backspace: Boolean = false, unregister: Boolean = false) {
        if (!state.hasCandidates) return
        val candidateList = mCandidateList ?: return
        val rawCandidate = candidateList[index]
        val candidate = StringBuilder(getCandidate(index) ?: return)
        dLog("pickCandidate $candidate from $candidateList (unregister=$unregister)")
        suspendSuggestions()

        if (mCandidateKanjiKey == "emoji" || mCandidateKanjiKey == "/きごう") {
            if (isPersonalizedLearning || unregister) {
                val s = removeAnnotation(candidate.toString())
                var newEntry = "/160/$s"
                (mASCIIDict.getEntry(mCandidateKanjiKey)?.let { entry ->
                    entry.candidates.asSequence()
                        .zipWithNext()
                        .filterIndexed { i, _ -> i % 2 == 0 }
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
                        dLog("replaceEntry($mCandidateKanjiKey, $newEntry$it)")
                        mASCIIDict.replaceEntry(mCandidateKanjiKey, newEntry + it)
                    }
            }

            if (unregister) {
                val confirm = state as SKKConfirmingState
                confirm.confirmUnregister(this, "/${removeAnnotation(rawCandidate)}/") {
                    reset()
                    changeState(oldState)
                }
                resumeSuggestions()
                return
            }

            commitTextSKK(removeAnnotation(candidate.toString()))
            resumeSuggestions()
            if (state.isSequential) return
            reset()
            changeState(oldState)
            return
        }

        if (unregister) {
            val unannotated = removeAnnotation(rawCandidate)
            (state as SKKConfirmingState).confirmUnregister(
                this,
                "/$unannotated/${
                    if (mOkurigana.isNotEmpty()) "[$mOkurigana/$unannotated/]/" else ""
                }"
            ) {
                mUserDict.removeEntry(mKanjiKey.toString(), rawCandidate, mOkurigana)
                reset()
                changeState(kanaState)
            }
            resumeSuggestions()
            return
        }

        if (isPersonalizedLearning) {
            mUserDict.addEntry(mKanjiKey.toString(), rawCandidate, mOkurigana)
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
        resumeSuggestions()

        if (state.isSequential) return
        reset()
        changeState(kanaState)
    }

    private fun pickSuggestion(index: Int, unregister: Boolean = false, commit: Boolean = true) {
        var number = Regex("\\d+(\\.\\d+)?").find(mKanjiKey)
        val rawSuggestion = mCandidateList?.get(index) ?: return
        val s = rawSuggestion.map { ch ->
            if (ch != '#' || number == null) ch.toString() else {
                number.let {
                    number = it.next()
                    it.value
                }
            }
        }.joinToString("")
        dLog("pickSuggestion s=$s from $mCandidateList (unregister=$unregister)")
        val c = mCompletionList?.get(index) ?: return
        dLog("pickSuggestion c=$c from $mCompletionList")
        // c を入力して s になるイメージなので基本的に両者は同じだが
        // c が ill で s が I'll になるような、入力した文字まで変化する場合がある

        when (state) {
            SKKAbbrevState -> {
                setComposingTextSKK(s)
                mKanjiKey.setLength(0)
                mKanjiKey.append(s)
                if (commit) conversionStart(mKanjiKey)
            }

            SKKKanjiState, SKKOkuriganaState -> {
                val hira = if (kanaState is SKKHiraganaState) s else katakana2hiragana(s).orEmpty()
                val last = hira.lastOrNull() ?: ' '
                val hasOkuri = hira.isNotEmpty() &&
                        !isAlphabet(hira.first().code) && isAlphabet(last.code)
                val kanjiKey = if (hasOkuri) hira.dropLast(1) else hira
                mKanjiKey.setLength(0)
                mKanjiKey.append(kanjiKey)
                mComposing.setLength(0)
                if (commit) {
                    if (hasOkuri) {
                        processKey(encodeKey(last.uppercaseChar().code))
                    } else {
                        conversionStart(mKanjiKey)
                    }
                } else {
                    // ハードウェアキーボードで Tab を押しただけなら送り仮名で conversionStart しない
                    val composing = if (hasOkuri && last !in "aiueo") {
                        mComposing.append(last) // 子音は入力したことにして、次の母音を大文字で入力させる
                        kanjiKey + last
                    } else kanjiKey
                    setComposingTextSKK(composing)
                }
            }

            is SKKASCIIState -> {
                if (!commit) return
                if (isPersonalizedLearning || unregister) {
                    val lambda = {
                        var newEntry = "/160/$s"
                        (mASCIIDict.getEntry(c)?.let { entry ->
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
                                dLog("replaceEntry($c, $newEntry$it)")
                                mASCIIDict.replaceEntry(c, newEntry + it)
                            }
                    }
                    if (unregister) {
                        val confirm = state as SKKConfirmingState
                        confirm.confirmUnregister(this, "/${removeAnnotation(rawSuggestion)}/") {
                            lambda()
                            reset()
                            changeState(oldState)
                        }
                        return
                    }
                    lambda()
                }

                val slim = removeAnnotation(s)
                commitTextSKK(slim) // アプリ側で補完表示していることがあるのでまず上書きしておく
                deleteSurroundingASCII(slim)
                reset()
            }
        }
    }

    private fun deleteSurroundingASCII(text: String): Boolean {
        val ic = mService.currentInputConnection ?: return false
        val delimiter = Regex("[^\\p{L}&&[^\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}]]")
        ic.beginBatchEdit()
        try {
            val tbc = ic.getTextBeforeCursor(text.length * 2, 0) ?: throw Exception()
            val wbc = tbc.dropLast(text.length).split(delimiter).last()
            if (ic.deleteSurroundingText(wbc.length + text.length, 0) &&
                ic.commitText(text, 1)
            ) dLog("deleted already-typed [$wbc] before [$text]")
            else throw Exception()

            val tac = ic.getTextAfterCursor(ASCII_WORD_MAX_LENGTH, 0) ?: throw Exception()
            val wac = tac.split(delimiter, limit = 2).first()
            if (!ic.deleteSurroundingText(0, wac.length)) throw Exception()
            ic.endBatchEdit()
            return true
        } catch (_: Exception) {
            dLog("connection lost while trimming around '$text'")
            ic.endBatchEdit()
            return false
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

    // 入力モード変更操作．変更したらtrue
    internal fun changeInputMode(keyCode: Int): Boolean = when (keyCode) {
        skkPrefs.katakanaKey -> handleKatakanaKey()
        skkPrefs.hankakuKanaKey -> handleCtrlQ()
        skkPrefs.asciiKey -> handleASCIIKey()
        skkPrefs.zenkakuKey -> handleZenkakuKey()
        skkPrefs.abbrevKey -> tryStartAbbrev()
        else -> false
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
        val willBeTemporaryView = state is SKKAbbrevState || state is SKKZenkakuState
        val wasTemporaryView = mService.isTemporaryView
        val inCompatibleStates = (this.state.isTransient && this.state.canSuggest &&
                state.isTransient && state.canSuggest)

        if (this.state !is SKKEmojiState)
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

            is SKKNarrowingState -> {
                SKKNarrowingState.mHint.setLength(0)
                SKKNarrowingState.mOriginalCandidates = null
                SKKNarrowingState.mSpaceUsed = false
                SKKNarrowingState.isSequential = false
                setCurrentCandidateToComposing()
            }
        }
        if (state.icon != 0) {
            mService.showStatusIcon(state.icon)
        } else if (state is SKKASCIIState) {
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

package jp.deadend.noname.skk.engine

import android.text.SpannableString
import android.view.KeyEvent
import com.android.volley.toolbox.JsonArrayRequest
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import jp.deadend.noname.skk.SKKDictionaryInterface
import jp.deadend.noname.skk.SKKLog
import jp.deadend.noname.skk.SKKService
import jp.deadend.noname.skk.SKKUserDictionary
import jp.deadend.noname.skk.encodeKey
import jp.deadend.noname.skk.hankaku2zenkaku
import jp.deadend.noname.skk.hiragana2katakana
import jp.deadend.noname.skk.isAlphabet
import jp.deadend.noname.skk.katakana2hiragana
import jp.deadend.noname.skk.removeAnnotation
import jp.deadend.noname.skk.skkPrefs
import jp.deadend.noname.skk.zenkaku2hankaku
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONException

class SKKEngine(
    private val mService: SKKService,
    internal var mDictList: List<SKKDictionaryInterface>,
    internal val mUserDict: SKKUserDictionary,
    internal val mASCIIDict: SKKUserDictionary,
    internal val mEmojiDict: SKKUserDictionary
) {
    internal var state: SKKState = SKKHiraganaState
        private set
    internal var kanaState: SKKState = SKKHiraganaState
    internal var oldState: SKKState = SKKHiraganaState
    private var cameFromFlick: Boolean = skkPrefs.preferFlick

    internal val mCandidates = SKKCandidates(this, mService)
    internal val mRegister = SKKRegister(this)

    // ひらがなや英単語などの入力途中
    internal val mComposing = StringBuilder()

    // 漢字変換のキー 送りありの場合最後がアルファベット 変換中は不変
    internal val mKanjiKey = KanjiKey()

    @Suppress("JavaDefaultMethodsNotOverriddenByDelegation")
    internal class KanjiKey(val entry: StringBuilder = StringBuilder()) : CharSequence by entry {
        var cursor = 0

        fun clear(): StringBuilder = entry.apply { setLength(0) }.also { cursor = 0 }
        fun append(c: Char): StringBuilder = entry.append(c).also { cursor = entry.length }
        fun append(s: String): StringBuilder = entry.append(s).also { cursor = entry.length }
        fun set(s: String): StringBuilder = clear().append(s).also { cursor = entry.length }

        fun insertAtCursor(s: String): StringBuilder =
            entry.insert(cursor, s).also { cursor += s.length }

        fun deleteAtCursor(): StringBuilder =
            if (cursor > 0) entry.deleteCharAt(--cursor) else entry

        fun deleteAfterCursor(): StringBuilder = entry.delete(cursor, entry.length)

        fun deleteLast(): StringBuilder =
            (if (entry.isNotEmpty()) entry.deleteCharAt(entry.lastIndex) else entry)
                .also { cursor = entry.length }

        override fun toString() = entry.toString()
    }

    // 送りがな 「っ」や「ん」が含まれる場合だけ二文字になる
    internal var mOkurigana = ""

    // 実際に表示されているもの
    internal val mComposingText = StringBuilder()

    // 全角で入力する記号リスト
    private val mZenkakuSeparatorMap = mutableMapOf(
        "-" to "ー", "!" to "！", "?" to "？", "~" to "〜",
        "[" to "「", "]" to "」", "(" to "（", ")" to "）"
    )

    // 再変換のための情報
    internal class ConversionInfo(
        val candidate: String,
        val list: List<String>,
        val index: Int,
        val kanjiKey: String,
        val okurigana: String
    )

    internal var mLastConversion: ConversionInfo? = null

    internal var isPersonalizedLearning = true
    internal val ic get() = mService.currentInputConnection

    init {
        setZenkakuPunctuationMarks("en")
    }

    internal fun reopenDictionaries(dictList: List<SKKDictionaryInterface>) {
        mCandidates.reset()
        for (dict in mDictList - dictList.toSet()) {
            dict.close()
        }
        mDictList = dictList
    }

    internal fun setZenkakuPunctuationMarks(type: String) {
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

    internal fun close() {
        for (dict in mDictList) {
            dict.close()
            if (dict == mUserDict) {
                mASCIIDict.close()
            } // ASCII は mDictList に入れていない
        }
    }

    internal fun processKey(keyCode: Int) =
        state.processKey(this, keyCode)

    internal fun handleKanaKey() =
        state.handleKanaKey(this)

    private fun handleKatakanaKey(): Boolean = true.also {
        changeState(
            if (kanaState is SKKHiraganaState) SKKKatakanaState
            else SKKHiraganaState
        )
    }

    internal fun handleASCIIKey(): Boolean {
        if (mComposing.length != 1 || mComposing[0] != 'z') {
            // ▽モード(PreeditState)では l で Abbrev モードに遷移（SKK 原本の動作）
            if (state is SKKPreeditState) {
                changeState(SKKAbbrevState)
            } else {
                changeState(SKKASCIIState, true)
            }
            return true
        }
        // 「→」を入力するための z+l 例外はそのまま維持
        return false
    }

    private fun handleZenkakuKey(): Boolean = true.also {
        changeState(SKKZenkakuState)
    }

    private fun handleAbbrevKey(): Boolean {
        if (mComposing.isEmpty()) {
            changeState(SKKAbbrevState)
            return true
        }
        return false
    }

    private fun handleHankakuKanaKey(): Boolean = true.also {
        changeState(SKKHanKanaState)
    }

    internal fun handleEnter(): Boolean {
        when {
            state.hasCandidates -> mCandidates.pickCandidate(mCandidates.mIndex)
            state.isTransient && state.canComplete -> changeState(kanaState)
            else -> {
                if (mComposing.isEmpty()) {
                    if (mRegister.isOngoing) {
                        mRegister.finish()
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

    internal fun handleBackspace(): Boolean {
        if (state.hasCandidates) {
            state.afterBackspace(this)
            return true
        }

        // 変換中のものがない場合
        if (mComposing.isEmpty() && mKanjiKey.isEmpty()) {
            if (state.isTransient && state.canComplete) {
                changeState(kanaState)
                return true
            }
            val (regInfo, firstEntry) = mRegister.first() ?: return state.isTransient
            if (firstEntry.length >= regInfo.cursor && regInfo.cursor > 0) {
                firstEntry.deleteCharAt(--regInfo.cursor)
                setComposingTextSKK("")
                return true
            } // else 何もしない
        }

        if (mComposing.isNotEmpty()) {
            mComposing.deleteCharAt(mComposing.lastIndex)
        } else if (mKanjiKey.isNotEmpty()) {
            mKanjiKey.deleteAtCursor()
        }
        state.afterBackspace(this)

        return true
    }

    internal fun handleCancel(reconvert: Boolean = true): Boolean =
        ic != null && state.handleCancel(this, reconvert)

    /**
     * commitTextのラッパー 登録作業中なら登録内容に追加し，表示を更新
     * @param text
     */
    internal fun commitTextSKK(text: CharSequence) {
        SKKLog.d("commit text='$text' registering=${mRegister.isOngoing}")
        ic ?: return
        if (!mRegister.isOngoing) {
            ic.commitText(text, 1)
            mComposingText.setLength(0)
            mKanjiKey.clear()
            mComposing.setLength(0)
            mOkurigana = ""
            return
        }
        val (regInfo, firstEntry) = mRegister.first()!!
        firstEntry.insert(regInfo.cursor, text)
        regInfo.cursor += text.length
        setComposingTextSKK("")
    }

    internal fun resetOnStartInput() {
        mComposing.setLength(0)
        mKanjiKey.clear()
        mOkurigana = ""
        mCandidates.reset()
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

    internal fun moveCandidateCursor(isForward: Boolean) =
        mCandidates.moveCandidateCursor(isForward)

    internal fun handleDpad(keyCode: Int): Boolean {
        when {
            state.hasCandidates -> when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> moveCandidateCursor(false)
                KeyEvent.KEYCODE_DPAD_RIGHT -> moveCandidateCursor(true)
                else -> return false
            }

            mRegister.isOngoing -> mRegister.handleDpad(keyCode)
            state.isTransient -> handleDpadTransient(keyCode)
            else -> return false
        }
        return true
    }

    private fun handleDpadTransient(keyCode: Int): Boolean {
        val oldCursor = mKanjiKey.cursor
        mKanjiKey.cursor = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> mKanjiKey.cursor.dec().coerceAtLeast(0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> mKanjiKey.cursor.inc().coerceAtMost(mKanjiKey.length)
            else -> return false
        }
        if (mKanjiKey.cursor == oldCursor) {
            mCandidates.cycleCompletionCursor(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) mKanjiKey.cursor = 0
        } else SKKLog.d(
            "mKanjiKey: " + mKanjiKey.take(mKanjiKey.cursor) +
                    " | " + mKanjiKey.drop(mKanjiKey.cursor)
        )
        setComposingTextSKK()
        return true
    }

    internal fun pickCandidatesViewManually(
        index: Int,
        unregister: Boolean = false,
        sequential: Boolean = false
    ) {
        SKKLog.d("pickCandidatesViewManually(index=$index, unregister=$unregister, sequential=$sequential)")
        val state = state
        if (state is SKKConfirmingState && state.pendingLambda != null) {
            state.beforeProcessKey(this, encodeKey('y'.code))
            return
        }
        when {
            state.hasCandidates -> {
                state.isSequential = sequential
                mCandidates.pickCandidate(index, unregister = unregister)
            }

            state.canComplete -> {
                state.isSequential = sequential
                mCandidates.pickCompletion(index, unregister)
            }

            else -> throw RuntimeException("cannot pick candidate in $state")
        }
    }

    internal fun prepareToMushroom(clip: String): String {
        val str = when {
            state is SKKASCIIState -> (state as SKKASCIIState).getPrefix(this).ifEmpty { clip }
            state.canComplete -> mKanjiKey.toString()
            else -> clip
        }

        if (state.isTransient) {
            changeState(kanaState)
        } else {
            reset()
            mRegister.mStack.clear()
        }

        return str
    }

    // 小文字大文字変換，濁音，半濁音に使う
    internal fun changeLastChar(type: String) {
        when {
            state is SKKPreeditState && mComposing.isEmpty() && mKanjiKey.cursor > 0 -> {
                val s = mKanjiKey.toString() // ▽あい
                if (mKanjiKey.cursor < 2 && type == LAST_CONVERSION_SHIFT) return // ▽あ
                val newLastChar = RomajiConverter.convertLastChar(
                    s.substring(mKanjiKey.cursor - 1, mKanjiKey.cursor), type
                ).second
                // この convertLastChar に 2 文字が渡ることはない

                mKanjiKey.deleteAtCursor()
                if (type == LAST_CONVERSION_SHIFT) {
                    mKanjiKey.deleteAfterCursor()
                    mKanjiKey.append(RomajiConverter.getConsonantForVoiced(newLastChar))
                    mOkurigana = newLastChar
                    startConversion() // ▼合い
                } else {
                    mKanjiKey.insertAtCursor(newLastChar)
                    setComposingTextSKK()
                    complete(mKanjiKey.toString())
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
                    mCandidates.narrow(hint.toString())
                } else if (state is SKKChooseState) {
                    if (mOkurigana.isEmpty()) return
                    val okurigana = mOkurigana // ▼合い (okurigana = い)
                    val newOkurigana = RomajiConverter.convertLastChar(okurigana, type).second

                    if (type == LAST_CONVERSION_SHIFT) {
                        handleCancel() // ▽あ (mOkurigana = null)
                        mKanjiKey.insertAtCursor(newOkurigana) // ▽あい
                        setComposingTextSKK()
                        complete(mKanjiKey.toString())
                        return
                    }
                    // 例外: 送りがなが「っ」になる場合は，どのみち必ずt段の音なのでmKanjiKeyはそのまま
                    // 「ゃゅょ」で送りがなが始まる場合はないはず
                    if (type != LAST_CONVERSION_SMALL) {
                        mKanjiKey.deleteAtCursor()
                        mKanjiKey.append(RomajiConverter.getConsonantForVoiced(newOkurigana))
                    }
                    mOkurigana = newOkurigana
                    startConversion() //変換やりなおし
                }
            }

            mComposing.isEmpty() && mKanjiKey.isEmpty() -> {
                ic ?: return
                val cs = ic.getTextBeforeCursor(2, 0) ?: return
                // 0〜2 文字を 0〜3 文字にするので注意!
                val newLast2Chars = RomajiConverter.convertLastChar(cs.toString(), type)
                SKKLog.d("changeLastChar: 2chars=$newLast2Chars")
                if (newLast2Chars.first.isEmpty() && newLast2Chars.second.isEmpty()) return

                val deleteTwo = (cs.length == 2 && newLast2Chars.first.isEmpty())
                val newLastChar = newLast2Chars.second

                if (mRegister.isOngoing) {
                    val (regInfo, firstEntry) = mRegister.first()!!
                    if (regInfo.cursor > if (deleteTwo) 1 else 0) return
                    firstEntry.deleteCharAt(regInfo.cursor--)
                    if (deleteTwo) {
                        firstEntry.deleteCharAt(regInfo.cursor--)
                    }
                    if (type == LAST_CONVERSION_SHIFT) {
                        mKanjiKey.append(katakana2hiragana(newLastChar).orEmpty())
                        changeState(SKKPreeditState)
                        setComposingTextSKK()
                        complete(mKanjiKey.toString())
                    } else {
                        firstEntry.insert(regInfo.cursor, newLastChar)
                        regInfo.cursor += newLastChar.length
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
                        mKanjiKey.append(katakana2hiragana(newLastChar).orEmpty())
                        changeState(SKKPreeditState) // Abbrevから来ることはないはず
                        setComposingTextSKK()
                        complete(mKanjiKey.toString())
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

    internal fun getZenkakuSeparator(key: String) =
        mZenkakuSeparatorMap[key]

    /**
     * setComposingTextのラッパー 変換モードマーク等を追加する
     * @param text
     */
    internal fun setComposingTextSKK(text: CharSequence? = null) {
        val ct = mComposingText
        ct.setLength(0)

        val suffix = if (mRegister.isOngoing) {
            val depth = mRegister.mStack.size
            repeat(depth) { ct.append("[") }
            ct.append("登録")
            repeat(depth) { ct.append("]") }

            val (regInfo, entry) = mRegister.first()!!
            val key =
                if (kanaState is SKKHiraganaState) regInfo.key
                else hiragana2katakana(regInfo.key)
            val okurigana =
                if (kanaState is SKKHiraganaState) regInfo.okurigana
                else hiragana2katakana(regInfo.okurigana)
            // 半角カナ変換はあとで
            if (okurigana.isEmpty()) {
                ct.append(key)
            } else {
                ct.append(key.dropLast(1), "*", okurigana)
            }
            // ここはカナ変換しない
            ct.append("：", entry.take(regInfo.cursor))
            entry.drop(regInfo.cursor)
        } else ""
        if (suffix.isNotEmpty()) {
            ic.setSelection(0, 0)
            ct.append("[")
        }

        state.prefix?.let { prefix ->
            if (skkPrefs.prefixMark) {
                if (!isPersonalizedLearning) {
                    ct.append("㊙")
                }
                ct.append(prefix)
            } else if (text?.isEmpty() ?: mKanjiKey.isEmpty()) {
                ct.append(" ")
            }
        }

        val textToProcess = text ?: (if (mKanjiKey.cursor == mKanjiKey.length) {
            "${mKanjiKey}${mComposing}"
        } else {
            "${mKanjiKey.take(mKanjiKey.cursor)}[${mComposing}]${mKanjiKey.drop(mKanjiKey.cursor)}"
        })

        ct.append(
            if (mService.isHiragana) textToProcess else hiragana2katakana(
                textToProcess.toString(),
                reversed = true
            )
        )
        if (state is SKKNarrowingState) {
            ct.append(" hint: ", SKKNarrowingState.mHint, mComposing)
        }

        if (suffix.isNotEmpty()) {
            ct.append("]$suffix") // pseudo caret
        }

        // SpannableStringBuilder SPAN_EXCLUSIVE_EXCLUSIVE spans cannot have a zero length
        // というエラーを防ぐため最初から SpannableString にする
        ic?.setComposingText(ct.ifEmpty { SpannableString("") }, 1)
    }

    /***
     * mKanjiKey で変換スタート
     * 送りありの場合，事前に送りがなをmOkuriganaにセットしておく
     */
    internal fun startConversion() {
        val key = mKanjiKey.entry
        if (key.isEmpty()) {
            changeState(kanaState) // ASCIIには戻れない…
            return
        }
        val str = key.toString()

        changeState(SKKChooseState)

        val list = find(str).ifEmpty {
            find(str.replace(Regex("\\d+(\\.\\d+)?"), "#")).ifEmpty {
                mRegister.start(str)
                return
            }
        }

        mCandidates.apply {
            mList = list
            mIndex = 0
            mQuery = str
            setView(list, mQuery, mIndex)
            updateComposingText()
        }
    }

    internal fun reConvert(): Boolean {
        val lastConv = mLastConversion ?: return false
        SKKLog.d("last conversion: ${lastConv.candidate} from ${lastConv.list} at ${lastConv.index} (key=${lastConv.kanjiKey}, okuri=${lastConv.okurigana})")

        if (mService.prepareReConversion(lastConv.candidate)) {
            runBlocking(Dispatchers.IO) { mUserDict.rollBack() }

            changeState(SKKChooseState)

            mComposing.setLength(0)
            mKanjiKey.set(lastConv.kanjiKey)
            mOkurigana = lastConv.okurigana
            mCandidates.apply {
                mList = lastConv.list
                mIndex = lastConv.index
                mQuery = lastConv.kanjiKey
                setView(mList, mQuery, mIndex)
                updateComposingText()
            }

            return true
        }

        return false
    }

    internal fun complete(str: String) =
        mCandidates.complete(str)

    internal fun completeASCII() =
        mCandidates.completeASCII()

    private val volleyQueue by lazy { Volley.newRequestQueue(mService.applicationContext) }
    internal fun googleTransliterate() {
        if (!mRegister.isOngoing) {
            if (state is SKKASCIIState) return googleTranslate()
            if (mKanjiKey.isEmpty()) return
        } else {
            // candidate から選択しただけで登録されるので
            val regInfo = mRegister.mStack.removeFirst() ?: return
            mComposing.setLength(0)
            mKanjiKey.set(regInfo.key)
            mOkurigana = regInfo.okurigana
        }
        SKKLog.d("googleTransliterate mKanjiKey=${mKanjiKey} mOkurigana=${mOkurigana}")

        changeState(SKKPreeditState)
        val query = if (mKanjiKey.isNotEmpty() && isAlphabet(mKanjiKey.last().code)) {
            val trimmedKanjiKey = mKanjiKey.dropLast(1)
            setComposingTextSKK("${trimmedKanjiKey}*${mOkurigana}")
            "${trimmedKanjiKey}${mOkurigana}"
        } else {
            setComposingTextSKK()
            mKanjiKey.toString() // たぶん送り仮名は存在しないはず
        }
        volleyQueue.add(
            JsonArrayRequest(
                "https://www.google.com/transliterate?langpair=ja-Hira|ja&text=${query},",
                { response ->
                    SKKLog.d(" googleTransliterate response=${response.toString(4)}")
                    val list = try {
                        val jsonArray = response.optJSONArray(0)
                            ?.optJSONArray(1) ?: throw JSONException("no array")
                        (0 until jsonArray.length()).map { index ->
                            val item = jsonArray.getString(index)
                            if (mOkurigana.isNotEmpty()) item.removeSuffix(mOkurigana) else item
                        }
                    } catch (e: JSONException) {
                        SKKLog.w(" googleTransliterate JSON error", e)
                        listOf("(エラー)", hiragana2katakana(mKanjiKey.toString()))
                    }
                    changeState(SKKChooseState)
                    mCandidates.apply {
                        mList = list
                        mIndex = 0
                        mQuery = mKanjiKey.toString()
                        setView(mList, mQuery, mIndex)
                        updateComposingText()
                    }
                },
                { e -> SKKLog.w(" googleTransliterate API error", e) }
            )
        )
    }

    private fun googleTranslate() {
        val query = state.let { ascii ->
            if (ascii !is SKKASCIIState) return
            ascii.getPrefix(this) + ascii.getSuffix(this) // Uri.encode() すべき?
        }
        SKKLog.d("googleTranslate query=$query")
        if (query.isEmpty()) return

        volleyQueue.add(
            object : JsonObjectRequest(
                "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=ja&dt=bd&dj=1&q=${query}",
                { response ->
                    SKKLog.d(" googleTranslate response=${response.toString(4)}")
                    val list = try {
                        val jsonArray = response.optJSONArray("dict")?.optJSONObject(0)
                            ?.optJSONArray("entry") ?: throw JSONException("no entry")
                        (0 until jsonArray.length()).map { index ->
                            val item = jsonArray.getJSONObject(index)
                            val word = item.getString("word")
                            word + item.optJSONArray("reverse_translation")?.let { synonyms ->
                                val annotation = (0 until synonyms.length()).mapNotNull { i ->
                                    synonyms.getString(i).takeIf { it != query }
                                }.joinToString()
                                if (annotation.isNotEmpty()) "; $annotation" else ""
                            }.orEmpty()
                        }
                    } catch (e: JSONException) {
                        SKKLog.w(" googleTranslate JSON error", e)
                        listOf("(エラー)", query)
                    }
                    mCandidates.apply {
                        mCompletionList = list.map { query }
                        mList = list
                        mIndex = 0
                        mQuery = query
                        setView(mList, mQuery, mIndex)
                    }
                },
                { e -> SKKLog.w(" googleTranslate API error", e) }
            ) {
                // User-Agent を設定しないと 429 エラーになる
                override fun getHeaders() = mutableMapOf("User-Agent" to "Volley/1.2.1")
            }
        )
    }

    internal fun symbolCandidates() {
        changeState(SKKEmojiState)
        val set = mutableSetOf<Pair<String, String>>()
        runBlocking {
            mCandidates.addFound(this, set, "/きごう", mASCIIDict)
        }
        mCandidates.apply {
            mList = set.map { it.second } +
                    "\"#$%&'()=^~¥|@`[{;+*]},<.>\\_←↓↑→“”‘’『』【】！＂＃＄％＆＇（）－＝＾～￥｜＠｀［｛；＋：＊］｝，＜．＞／？＼＿、。"
                        .toCharArray()
                        .map { it.toString() }
            mCompletionList = mList?.map { "/きごう" }
            mIndex = 0
            mQuery = "/きごう"
            setView(mList, mQuery, mIndex)
        }
    }

    internal fun emojiCandidates() {
        changeState(SKKEmojiState)
        complete("emoji")
    }

    internal fun find(key: String): List<String> = runBlocking(Dispatchers.IO) {
        val userEntry = mUserDict.getEntry(key)
        SKKLog.d("user dictionary: $key -> ${userEntry?.candidates} with ${userEntry?.okuriganaBlocks}")
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

        if (list.isEmpty()) SKKLog.d("Dictionary: Can't find Kanji for $key")

        list
    }

    // いわゆる暗黙の確定
    internal fun pickCurrentCandidate(backspace: Boolean = false, unregister: Boolean = false) =
        mCandidates.pickCandidate(mCandidates.mIndex, backspace, unregister)

    internal fun reset() {
        mComposing.setLength(0)
        mKanjiKey.clear()
        mOkurigana = ""
        mCandidates.reset()
        mService.clearCandidatesView()
        if (ic?.getSelectedText(0).isNullOrEmpty()) {
            ic?.setComposingText(SpannableString(""), 1)
            // SpannableStringしないと BaseInputConnection の replaceTextInternal() で
            // SpannableStringBuilder SPAN_EXCLUSIVE_EXCLUSIVE spans cannot have a zero length
            // というエラーを出してしまう
        }
        mComposingText.setLength(0)
    }

    // 入力モード変更操作．変更したらtrue
    internal fun changeInputMode(keyCode: Int): Boolean = when (keyCode) {
        skkPrefs.katakanaKey -> handleKatakanaKey()
        skkPrefs.hankakuKanaKey -> handleHankakuKanaKey()
        skkPrefs.asciiKey -> handleASCIIKey()
        skkPrefs.zenkakuKey -> handleZenkakuKey()
        skkPrefs.abbrevKey -> handleAbbrevKey()
        else -> false
    }

    internal fun commitComposing() {
        if (mKanjiKey.isNotEmpty()) {
            if (!isAlphabet(mKanjiKey.first().code) && isAlphabet(mKanjiKey.last().code)) {
                mKanjiKey.deleteLast()
            }
            commitTextSKK(
                when (kanaState) {
                    SKKKatakanaState ->
                        hiragana2katakana(mKanjiKey.toString())

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
        SKKLog.d("changeState ${this.state.name} -> ${state.name} (force=$force)")
        val willBeTemporaryView = state is SKKAbbrevState || state is SKKZenkakuState
        val wasTemporaryView = mService.isTemporaryView
        val inCompatibleStates = (this.state.isTransient && this.state.canComplete &&
                state.isTransient && state.canComplete)

        if (this.state !is SKKEmojiState)
            oldState = this.state
        else mKanjiKey.clear() // mKanjiKey=="emoji"は基本的に無意味なので

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
            if (mRegister.isOngoing) setComposingTextSKK("")
            // reset()で一旦消してるので， 登録中はここまで来てからComposingText復活
        }

        when (state) {
            SKKHiraganaState, SKKKatakanaState, SKKHanKanaState ->
                mService.kanaState = state

            is SKKNarrowingState -> {
                state.apply {
                    mHint.setLength(0)
                    mOriginalCandidates = null
                    mSpaceUsed = false
                    isSequential = false
                    isASCII = false
                }
                mCandidates.updateComposingText()
            }
        }
        if (state.icon != 0) {
            mService.showStatusIcon(state.icon)
        } else if (state is SKKASCIIState) {
            mService.hideStatusIcon()
        }
    }

    internal fun changeSoftKeyboard(state: SKKState) {
        // 仮名からASCII以外の一時的なキーボードになるときや明示的変更のとき記録して後で戻れるようにしておく
        when (state) {
            SKKAbbrevState, SKKZenkakuState -> cameFromFlick = mService.isFlickWidth()
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

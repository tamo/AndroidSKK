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
import jp.deadend.noname.skk.getKeyName
import jp.deadend.noname.skk.hankaku2zenkaku
import jp.deadend.noname.skk.hiragana2katakana
import jp.deadend.noname.skk.isAlphabet
import jp.deadend.noname.skk.katakana2hiragana
import jp.deadend.noname.skk.lowerCode
import jp.deadend.noname.skk.removeAnnotation
import jp.deadend.noname.skk.skkPrefs
import jp.deadend.noname.skk.zenkaku2hankaku
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONException

fun String.convertTo(state: SKKState, reversed: Boolean = false): String = when (state) {
    SKKHiraganaState -> if (reversed) this else katakana2hiragana(this)
    SKKKatakanaState -> hiragana2katakana(this, reversed = reversed)
    SKKHanKanaState -> zenkaku2hankaku(hiragana2katakana(this))
    else -> this
}

class SKKEngine(
    private val mService: SKKService,
    internal var mDictList: List<SKKDictionaryInterface>,
    internal val mUserDict: SKKUserDictionary,
    internal val mASCIIDict: SKKUserDictionary,
    internal val mEmojiDict: SKKUserDictionary,
    internal val mSymbolDict: SKKUserDictionary
) {
    internal var state: SKKState = SKKHiraganaState
        private set
    internal var kanaState: SKKState = SKKHiraganaState
    internal var oldState: SKKState = SKKHiraganaState

    internal val mCandidates = SKKCandidates(this, mService)
    internal val mRegister = SKKRegister(this)

    // ひらがなや英単語などの入力途中
    internal val mRoman = StringBuilder()

    // 漢字変換のキー 送りありの場合最後がアルファベット 変換中は不変
    internal val mKanjiKey = KanjiKey()

    @Suppress("JavaDefaultMethodsNotOverriddenByDelegation")
    internal class KanjiKey(val entry: StringBuilder = StringBuilder()) : CharSequence by entry {
        var cursor = 0
        var roman: Char? = null

        fun clear(): StringBuilder = entry.clear().also { cursor = 0; roman = null }
        fun append(c: Char): StringBuilder = entry.append(c).also { cursor = entry.length }
        fun append(s: String): StringBuilder = entry.append(s).also { cursor = entry.length }
        fun set(s: String): StringBuilder = clear().append(s).also {
            if (it.length > 1 && !isAlphabet(it.first().code)) {
                val last = it.last()
                if (isAlphabet(last.code) || last == '>') {
                    roman = last
                    it.deleteAt(it.lastIndex)
                }
            }
            cursor = entry.length
        }

        fun insertAtCursor(s: String): StringBuilder =
            entry.insert(cursor, s).also { cursor += s.length }

        fun deleteAtCursor(): StringBuilder =
            if (cursor > 0) entry.deleteCharAt(--cursor) else entry

        fun deleteAfterCursor(all: Boolean = true): StringBuilder =
            if (cursor < entry.length) {
                if (all) entry.delete(cursor, entry.length)
                else entry.deleteCharAt(cursor)
            } else entry

        override fun toString() = entry.toString() + roman?.toString().orEmpty()
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
        val candidate: String, val list: List<String>, val index: Int,
        val kanjiKey: String, val okurigana: String
    )

    internal var mLastConversion: ConversionInfo? = null

    internal var isPersonalizedLearning = true
    internal val ic get() = mService.currentInputConnection
    internal val keyboardType: String
        get() = if (mService.checkUseSoftKeyboard()) skkPrefs.softKeyboardType else "qwerty"
    private var wasFlickBeforeTemporary: Boolean = keyboardType != "qwerty"

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
        val closedSet = mutableSetOf<SKKDictionaryInterface>()
        for (dict in mDictList + mASCIIDict + mEmojiDict + mSymbolDict) {
            if (closedSet.add(dict)) dict.close()
        }
    }

    internal fun processKey(keyCode: Int) {
        if (skkPrefs.isModeKey(keyCode)) SKKLog.d("processKey(${getKeyName(keyCode)}) in ${state.name}")
        state.processKey(this, keyCode)
    }

    internal fun handleKanaKey() {
        SKKLog.d("handleKanaKey() in ${state.name}")
        state.handleKanaKey(this)
    }


    internal fun handleASCIIKey(): Boolean = state.handleASCIIKey(this)


    internal fun handleEnter(): Boolean {
        SKKLog.d("handleEnter() in ${state.name}")
        return state.handleEnter(this)
    }

    internal fun handleBackspace(): Boolean = state.handleBackspace(this)

    internal fun handleForwardDel(): Boolean = state.handleForwardDel(this)

    internal fun handleDelete(isForward: Boolean = false): Boolean {
        when (state) {
            SKKChooseState, SKKNarrowingState -> SKKLog.e("handleDelete() can't be called in ${state.name}")
            // Abbrev, ASCII, *Kana, Okurigana, Preedit, Zenkaku からは来る
            else -> SKKLog.d("handleDelete(isForward=$isForward) in ${state.name} ($mKanjiKey[$mRoman])")
        }

        // 変換中のものがない場合 (ASCII/Zenkaku は必ずここを通る)
        if (mRoman.isEmpty() && mKanjiKey.isEmpty()) {
            if (!isForward && state.isTransient && state.canComplete) {
                changeState(kanaState)
                return true
            }

            // 登録中なら true で吸収、非登録中の非 transient なら false で外部処理
            val (regInfo, firstEntry) = mRegister.first() ?: return state.isTransient
            when (isForward) {
                true if regInfo.cursor < firstEntry.length ->
                    firstEntry.deleteCharAt(regInfo.cursor)

                false if regInfo.cursor > 0 ->
                    firstEntry.deleteCharAt(--regInfo.cursor)

                else -> return true
            }
            setComposingTextSKK("")
            return true
        }

        // ここで ASCII/Zenkaku の可能性が消滅し、handleBackspace/handleForwardDel 実装が必ず存在する
        // そのため、ここからの返り値は外部処理の必要性ではなく、変化の有無となる
        when (isForward) {
            true -> if (mKanjiKey.isNotEmpty() && mKanjiKey.cursor < mKanjiKey.length) {
                mKanjiKey.deleteAfterCursor(all = false)
            } else return false

            false -> when {
                mRoman.isNotEmpty() -> mRoman.deleteCharAt(mRoman.lastIndex)
                mOkurigana.isNotEmpty() -> mOkurigana = mOkurigana.dropLast(1)
                mKanjiKey.isNotEmpty() -> mKanjiKey.deleteAtCursor()
                else -> return false
            }
        }
        return true
    }

    // Abbrev と Preedit で delete 処理後などに実行
    internal fun updateComplete() {
        setComposingTextSKK()
        complete(mKanjiKey.toString())
    }

    internal fun handleCancel(reconvert: Boolean = true): Boolean {
        SKKLog.d("handleCancel() in ${state.name}")
        return ic != null && state.handleCancel(this, reconvert)
    }

    /**
     * commitTextのラッパー 登録作業中なら登録内容に追加し，表示を更新
     * @param text
     */
    internal fun commitTextSKK(text: CharSequence) {
        SKKLog.d("commit text='$text' registering=${mRegister.isOngoing}")
        ic ?: return
        if (!mRegister.isOngoing) {
            ic.commitText(text, 1)
            mComposingText.clear()
            mKanjiKey.clear()
            mRoman.clear()
            mOkurigana = ""
            return
        }
        val (regInfo, firstEntry) = mRegister.first() ?: return
        firstEntry.insert(regInfo.cursor, text)
        regInfo.cursor += text.length
        setComposingTextSKK("")
    }

    internal fun resetOnStartInput() {
        SKKLog.d("resetOnStartInput()")
        mRoman.clear()
        mKanjiKey.clear()
        mOkurigana = ""
        mCandidates.reset()
        if (state.isTransient) changeState(kanaState)
        state.onEnter(this, state)

        // onStartInput()では，WebViewのときsetComposingText("", 1)すると落ちるようなのでやめる
    }

    internal fun moveCandidateCursor(isForward: Boolean) =
        mCandidates.moveCandidateCursor(isForward)

    internal fun handleDpad(keyCode: Int): Boolean = state.handleDpad(this, keyCode)

    internal fun handleDpadTransient(keyCode: Int, target: KanjiKey): Boolean {
        val (cursor, direction) = when (keyCode) {
            KeyEvent.KEYCODE_MOVE_HOME -> 0 to null
            KeyEvent.KEYCODE_DPAD_LEFT -> target.cursor.dec().coerceAtLeast(0) to false
            KeyEvent.KEYCODE_DPAD_RIGHT -> target.cursor.inc().coerceAtMost(target.length) to true
            KeyEvent.KEYCODE_MOVE_END -> target.length to null
            else -> return false
        }
        if (target.cursor == cursor && direction != null) {
            if (state.hasCandidates) mCandidates.moveCandidateCursor(direction)
            else mCandidates.cycleCompletionCursor(direction)
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) target.cursor = 0
        } else {
            SKKLog.d("[${target.take(cursor)}]|[${target.drop(cursor)}]")
            target.cursor = cursor
            setComposingTextSKK()
        }
        return true
    }

    internal fun pickCandidatesViewManually(
        index: Int, unregister: Boolean = false, sequential: Boolean = false
    ) = state.pickCandidatesViewManually(this, index, unregister, sequential)


    internal fun prepareToMushroom(clip: String): String = state.prepareToMushroom(this, clip)


    // 小文字大文字変換，濁音，半濁音に使う
    internal fun changeLastChar(type: String) {
        SKKLog.d("changeLastChar($type) in ${state.name} ($mKanjiKey[$mRoman])")
        when {
            state.transformLastChar(this, type) -> return
            mKanjiKey.isNotEmpty() -> return
        }

        val cs =
            if (mRoman.isNotEmpty()) mRoman.toString().also { mRoman.clear() }
            else ic?.getTextBeforeCursor(2, 0) ?: return

        // 0〜2 文字を 0〜3 文字にするので注意!
        val newLast2Chars = RomajiConverter.transform(cs.toString(), type)
        SKKLog.d("changeLastChar: $cs -> 2chars=$newLast2Chars")
        if (newLast2Chars.toList().all { it.isEmpty() }) return

        val deleteTwo = (cs.length == 2 && newLast2Chars.first.isEmpty())
        val newLastChar = newLast2Chars.second

        if (mRegister.isOngoing) {
            val (regInfo, firstEntry) = mRegister.first() ?: return
            when {
                regInfo.cursor < if (deleteTwo) 2 else 1 -> return
                deleteTwo -> firstEntry.deleteCharAt(regInfo.cursor--)
            }
            firstEntry.deleteCharAt(regInfo.cursor--)
            if (type == TRANS_SHIFT) {
                mKanjiKey.append(katakana2hiragana(newLastChar))
                changeState(SKKPreeditState)
                setComposingTextSKK()
                complete(mKanjiKey.toString())
            } else {
                firstEntry.insert(regInfo.cursor, newLastChar)
                regInfo.cursor += newLastChar.length
                setComposingTextSKK("")
            }
            return
        }

        // 同じ部分を消してまた書くのは避ける
        when {
            cs.isEmpty() -> if (type != TRANS_SHIFT) return
            !deleteTwo && cs.last() == newLastChar.last() && type != TRANS_SHIFT -> return
            !deleteTwo -> ic.deleteSurroundingText(1, 0)
            deleteTwo -> ic.deleteSurroundingText(2, 0)
        }
        if (type == TRANS_SHIFT) {
            mKanjiKey.append(katakana2hiragana(newLastChar))
            changeState(SKKPreeditState) // Abbrevから来ることはないはず
            updateComplete()
        } else {
            ic.commitText(newLastChar, 1)
            mComposingText.clear()
        }
    }

    internal fun getZenkakuSeparator(key: String) =
        mZenkakuSeparatorMap[key]

    /**
     * setComposingTextのラッパー 変換モードマーク等を追加する
     * @param text
     */
    internal fun setComposingTextSKK(text: CharSequence? = null) {
        val ct = mComposingText.clear()

        val suffix = if (mRegister.isOngoing) {
            val depth = mRegister.mStack.size
            repeat(depth) { ct.append("[") }
            ct.append("登録")
            repeat(depth) { ct.append("]") }

            val (regInfo, entry) = mRegister.first() ?: return
            val key = regInfo.key.convertTo(kanaState)
            val okurigana = regInfo.okurigana.convertTo(kanaState)
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
                if (!isPersonalizedLearning) ct.append("㊙")
                ct.append(prefix)
            } else if (text?.isEmpty() ?: mKanjiKey.isEmpty()) {
                ct.append(" ")
            }
        }

        val textToProcess = text ?: (if (mKanjiKey.cursor == mKanjiKey.length) {
            "${mKanjiKey.entry}${mRoman}"
        } else {
            "${mKanjiKey.take(mKanjiKey.cursor)}[${mRoman}]${mKanjiKey.drop(mKanjiKey.cursor)}"
        })

        ct.append(textToProcess.toString().convertTo(kanaState, reversed = true))
        state.setComposingText?.invoke(this, ct)

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
    internal fun startConversion(dictList: List<SKKDictionaryInterface> = mDictList) {
        if (mKanjiKey.isEmpty()) {
            changeState(kanaState) // ASCIIには戻れない…
            return
        }
        changeState(SKKChooseState)

        val str = mKanjiKey.toString()
        val list = lookup(str, dictList).ifEmpty {
            lookup(str.replace(Regex("\\d+(\\.\\d+)?"), "#"), dictList).ifEmpty {
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
        SKKLog.d("reConvert: lastConv=${lastConv.candidate} from ${lastConv.list} at ${lastConv.index} (key=${lastConv.kanjiKey}, okuri=${lastConv.okurigana})")

        if (mService.prepareReConversion(lastConv.candidate)) {
            runBlocking(Dispatchers.IO) { mUserDict.rollBack() }

            changeState(SKKChooseState)

            mRoman.clear()
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
            if (!state.isJapanese) return googleTranslate()
            if (mKanjiKey.isEmpty()) return
        } else {
            // candidate から選択しただけで登録されるので
            val regInfo = mRegister.mStack.removeFirst() ?: return
            mRoman.clear()
            mKanjiKey.set(regInfo.key)
            mOkurigana = regInfo.okurigana
        }
        SKKLog.d("googleTransliterate mKanjiKey=${mKanjiKey} mOkurigana=${mOkurigana}")

        changeState(SKKPreeditState)
        val query = if (mKanjiKey.isNotEmpty() && mKanjiKey.roman != null) {
            setComposingTextSKK("${mKanjiKey.entry}*${mOkurigana}")
            "${mKanjiKey.entry}${mOkurigana}"
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
        val query = when (val state = state) {
            is SKKAbbrevState -> mKanjiKey.toString()
            is SKKASCIIState -> state.getPrefix(this) + state.getSuffix(this) // Uri.encode() すべき?
            else -> return
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
                    if (state is SKKAbbrevState) changeState(SKKChooseState)
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

    internal fun symbolCandidates() =
        specialCandidates(SKKPreeditState, listOf(mSymbolDict), "")

    internal fun emojiCandidates() =
        specialCandidates(SKKChooseState, listOf(mASCIIDict, mEmojiDict), "emoji")

    private fun specialCandidates(
        targetState: SKKState, dictList: List<SKKUserDictionary>, query: String
    ) {
        mKanjiKey.set(query)
        changeState(targetState)
        setComposingTextSKK("") // 前の mKanjiKey の表示が残らないように

        mCandidates.apply {
            isSpecial = true

            val (completionList, list) = mutableSetOf<Pair<String, String>>().also { set ->
                runBlocking { dictList.forEach { searchCandidates(this, set, query, it) } }
            }.unzip()
            mCompletionList = completionList
            mList = list
            mIndex = 0
            mQuery = query
            setView(mList, mQuery, mIndex)

            // 補完は暗黙の確定をしないので composing 表示不要、候補だけ表示
            if (targetState.hasCandidates) updateComposingText()
        }
    }

    internal fun lookup(
        key: String, dictList: List<SKKDictionaryInterface> = mDictList
    ): List<String> = runBlocking(Dispatchers.IO) {
        val userEntry = mUserDict.getEntry(key)
        SKKLog.d("user dictionary: $key -> ${userEntry?.candidates} with ${userEntry?.okuriganaBlocks}")
        val (userOkList, userRestList) = (userEntry?.candidates ?: listOf()).partition { s ->
            mOkurigana.isEmpty() || userEntry?.okuriganaBlocks?.any {
                it.first == katakana2hiragana(hankaku2zenkaku(mOkurigana)) && it.second == s
                // 送り仮名ブロックを直接使って変換するのではなく、この判定にだけ使っている
                // なので、送り仮名ブロックだけで存在していても無意味である
            } == true
        }

        val rawList: List<String> = dictList.asSequence().mapNotNull { dict ->
            when (dict) {
                mUserDict -> userOkList
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
        mRoman.clear()
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
        mComposingText.clear()
    }

    // 入力モード変更操作．変更したらtrue
    internal fun changeInputMode(keyCode: Int): Boolean {
        val char = Char(keyCode.lowerCode)
        if (mRoman.isNotEmpty() && RomajiConverter.convert("$mRoman$char")
                .isNotEmpty()
        ) return false

        return when (keyCode) {
            skkPrefs.katakanaKey -> state.handleKatakanaKey(this)
            skkPrefs.hankakuKanaKey -> state.handleHankakuKanaKey(this)
            skkPrefs.asciiKey -> state.handleASCIIKey(this)
            skkPrefs.zenkakuKey -> state.handleZenkakuKey(this)
            skkPrefs.abbrevKey -> state.handleAbbrevKey(this)
            else -> false
        }
    }

    internal fun commitComposing() {
        if (mKanjiKey.isNotEmpty()) {
            mKanjiKey.roman = null
            commitTextSKK(mKanjiKey.toString().convertTo(kanaState))
        }
        if (mRoman.toString() == "n") {
            commitTextSKK("ん".convertTo(kanaState))
        }
        reset()
    }

    internal fun updateKanaState(state: SKKState) {
        mService.kanaState = state
    }

    internal fun changeState(newState: SKKState, forceKeyboard: Boolean = false) {
        SKKLog.d("changeState ${this.state.name} -> ${newState.name} (force=$forceKeyboard)")

        val wasTemporaryQwerty = mService.isTemporaryQwerty
        val willBeTemporaryQwerty = keyboardType == "switch" && newState.isTemporaryQwerty
        val isInternalTransition = newState.isTransient && // ▽▼ への遷移は通常では何もしない
                !forceKeyboard && // しかしキーボード変更を強制する場合や
                !willBeTemporaryQwerty && !wasTemporaryQwerty // abbrev 等の一時的な変更は除外

        this.state.onExit(this, newState)
        oldState = this.state
        this.state = newState

        if (!isInternalTransition) {
            when {
                forceKeyboard || willBeTemporaryQwerty -> changeSoftKeyboard(newState)

                // 一時的なキーボードから戻る場面なので、wasFlick に記録せず直接 mService を叩く
                wasTemporaryQwerty && wasFlickBeforeTemporary ->
                    mService.changeSoftKeyboard(kanaState)
            }
            // reset()で一旦消してるので， 登録中はここまで来てからComposingText復活
            if (mRegister.isOngoing) setComposingTextSKK("")
        }

        newState.onEnter(this, oldState)
    }

    internal fun changeSoftKeyboard(state: SKKState) {
        // 仮名からASCII以外の一時的なキーボードになるときや明示的変更のとき記録して後で戻れるようにしておく
        wasFlickBeforeTemporary = when {
            state.isTemporaryQwerty -> mService.isFlick()
            !state.isTransient -> keyboardType != "qwerty" && state.isJapanese
            else -> wasFlickBeforeTemporary
        }
        mService.changeSoftKeyboard(state)
    }

    internal fun updateStatusIcon(icon: Int) {
        when (icon) {
            -1 -> mService.hideStatusIcon()
            0 -> {}
            else -> mService.showStatusIcon(icon)
        }
    }


    companion object {
        const val TRANS_SMALL = "small"
        const val TRANS_DAKUTEN = "dakuten"
        const val TRANS_HANDAKUTEN = "handakuten"
        const val TRANS_AUTO = "auto"
        const val TRANS_SHIFT = "shift"
        const val ASCII_WORD_MAX_LENGTH = 32
    }
}

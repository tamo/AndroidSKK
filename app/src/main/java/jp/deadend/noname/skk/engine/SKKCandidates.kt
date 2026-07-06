package jp.deadend.noname.skk.engine

import jp.deadend.noname.skk.SKKDictionaryInterface
import jp.deadend.noname.skk.SKKLog
import jp.deadend.noname.skk.SKKService
import jp.deadend.noname.skk.encodeKey
import jp.deadend.noname.skk.fuzzy
import jp.deadend.noname.skk.hiragana2katakana
import jp.deadend.noname.skk.isAlphabet
import jp.deadend.noname.skk.processConcatAndMore
import jp.deadend.noname.skk.removeAnnotation
import jp.deadend.noname.skk.skkPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

class SKKCandidates(private val engine: SKKEngine, private val service: SKKService) {
    // mList と mCompletionList は基本的に同じものが入るが、
    // ASCII のとき mCompletionList に ill があって mList で I'll に変換されるなどもある
    internal var mList: List<String>? = null
    internal var mCompletionList: List<String>? = null
    internal var mIndex = 0
    internal var mQuery = ""
    internal var isSequential = false // シフトでオンオフする連続入力フラグ
    internal var isSpecial = false // 絵文字か記号をソフトキーで呼んだら true
    private var isFirstMove = true // mIndex は 0 からスタートだが -1 のように扱いたい
    private var mJob: Job = Job()
    private var mSuspended: Boolean = false

    private val mNumberRegex = Regex("\\d+(\\.\\d+)?")

    private suspend fun updateView(list: List<String>?, kanjiKey: String, index: Int) {
        if (list.isNullOrEmpty()) withContext(Dispatchers.Main) {
            service.clearCandidatesView()
        } else {
            val (layout, viewLines) = service.mCandidatesView.buildLayout(list, kanjiKey, isSpecial)
            withContext(Dispatchers.Main) {
                service.setCandidates(layout, viewLines, index)
            }
        }
        isFirstMove = true
    }

    internal fun setView(list: List<String>?, kanjiKey: String, index: Int) {
        mJob.cancel()
        mJob = MainScope().launch(Dispatchers.Default) {
            updateView(list, kanjiKey, index)
        }
    }

    internal fun appendTask(task: () -> Unit) = MainScope().launch(Dispatchers.Default) {
        mJob.join()
        task()
    }

    private fun updateViewCursor() = service.setCandidatesCursor(mIndex)

    internal fun cycleCompletionCursor(isForward: Boolean) {
        val list = mList ?: return
        if (list.isEmpty()) return SKKLog.d("list is empty")

        mIndex = if (isForward) {
            if (isFirstMove) mIndex.also { isFirstMove = false }
            else (mIndex + 1) % list.size
        } else {
            (mIndex - 1 + list.size) % list.size
        }

        updateViewCursor()
        updateCompletionCursor()
    }

    internal fun moveCandidateCursor(isForward: Boolean) {
        val list = mList ?: return
        if (list.isEmpty()) return SKKLog.d("list is empty")

        if (isForward) mIndex++ else mIndex--

        if (isSpecial) mIndex = (mIndex + list.size) % list.size
        else when {
            mIndex > list.lastIndex -> {
                engine.mRegister.start(engine.mKanjiKey.toString())
                return
            }

            mIndex < 0 -> when (engine.state) {
                is SKKNarrowingState -> mIndex = list.lastIndex

                is SKKChooseState -> engine.apply {
                    SKKLog.d("back to preedit: composing=$mComposing, key=$mKanjiKey, okuri=$mOkurigana")
                    this@SKKCandidates.reset()
                    if (mComposing.isEmpty()) {
                        if (mOkurigana.isNotEmpty()) {
                            mOkurigana = ""
                            mKanjiKey.deleteAtCursor()
                        }
                        changeState(SKKPreeditState)
                        setComposingTextSKK()
                        complete(mKanjiKey.toString())
                    } else {
                        mKanjiKey.clear()
                        changeState(SKKAbbrevState)
                        setComposingTextSKK(mComposing)
                        complete(mComposing.toString())
                    }
                    return
                }
            }
        }

        updateViewCursor()
        updateComposingText()
    }

    internal fun setCandidateCursor(index: Int) {
        mIndex = index
        mList ?: return
        updateViewCursor()
        updateComposingText()
    }

    private fun get(index: Int): String? = mList?.getOrNull(index)?.let {
        processConcatAndMore(removeAnnotation(it), mQuery)
    }

    fun updateComposingText() = engine.run {
        get(mIndex)?.let { candidate ->
            setComposingTextSKK( // setComposingText のある NarrowingState は自前で mComposing を表示
                candidate + mOkurigana + if (state.setComposingText == null) mComposing else ""
            )
        } ?: setComposingTextSKK()
    }

    fun pickCandidate(index: Int, backspace: Boolean = false, unregister: Boolean = false) {
        if (!engine.state.hasCandidates) return
        val list = mList ?: return
        val rawCandidate = list.getOrNull(index) ?: return
        val candidate = StringBuilder(get(index) ?: return)
        SKKLog.d("pickCandidate $candidate from $list (unregister=$unregister, learn=${engine.isPersonalizedLearning}, key=${engine.mKanjiKey})")
        suspendCompletion()

        if (isSpecial) engine.apply {
            if (mQuery.isNotEmpty()) {
                if (unregister) {
                    val confirm = state as SKKConfirmingState
                    confirm.confirmUnregister(this, "/${removeAnnotation(rawCandidate)}/") {
                        runBlocking(Dispatchers.IO) {
                            engine.mASCIIDict.removeEntry(mQuery, rawCandidate, "")
                        }
                        reset()
                        changeState(oldState)
                    }
                    resumeCompletion()
                    return
                } else if (isPersonalizedLearning) runBlocking(Dispatchers.IO) {
                    engine.mASCIIDict.addEntry(mQuery, candidate.toString(), "")
                }
            }

            commitTextSKK(removeAnnotation(candidate.toString()))
            resumeCompletion()

            if (isSequential) return
            reset()
            changeState(kanaState)
            return
        }

        if (unregister) engine.apply {
            val unannotated = removeAnnotation(rawCandidate)
            (state as SKKConfirmingState).confirmUnregister(
                this,
                "/$unannotated/${
                    if (mOkurigana.isNotEmpty()) "[$mOkurigana/$unannotated/]/" else ""
                }"
            ) {
                runBlocking(Dispatchers.IO) {
                    mUserDict.removeEntry(mKanjiKey.toString(), rawCandidate, mOkurigana)
                }
                reset()
                changeState(kanaState)
            }
            resumeCompletion()
            return
        }

        engine.apply {
            if (isPersonalizedLearning) {
                runBlocking(Dispatchers.IO) {
                    mUserDict.addEntry(mKanjiKey.toString(), rawCandidate, mOkurigana)
                }
                // ユーザー辞書登録時はエスケープや注釈を消さない
            }

            if (backspace) {
                if (mOkurigana.isNotEmpty()) mOkurigana =
                    mOkurigana.dropLast(1)
                else candidate.deleteCharAt(candidate.lastIndex)
            }
            val concat = candidate.toString() + mOkurigana
            val text = concat.convertTo(kanaState, reversed = true)
            if (!mRegister.isOngoing) {
                mLastConversion = SKKEngine.ConversionInfo(
                    text, list, index, mKanjiKey.toString(), mOkurigana
                )
            }
            commitTextSKK(text)
            resumeCompletion()

            if (isSequential) return
            reset()
            changeState(kanaState)
        }
    }

    private fun updateCompletionCursor() = pickCompletion(mIndex, commit = false)

    fun pickCompletion(index: Int, unregister: Boolean = false, commit: Boolean = true) {
        var number = mNumberRegex.find(engine.mKanjiKey)
        val rawCompletion = mList?.get(index) ?: return
        val conv = buildString {
            for (char in rawCompletion) {
                if (number != null && char == '#') {
                    append(number.value)
                    number = number.next()
                } else append(char)
            }
        }
        SKKLog.d("pickCompletion conv=$conv from $mList (unregister=$unregister)")
        val comp = mCompletionList?.get(index) ?: return
        SKKLog.d("pickCompletion comp=$comp from $mCompletionList")
        // comp を入力して conv になるイメージなので基本的に両者は同じだが
        // comp が ill で conv が I'll になるような、入力した文字まで変化する場合がある

        engine.apply {
            when (val state = state) {
                SKKAbbrevState -> {
                    mKanjiKey.set(conv)
                    setComposingTextSKK()
                    if (commit) startConversion()
                }

                SKKPreeditState, SKKOkuriganaState -> {
                    val hira = conv.convertTo(SKKHiraganaState)
                    val last = hira.lastOrNull() ?: ' '
                    val hasOkuri = hira.isNotEmpty() &&
                            !isAlphabet(hira.first().code) && isAlphabet(last.code)
                    val kanjiKey = if (hasOkuri) hira.dropLast(1) else hira
                    mKanjiKey.set(kanjiKey)
                    mComposing.setLength(0)
                    if (commit) when {
                        isSpecial -> {
                            mKanjiKey.set(conv) // カテゴリ名
                            startConversion(listOf(mASCIIDict, mSymbolDict))
                        }

                        hasOkuri -> processKey(encodeKey(last.uppercaseChar().code))
                        else -> startConversion()
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
                    when {
                        !commit -> return

                        unregister -> return state.confirmUnregister(
                            engine, "/${removeAnnotation(rawCompletion)}/"
                        ) {
                            runBlocking(Dispatchers.IO) {
                                engine.mASCIIDict.removeEntry(comp, rawCompletion, "")
                            }
                            reset() // ASCII からは changeState(oldState) 不要
                        }

                        isPersonalizedLearning -> runBlocking(Dispatchers.IO) {
                            engine.mASCIIDict.addEntry(comp, rawCompletion, "")
                        }
                    }

                    val slim = removeAnnotation(conv)
                    commitTextSKK(slim) // アプリ側で補完表示していることがあるのでまず上書きしておく
                    state.deleteSurroundingText(this@apply, slim)
                    reset()
                }
            }
        }
    }


    internal fun narrow(hint: String) {
        SKKLog.d("narrowCandidates: hint: $hint, mKanjiKey: $mQuery")
        if (SKKNarrowingState.mOriginalCandidates == null) {
            SKKNarrowingState.mOriginalCandidates = mList
        }
        val candidates = SKKNarrowingState.mOriginalCandidates ?: return

        val narrowed = if (hint.isEmpty()) candidates else {
            val katakanaHint = hiragana2katakana(hint)
            val hintKanjiSet = engine.lookup(hint).asSequence()
                .flatMap { processConcatAndMore(removeAnnotation(it), "").asSequence() }
                .toSet()
            SKKLog.d("narrowCandidates: hintKanjiSet: $hintKanjiSet")
            // mQuery("かんじ") -> candidates("漢字; 注釈も含む", "幹事", "監事", "感じ")
            // hint("おとこ") -> hintKanjiSet(['男', '漢', '♂']) 注釈なし
            // -> narrowed(["漢字; 注釈も含む"]) 「漢」で合致
            //
            // mQuery("じゅ") -> candidates("受", "授", "樹", "寿")
            // hint("じゅもく") -> hintKanjiSet(['樹', '木']) "樹木" から単漢字セットに
            // -> narrowed(["樹"])
            candidates.filter { str ->
                str.any { it in hintKanjiSet } ||
                        str.contains(hint) ||
                        str.contains(katakanaHint) ||
                        str.contains(hint.uppercase()) // 記号用
            }
        }

        mList = narrowed.ifEmpty { null }
        mIndex = 0
        setView(narrowed, mQuery, mIndex)
        updateComposingText()
    }

    internal fun complete(str: String) {
        if (mSuspended) return
        mJob.cancel()
        mJob = MainScope().launch(Dispatchers.Default) {
            completeInternal(str)
        }
    }

    internal fun completeASCII() {
        if (mSuspended) return
        if (engine.state !is SKKASCIIState) return
        mJob.cancel()
        mJob = MainScope().launch(Dispatchers.Default) {
            delay(50.milliseconds) // バックスペースなどの処理が間に合っていないことがあるので
            ensureActive()
            if (engine.state is SKKASCIIState) {
                val prefix = (engine.state as SKKASCIIState).getPrefix(engine)
                completeInternal(prefix)
            }
        }
    }

    private suspend fun completeInternal(str: String) {
        coroutineScope {
            if (str.isEmpty()) {
                delay(150.milliseconds) // 短時間に連続で実行されると画面がチラつく
                ensureActive()
                withContext(Dispatchers.Main) { service.clearCandidatesView() }
                return@coroutineScope
            }

            val isEmoji = str == "emoji" // 手動で emoji と打ったかどうか
            val hiraKey = str.convertTo(SKKHiraganaState)
            val maskedStr = hiraKey.replace(mNumberRegex, "#")
            val isFuzzyEnabled = skkPrefs.fuzzySuggestion && !isEmoji
            val dictList = if (isEmoji) engine.mDictList + engine.mEmojiDict
            else engine.mDictList

            val startTime = System.currentTimeMillis()
            fun elapsed() = System.currentTimeMillis() - startTime

            val results = withContext(Dispatchers.Default) {
                dictList.map { dict ->
                    async {
                        // ASCII は、ほとんどの辞書を無視する (user は実際には ASCII の意味になる)
                        if (engine.state is SKKASCIIState && dict !== engine.mUserDict &&
                            !(isEmoji && dict === engine.mEmojiDict)
                        ) return@async null

                        val found = mutableSetOf<Pair<String, String>>()
                        fun fuzzySequence(str: String) = if (skkPrefs.fuzzySuggestion) {
                            fuzzy(str, false) +
                                    if (skkPrefs.fuzzierSuggestion) fuzzy(str, true)
                                    else emptySequence()
                        } else emptySequence()

                        // 字数を増やさずに変換できるものを最優先
                        if (isFuzzyEnabled) withTimeoutOrNull(1000.milliseconds) {
                            // 重い処理: 価値があるので数は少なくてもいいがタイムアウトが必要
                            val goal: Int = skkPrefs.candidatesNormalLines * 7 / hiraKey.length
                            for (fuzzyStr in fuzzySequence(hiraKey)) {
                                if (found.size >= goal) break
                                ensureActive()
                                if (withContext(Dispatchers.IO) {
                                        dict.getCandidates(fuzzyStr).isNullOrEmpty()
                                    }) continue
                                val shownStr = fuzzyStr.convertTo(engine.kanaState, reversed = true)
                                found.add(fuzzyStr to shownStr)
                            }
                        }

                        // 前方一致は軽いので無条件で実行 (数字ありと数字マスク状態で)
                        searchCandidates(this, found, hiraKey, dict)

                        if (maskedStr != hiraKey) {
                            searchCandidates(this, found, maskedStr, dict)
                        }

                        // あいまい前方一致は意味があまりないので数は多く、最後に短時間だけ
                        if (isFuzzyEnabled) {
                            val goal: Int = skkPrefs.candidatesNormalLines * 10 / hiraKey.length
                            for (fuzzyStr in fuzzySequence(maskedStr)) {
                                if (found.size > goal ||
                                    found.size > 2000 / elapsed().coerceAtLeast(1)
                                ) break
                                ensureActive()
                                searchCandidates(this, found, fuzzyStr, dict)
                            }
                        }
                        found
                    }
                }.awaitAll()
            }.filterNotNull()

            delay((150 - elapsed()).milliseconds)
            ensureActive() // 短時間に連続で実行されないよう最新のみ有効に

            val uniqueSet = results.flatten().distinctBy { it.second }
            SKKLog.d("complete query='$hiraKey' found=${uniqueSet.size} in ${elapsed()} ms")

            val (completionList, list) = uniqueSet.unzip()
            mCompletionList = completionList
            mList = list

            mQuery = hiraKey
            mIndex = 0
            updateView(mList, hiraKey, mIndex)
        }
    }

    internal fun suspendCompletion() {
        mJob.cancel()
        mSuspended = true
    }

    internal fun resumeCompletion() {
        mSuspended = false
    }

    internal suspend fun searchCandidates(
        scope: CoroutineScope,
        target: MutableSet<Pair<String, String>>,
        key: String,
        dict: SKKDictionaryInterface
    ) {
        val dictionary = when (dict) {
            engine.mUserDict ->
                if (isSpecial || key == "emoji" || engine.state is SKKASCIIState)
                    engine.mASCIIDict else engine.mUserDict

            engine.mEmojiDict, engine.mSymbolDict ->
                if (isSpecial || key == "emoji") { // symbol は isSpecial 以外で呼ばれないはず
                    dict.findKeys(scope, "").forEach { pair -> target.add(key to pair.second) }
                    return
                } else return

            else -> dict
        }

        val keys = dictionary.findCompletePairs(scope, key)

        keys.forEach { (first, second) ->
            target.add(first to second.convertTo(engine.kanaState, reversed = true))
        }
    }

    fun reset() {
        mJob.cancel()
        mList = null
        mCompletionList = null
        mIndex = 0
        mQuery = ""
        isSequential = false
        isSpecial = false
        engine.apply {
            (mDictList + mASCIIDict + mEmojiDict + mSymbolDict).forEach { it.clearCache() }
        }
        setView(null, "", 0)
    }

    internal fun loadAllSymbols() {
        val list = engine.mSymbolDict.getAllCandidates()
        mList = list
        mCompletionList = list.map { "" }
        mQuery = ""
        mIndex = 0
        setView(list, mQuery, mIndex)
    }
}

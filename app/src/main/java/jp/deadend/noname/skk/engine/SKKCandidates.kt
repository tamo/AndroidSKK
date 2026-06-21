package jp.deadend.noname.skk.engine

import jp.deadend.noname.skk.SKKDictionaryInterface
import jp.deadend.noname.skk.SKKLog
import jp.deadend.noname.skk.SKKService
import jp.deadend.noname.skk.SKKUserDictionary
import jp.deadend.noname.skk.encodeKey
import jp.deadend.noname.skk.fuzzy
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
import kotlin.time.measureTime

class SKKCandidates(private val engine: SKKEngine, private val service: SKKService) {
    // mList と mCompletionList は基本的に同じものが入るが、
    // ASCII のとき mCompletionList に ill があって mList で I'll に変換されるなどもある
    internal var mList: List<String>? = null
    internal var mCompletionList: List<String>? = null
    internal var mIndex = 0
    internal var mQuery = ""
    private var mJob: Job = Job()
    private var mSuspended: Boolean = false

    private val mNumberRegex = Regex("\\d+(\\.\\d+)?")
    private val mFoundKeys = mutableMapOf<SKKDictionaryInterface, DictCache>()

    private data class DictCache(val key: String, val results: List<Pair<String, String>>)

    private suspend fun updateView(list: List<String>?, kanjiKey: String, index: Int) {
        if (list.isNullOrEmpty()) withContext(Dispatchers.Main) {
            service.clearCandidatesView()
        } else {
            val (layout, viewLines) = service.mCandidatesView.buildLayout(list, kanjiKey)
            withContext(Dispatchers.Main) {
                service.setCandidates(layout, viewLines, index)
            }
        }
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

    internal fun updateViewCursor() = service.setCandidatesCursor(mIndex)

    internal fun cycleCompletionCursor(isForward: Boolean) {
        val list = mList ?: return

        mIndex = if (isForward) {
            (mIndex + 1) % list.size
        } else {
            (mIndex - 1 + list.size) % list.size
        }

        updateViewCursor()
        updateCompletionCursor()
    }

    internal fun moveCandidateCursor(isForward: Boolean) {
        val list = mList ?: return

        if (isForward) mIndex++ else mIndex--

        when (mQuery) {
            "emoji", "/きごう" -> mIndex = (mIndex + list.size) % list.size

            else -> when {
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
        }

        updateViewCursor()
        updateComposingText()
    }


    private fun get(index: Int): String? = mList?.let {
        processConcatAndMore(removeAnnotation(it[index]), mQuery)
    }

    fun updateComposingText() = get(mIndex)?.let { candidate ->
        engine.setComposingTextSKK(candidate + engine.mOkurigana)
    } ?: engine.setComposingTextSKK()

    fun pickCandidate(index: Int, backspace: Boolean = false, unregister: Boolean = false) {
        if (!engine.state.hasCandidates) return
        val list = mList ?: return
        val rawCandidate = list[index]
        val candidate = StringBuilder(get(index) ?: return)
        SKKLog.d("pickCandidate $candidate from $list (unregister=$unregister, learn=${engine.isPersonalizedLearning}, key=${engine.mKanjiKey})")
        suspendCompletion()

        if (mQuery == "emoji" || mQuery == "/きごう") {
            if (engine.isPersonalizedLearning || unregister) {
                val s = removeAnnotation(candidate.toString())
                var newEntry = "/160/$s"
                runBlocking(Dispatchers.IO) {
                    engine.mASCIIDict.getEntry(mQuery)?.let { entry ->
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
                    }
                }.orEmpty().let { oldEntry ->
                    if (unregister) newEntry = ""
                    SKKLog.d("replaceEntry($mQuery, $newEntry$oldEntry)")
                    runBlocking(Dispatchers.IO) {
                        engine.mASCIIDict.replaceEntry(mQuery, newEntry + oldEntry)
                    }
                }
            }

            engine.apply {
                if (unregister) {
                    val confirm = state as SKKConfirmingState
                    confirm.confirmUnregister(this, "/${removeAnnotation(rawCandidate)}/") {
                        reset()
                        changeState(oldState)
                    }
                    resumeCompletion()
                    return
                }

                commitTextSKK(removeAnnotation(candidate.toString()))
                resumeCompletion()
                if (state.isSequential) return
                reset()
                changeState(oldState)
                return
            }
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
            val text = when (kanaState) {
                SKKHiraganaState -> concat
                SKKKatakanaState -> hiragana2katakana(concat, reversed = true)
                SKKHanKanaState -> zenkaku2hankaku(hiragana2katakana(concat))
                else -> throw RuntimeException("kanaState: $kanaState")
            } // カナかなは互換性あるけど半角カナと全角かなは互換性ない感覚があるので reverse しない
            if (!mRegister.isOngoing) {
                mLastConversion = SKKEngine.ConversionInfo(
                    text, list, index, mKanjiKey.toString(), mOkurigana
                )
            }
            commitTextSKK(text)
            resumeCompletion()

            if (state.isSequential) return
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
            when (state) {
                SKKAbbrevState -> {
                    mKanjiKey.set(conv)
                    setComposingTextSKK()
                    if (commit) startConversion()
                }

                SKKPreeditState, SKKOkuriganaState -> {
                    val hira =
                        if (kanaState is SKKHiraganaState) conv else katakana2hiragana(conv)
                    val last = hira.lastOrNull() ?: ' '
                    val hasOkuri = hira.isNotEmpty() &&
                            !isAlphabet(hira.first().code) && isAlphabet(last.code)
                    val kanjiKey = if (hasOkuri) hira.dropLast(1) else hira
                    mKanjiKey.set(kanjiKey)
                    mComposing.setLength(0)
                    if (commit) {
                        if (hasOkuri) {
                            processKey(encodeKey(last.uppercaseChar().code))
                        } else {
                            startConversion()
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
                            var newEntry = "/160/$conv"
                            runBlocking(Dispatchers.IO) {
                                engine.mASCIIDict.getEntry(comp)?.let { entry ->
                                    entry.candidates.asSequence() // freq1, val1, freq2, val2
                                        .zipWithNext() // (freq1, val1), (val1, freq2), (freq2, val2)
                                        .filterIndexed { i, _ -> i % 2 == 0 } // (freq1, val1), (freq2, val2)
                                        .mapNotNull {
                                            if (it.second == conv) {
                                                newEntry =
                                                    "/${it.first.toInt().coerceAtLeast(160)}/$conv"
                                                null
                                            } else it
                                        }
                                        .fold("/") { str, pair -> "$str${pair.first}/${pair.second}/" }
                                }
                            }.orEmpty().let { oldEntry ->
                                if (unregister) newEntry = ""
                                SKKLog.d("replaceEntry($comp, $newEntry$oldEntry)")
                                runBlocking(Dispatchers.IO) {
                                    engine.mASCIIDict.replaceEntry(comp, newEntry + oldEntry)
                                }
                            }
                        }
                        if (unregister) {
                            val confirm = state as SKKConfirmingState
                            confirm.confirmUnregister(
                                engine,
                                "/${removeAnnotation(rawCompletion)}/"
                            ) {
                                lambda()
                                reset()
                                // ASCII からは changeState(oldState) 不要
                            }
                            return
                        }
                        lambda()
                    }

                    val slim = removeAnnotation(conv)
                    commitTextSKK(slim) // アプリ側で補完表示していることがあるのでまず上書きしておく
                    (state as SKKASCIIState).deleteSurroundingText(this@apply, slim)
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
            val hintKanjiSet = engine.find(hint).asSequence()
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
                        str.contains(hint) || str.contains(katakanaHint)
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

            val isEmoji = str == "emoji"
            val maskedStr = str.replace(mNumberRegex, "#")
            val isFuzzyEnabled = skkPrefs.fuzzySuggestion && !isEmoji

            var elapsed = 0.milliseconds
            val results = withContext(Dispatchers.Default) {
                engine.mDictList.map { dict ->
                    async {
                        if (engine.state is SKKASCIIState &&
                            if (isEmoji) dict !is SKKUserDictionary else dict != engine.mUserDict
                        ) return@async null

                        val found = mutableSetOf<Pair<String, String>>()
                        // 字数を増やさずに変換できるものを最優先
                        val fuzzyFurther = isFuzzyEnabled && withTimeoutOrNull(1000.milliseconds) {
                            // 重い処理: 価値があるので数は少なくてもいいがタイムアウトが必要
                            var foundCount = 0
                            val duration = measureTime {
                                val goal: Int = skkPrefs.candidatesNormalLines * 7 / str.length
                                for (fuzzyStr in fuzzy(str)) {
                                    if (foundCount >= goal) break
                                    ensureActive()
                                    if (withContext(Dispatchers.IO) {
                                            dict.getCandidates(fuzzyStr).isNullOrEmpty()
                                        }) continue
                                    val shownStr =
                                        if (service.isHiragana) fuzzyStr
                                        else hiragana2katakana(fuzzyStr, reversed = true)
                                    found.add(fuzzyStr to shownStr)
                                    foundCount++
                                }
                            }
                            elapsed += duration
                            duration < 500.milliseconds || foundCount == 0
                        } ?: true // タイムアウトしても未発見なら前方一致あいまい検索してみる

                        // 前方一致は軽いので無条件で実行 (数字ありと数字マスク状態で)
                        addFound(this, found, str, dict)

                        if (maskedStr != str) {
                            addFound(this, found, maskedStr, dict)
                        }

                        // あいまい前方一致は意味があまりないので数は多く、最後に短時間だけ
                        val goal: Int = skkPrefs.candidatesNormalLines * 10 / str.length
                        if (fuzzyFurther) withTimeoutOrNull(500.milliseconds) {
                            for (fuzzyStr in fuzzy(maskedStr)) {
                                if (found.size > goal) break
                                ensureActive()
                                addFound(this, found, fuzzyStr, dict)
                            }
                        }
                        found
                    }
                }.awaitAll()
            }.filterNotNull()

            delay(150.milliseconds - elapsed)
            ensureActive() // 短時間に連続で実行されないよう最新のみ有効に

            val uniqueSet = results.flatten().distinctBy { it.second }
            SKKLog.d("complete query='$str' found=${uniqueSet.size} in ${elapsed.inWholeMilliseconds}ms")

            val (completionList, list) = uniqueSet.unzip()
            mCompletionList = completionList
            mList = list

            mQuery = str
            mIndex = 0
            updateView(mList, str, mIndex)
        }
    }

    internal fun suspendCompletion() {
        mJob.cancel()
        mSuspended = true
    }

    internal fun resumeCompletion() {
        mSuspended = false
    }

    internal suspend fun addFound(
        scope: CoroutineScope,
        target: MutableSet<Pair<String, String>>,
        key: String,
        dict: SKKDictionaryInterface
    ) {
        val dictionary = when (dict) {
            engine.mUserDict -> {
                if (key == "emoji" || engine.state is SKKASCIIState) engine.mASCIIDict else engine.mUserDict
            }

            engine.mEmojiDict -> {
                if (key == "emoji" || key == "えもじ") {
                    engine.mEmojiDict.findKeys(scope, "").forEach { emoji ->
                        target.add(key to emoji.second)
                    }
                }
                return
            }

            else -> dict
        }

        val cached = mFoundKeys[dictionary]
        val limit = if (dictionary.mIsASCII) SKKDictionaryInterface.MAX_FIND_KEYS_ASCII
        else SKKDictionaryInterface.MAX_FIND_KEYS
        val keys =
            if (cached != null && key.startsWith(cached.key) && cached.results.size < limit) {
                cached.results.filter { it.first.startsWith(key) }
            } else {
                dictionary.findKeys(scope, key).also {
                    mFoundKeys[dictionary] = DictCache(key, it)
                }
            }

        if (engine.kanaState is SKKHiraganaState) {
            target.addAll(keys)
        } else {
            keys.mapTo(target) { (first, second) ->
                first to hiragana2katakana(second, reversed = true)
            }
        }
    }

    fun reset() {
        mJob.cancel()
        mList = null
        mCompletionList = null
        mIndex = 0
        mQuery = ""
        mFoundKeys.clear()
    }
}

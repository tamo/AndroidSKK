package jp.deadend.noname.skk.engine

import jp.deadend.noname.skk.SKKDictionaryInterface
import jp.deadend.noname.skk.SKKService
import jp.deadend.noname.skk.dLog
import jp.deadend.noname.skk.encodeKey
import jp.deadend.noname.skk.fuzzy
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

    internal fun setView(list: List<String>?, kanjiKey: String) {
        mJob.cancel()
        mJob = MainScope().launch(Dispatchers.Default) {
            if (list.isNullOrEmpty()) withContext(Dispatchers.Main) {
                service.setCandidates(null)
            } else {
                val (layout, viewLines) = service.mCandidatesView.buildLayout(list, kanjiKey)
                withContext(Dispatchers.Main) {
                    service.setCandidates(layout, viewLines)
                }
            }
        }
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

        if (mIndex > list.lastIndex) {
            if (mQuery == "emoji" || mQuery == "/きごう") {
                mIndex = 0
            } else {
                engine.mRegister.start(engine.mKanjiKey.toString())
                return
            }
        } else if (mIndex < 0) {
            if (mQuery == "emoji" || mQuery == "/きごう") {
                mIndex = list.lastIndex
            } else when (engine.state) {
                is SKKChooseState -> engine.apply {
                    if (mComposing.isEmpty()) {
                        if (mOkurigana.isNotEmpty()) {
                            mOkurigana = ""
                            mKanjiKey.deleteCharAt(mKanjiKey.length - 1)
                        }
                        changeState(SKKPreeditState)
                        setComposingTextSKK(mKanjiKey)
                        complete(mKanjiKey.toString())
                    } else {
                        mKanjiKey.setLength(0)
                        changeState(SKKAbbrevState)
                        setComposingTextSKK(mComposing)
                        complete(mComposing.toString())
                    }

                    mIndex = 0
                    mJob.invokeOnCompletion {
                        updateComposingText()
                    }
                    return
                }

                is SKKNarrowingState -> {
                    mIndex = list.lastIndex
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
    }

    fun pickCandidate(index: Int, backspace: Boolean = false, unregister: Boolean = false) {
        if (!engine.state.hasCandidates) return
        val list = mList ?: return
        val rawCandidate = list[index]
        val candidate = StringBuilder(get(index) ?: return)
        dLog("pickCandidate $candidate from $list (unregister=$unregister, learn=${engine.isPersonalizedLearning})")
        suspendCompletion()

        if (mQuery == "emoji" || mQuery == "/きごう") {
            if (engine.isPersonalizedLearning || unregister) {
                val s = removeAnnotation(candidate.toString())
                var newEntry = "/160/$s"
                (engine.mASCIIDict.getEntry(mQuery)?.let { entry ->
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
                        dLog("replaceEntry($mQuery, $newEntry$it)")
                        engine.mASCIIDict.replaceEntry(mQuery, newEntry + it)
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
                mUserDict.removeEntry(
                    mKanjiKey.toString(),
                    rawCandidate,
                    mOkurigana
                )
                reset()
                changeState(kanaState)
            }
            resumeCompletion()
            return
        }

        engine.apply {
            if (isPersonalizedLearning) {
                mUserDict.addEntry(
                    mKanjiKey.toString(),
                    rawCandidate,
                    mOkurigana
                )
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
                SKKKatakanaState -> hiragana2katakana(concat, reversed = true).orEmpty()
                SKKHanKanaState -> zenkaku2hankaku(hiragana2katakana(concat)).orEmpty()
                else -> throw RuntimeException("kanaState: $kanaState")
            } // カナかなは互換性あるけど半角カナと全角かなは互換性ない感覚があるので reverse しない
            commitTextSKK(text)
            if (!mRegister.isOngoing) {
                mLastConversion = SKKEngine.ConversionInfo(
                    text, list, index, mKanjiKey.toString(), mOkurigana
                )
            }
            resumeCompletion()

            if (state.isSequential) return
            reset()
            changeState(kanaState)
        }
    }

    private fun updateCompletionCursor() = pickCompletion(mIndex, commit = false)

    fun pickCompletion(index: Int, unregister: Boolean = false, commit: Boolean = true) {
        var number = Regex("\\d+(\\.\\d+)?").find(engine.mKanjiKey)
        val rawCompletion = mList?.get(index) ?: return
        val s = rawCompletion.replace(Regex("#")) {
            number?.let { n ->
                val v = n.value
                number = n.next()
                v
            } ?: "#"
        }
        dLog("pickCompletion s=$s from $mList (unregister=$unregister)")
        val c = mCompletionList?.get(index) ?: return
        dLog("pickCompletion c=$c from $mCompletionList")
        // c を入力して s になるイメージなので基本的に両者は同じだが
        // c が ill で s が I'll になるような、入力した文字まで変化する場合がある

        engine.apply {
            when (state) {
                SKKAbbrevState -> {
                    setComposingTextSKK(s)
                    mKanjiKey.setLength(0)
                    mKanjiKey.append(s)
                    if (commit) startConversion(mKanjiKey)
                }

                SKKPreeditState, SKKOkuriganaState -> {
                    val hira =
                        if (kanaState is SKKHiraganaState) s else katakana2hiragana(s).orEmpty()
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
                            startConversion(mKanjiKey)
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
                            confirm.confirmUnregister(
                                engine,
                                "/${removeAnnotation(rawCompletion)}/"
                            ) {
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
                    (state as SKKASCIIState).deleteSurroundingText(this@apply, slim)
                    reset()
                }
            }
        }
    }

    internal fun narrow(hint: String) {
        dLog("narrowCandidates: hint: $hint, mKanjiKey: $mQuery")
        if (SKKNarrowingState.mOriginalCandidates == null) {
            SKKNarrowingState.mOriginalCandidates = mList
        }
        val candidates = SKKNarrowingState.mOriginalCandidates ?: return

        val narrowed = if (hint.isEmpty()) candidates else {
            val hintKanjiList = engine.find(hint).map {
                processConcatAndMore(removeAnnotation(it), "")
            }
            dLog("narrowCandidates: hintKanjiList: $hintKanjiList")
            // hint("おとこ") -> hintKanjiList(["男", "漢", "♂"]) 注釈なし
            // mQuery("かんじ") -> candidates("漢字; 注釈も含む", "幹事", "監事", "感じ")
            // -> narrowed(["漢字; 注釈も含む"]) 「漢」で合致
            candidates.filter { str ->
                hintKanjiList.any { unannotated -> str.contains(unannotated) }
                        // ひらがなかカタカナでヒントを含むstrもOK
                        || str.contains(hint)
                        || hiragana2katakana(hint).let { !it.isNullOrEmpty() && str.contains(it) }
            }
        }

        if (narrowed.isNotEmpty()) {
            mList = narrowed
            mIndex = 0
            setView(narrowed, mQuery)
        } else dLog("narrowCandidates: no entries")
        updateComposingText()
    }

    internal fun complete(str: String) {
        if (mSuspended) return
        val oldComList = mCompletionList?.toList()
        val oldCanList = mList?.toList()
        mJob.cancel()
        mJob.invokeOnCompletion {
            mJob = MainScope().launch(Dispatchers.Default) {
                @Throws(CancellationException::class)
                fun ensureCont() {
                    if (oldComList != mCompletionList || oldCanList != mList)
                        throw CancellationException()
                    ensureActive()
                }

                if (str.isEmpty()) {
                    delay(150.milliseconds) // 短時間に連続で実行されると画面がチラつく
                    ensureCont()
                    withContext(Dispatchers.Main) { service.clearCandidatesView() }
                    return@launch
                }

                val set = mutableSetOf<Pair<String, String>>()
                val elapsed = measureTime {
                    for (dict in engine.mDictList) {
                        // 字数を増やさずに変換できるものを最優先
                        val fuzzyFurther = if (str == "emoji" || !skkPrefs.fuzzySuggestion) false
                        else withTimeoutOrNull(1000.milliseconds) {
                            // 重い処理: 価値があるので数は少なくてもいいがタイムアウトが必要
                            500 > measureTime {
                                val goal: Int = skkPrefs.candidatesNormalLines * 7 / str.length
                                for (fuzzyStr in fuzzy(str)) {
                                    if (set.size >= goal) break
                                    ensureCont()
                                    if (dict.getCandidates(fuzzyStr).isNullOrEmpty()) continue
                                    val shownStr =
                                        if (service.isHiragana) fuzzyStr
                                        else hiragana2katakana(fuzzyStr, reversed = true).orEmpty()
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
                            if (fuzzyFurther) withTimeoutOrNull(500.milliseconds) {
                                for (fuzzyStr in fuzzy(it)) {
                                    if (set.size > goal) break
                                    ensureCont()
                                    addFound(this@launch, set, fuzzyStr, dict)
                                }
                            }
                        }
                    }
                }
                delay(150.milliseconds - elapsed)
                ensureCont() // 短時間に連続で実行されないよう最新のみ有効に

                val uniqueSet = set.distinctBy { it.second }
                val (completionList, list) = uniqueSet.unzip()
                mCompletionList = completionList
                mList = list

                mQuery = str
                mIndex = 0
                setView(mList, str)
            }
            mJob.start()
        }
    }

    internal fun completeASCII() {
        if (engine.state !is SKKASCIIState) return
        MainScope().launch(Dispatchers.Default) {
            delay(50.milliseconds) // バックスペースなどの処理が間に合っていないことがあるので
            if (engine.state is SKKASCIIState)
                complete((engine.state as SKKASCIIState).getPrefix(engine))
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
        val keys = dictionary.findKeys(scope, key)
        if (engine.kanaState is SKKHiraganaState) {
            target.addAll(keys)
        } else {
            keys.mapTo(target) { (first, second) ->
                first to hiragana2katakana(second, reversed = true).orEmpty()
            }
        }
    }
}

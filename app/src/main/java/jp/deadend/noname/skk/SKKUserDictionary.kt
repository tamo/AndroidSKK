package jp.deadend.noname.skk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class SKKUserDictionary private constructor(
    override var mStore: SKKStore?,
    override val mIsASCII: Boolean,
    override val mFilePath: String,
    private val mBtreeName: String
) : SKKDictionaryInterface {
    override val mLock = ReentrantLock()
    private var mOldKey: String = ""
    private var mOldValue: String = ""

    class Entry(
        val candidates: List<String>,
        val okuriganaBlocks: List<Pair<String, String>>
    )

    private fun parseValue(key: String, value: String): Entry? {
        // 正規表現で "/送/" と "/[り/送/]/" を拾う
        val (candidates, okuriganaStrings) =
            Regex("""((?<=/)[^\[\]/;][^/]*?(?=/)(?!]/))|((?<=/\[).+?(?=/]/))""")
                .findAll(value).map { it.value }
                .partition { !it.contains("/") }

        if (candidates.isEmpty()) {
            SKKLog.e("Invalid value found: Key=$key value=$value")
            return null
        }

        val okuriganaBlocks = okuriganaStrings.mapNotNull { block ->
            block.split('/').let { pair ->
                if (pair.size == 2) pair[0] to pair[1]
                else null.also { SKKLog.e("Invalid: Key=$key okuriganaBlock=$block in $value") }
            }
        }

        return Entry(candidates, okuriganaBlocks)
    }

    fun getEntry(rawKey: String, rawValue: String? = null): Entry? = mLock.withLock {
        val key = katakana2hiragana(rawKey)
        val value = rawValue ?: mStore?.get(key) ?: return@withLock null
        parseValue(key, value)
    }

    override fun getCandidates(rawKey: String): List<String>? = mLock.withLock {
        val key = katakana2hiragana(rawKey)
        val value = mStore?.get(key) ?: return@withLock null
        val entry = parseValue(key, value) ?: return@withLock null

        if (mIsASCII) {
            // ASCII 辞書の場合は /freq1/word1/freq2/word2/ という形式
            // 奇数番目が頻度、偶数番目が単語。頻度で降順ソートして単語のみ返す。
            entry.candidates.asSequence().chunked(2)
                .mapNotNull {
                    if (it.size == 2) it[1] to (it[0].toIntOrNull() ?: 0)
                    else null
                }
                .sortedByDescending { it.second }
                .map { it.first }
                .distinct().toList()
        } else {
            entry.candidates.distinct()
        }
    }

    fun getAllCandidates(): List<String> = mLock.withLock {
        val store = mStore ?: return@withLock listOf()
        val list = mutableListOf<String>()
        val browser = store.cursor() ?: return@withLock listOf()
        while (true) {
            val tuple = browser.next() ?: break
            parseValue(tuple.key, tuple.value)?.candidates?.let { candidates ->
                if (mIsASCII) {
                    list.addAll(candidates.filterIndexed { index, _ -> index % 2 == 1 })
                } else {
                    list.addAll(candidates)
                }
            }
        }
        return@withLock list.distinct()
    }

    fun addEntry(key: String, value: String, okurigana: String) = mLock.withLock {
        val store = mStore ?: return@withLock
        val hiraganaKey = katakana2hiragana(key)
        val oldVal: String? = store.get(hiraganaKey)

        val newVal = if (mIsASCII) {
            val s = removeAnnotation(value)
            var currentFreq = 160
            val otherCandidates = mutableListOf<Pair<Int, String>>()

            oldVal?.let { parseValue(hiraganaKey, it) }?.candidates?.chunked(2)?.forEach {
                if (it.size == 2) {
                    val freq = it[0].toIntOrNull() ?: 0
                    val word = it[1]
                    if (removeAnnotation(word) == s) {
                        currentFreq = freq.coerceAtLeast(160)
                    } else {
                        otherCandidates.add(freq to word)
                    }
                }
            }

            val result = StringBuilder("/")
            result.append(currentFreq).append("/").append(value).append("/")
            for (pair in otherCandidates) {
                result.append(pair.first).append("/").append(pair.second).append("/")
            }
            result.toString()
        } else {
            oldVal?.let { parseValue(hiraganaKey, it) }?.let { entry ->
                val candidates =
                    listOf(value)
                        .plus(entry.candidates)
                        .distinctBy { candidate -> removeAnnotation(candidate) }

                val okuriganaBlocks =
                    (if (okurigana.isEmpty()) emptyList() else listOf(okurigana to value))
                        .plus(entry.okuriganaBlocks)
                        .distinctBy { (okuri, kanji) -> okuri to removeAnnotation(kanji) }

                candidates.joinToString("/", prefix = "/", postfix = "/") +
                        okuriganaBlocks.joinToString("") { (okuri, kanji) -> "[$okuri/$kanji/]/" }
            } ?: ("/$value/" + if (okurigana.isNotEmpty()) "[$okurigana/$value/]/" else "")
        }

        mOldKey = hiraganaKey
        mOldValue = oldVal.orEmpty()
        store.set(hiraganaKey, newVal).commit()
    }

    fun removeEntry(key: String, value: String, okurigana: String) = mLock.withLock {
        val store = mStore ?: return@withLock
        val hiraganaKey = katakana2hiragana(key)
        // 「だい4かい」がなければ「だい#かい」を削除する
        val (usedKey, oldVal) = store.get(hiraganaKey)?.let { hiraganaKey to it }
            ?: hiraganaKey.replace(Regex("\\d+(\\.\\d+)?"), "#")
                .let { it to store.get(it) }
        val entry = oldVal?.let { parseValue(usedKey, it) } ?: return@withLock

        val newVal = if (mIsASCII) {
            val rawVal = removeAnnotation(value)
            val remaining = entry.candidates.chunked(2).filter {
                it.size == 2 && removeAnnotation(it[1]) != rawVal
            }
            if (remaining.isEmpty()) ""
            else remaining.joinToString("/", prefix = "/", postfix = "/") { "${it[0]}/${it[1]}" }
        } else {
            val candidates = entry.candidates.toMutableList() // 送/遅/贈;ユーザー辞書にも注釈がある
            val okuriganaBlocks =
                entry.okuriganaBlocks.toMutableList() // [ら/送/]/[り/送/]/[る/送;注釈もありうる?/]
            val rawVal = removeAnnotation(value) // 注釈を無視して探す

            if (okuriganaBlocks.isEmpty() || !okuriganaBlocks.removeIf { pair ->
                    pair.first == okurigana && removeAnnotation(pair.second) == rawVal
                } // 送りブロックが残らない場合は丸ごと消す
            ) candidates.removeIf { old -> removeAnnotation(old) == rawVal }

            candidates.joinToString("/", prefix = "/", postfix = "/") +
                    okuriganaBlocks.joinToString("") { (okuri, kanji) -> "[$okuri/$kanji/]/" }
        }

        if (newVal.isEmpty() || (Regex("/*").matchEntire(newVal) != null)) {
            store.delete(usedKey)
        } else {
            store.set(usedKey, newVal)
        }.commit()
    }


    fun rollBack() = mLock.withLock {
        if (mOldKey.isEmpty()) return@withLock

        if (mOldValue.isEmpty()) {
            mStore?.delete(mOldKey)
        } else {
            mStore?.set(mOldKey, mOldValue)
        }?.commit()

        mOldValue = ""
        mOldKey = ""
    }

    override fun close() = mLock.withLock {
        super.close()
        mOldKey = ""
        mOldValue = ""
        mStore = null
    }

    fun reopen() {
        runCatching {
            close()
            runBlocking(Dispatchers.IO) {
                openDB(mFilePath, mBtreeName, writable = true).let { newStore ->
                    mLock.withLock { mStore = newStore }
                }
            }
        }.onFailure { SKKLog.e("Error in reopening the dictionary $mFilePath", it) }
    }

    companion object {
        fun newInstance(
            context: SKKService,
            filePath: String,
            btreeName: String,
            isASCII: Boolean
        ): SKKUserDictionary? {
            val mvFile = File("$filePath.mv")
            val dbFile = File("$filePath.db")
            val dictName = dbFile.nameWithoutExtension

            if (dictName != context.getString(R.string.dict_name_user) &&
                !mvFile.exists() && !dbFile.exists()
            ) context.extractDictionary(dictName)

            return runCatching {
                val store = runBlocking(Dispatchers.IO) {
                    openDB(filePath, btreeName, writable = true)
                }
                SKKUserDictionary(store, isASCII, filePath, btreeName)
            }.onFailure { SKKLog.e("Error in opening the dictionary", it) }.getOrNull()
        }
    }
}

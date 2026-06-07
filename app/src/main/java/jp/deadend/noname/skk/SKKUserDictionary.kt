package jp.deadend.noname.skk

import android.util.Log
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
            Log.e("SKK", "Invalid value found: Key=$key value=$value")
            return null
        }

        val okuriganaBlocks = okuriganaStrings.mapNotNull { block ->
            block.split('/').let { pair ->
                if (pair.size == 2) pair[0] to pair[1]
                else null.also { Log.e("SKK", "Invalid: Key=$key okuriganaBlock=$block in $value") }
            }
        }

        return Entry(candidates, okuriganaBlocks)
    }

    fun getEntry(rawKey: String, rawValue: String? = null): Entry? = mLock.withLock {
        val key = katakana2hiragana(rawKey) ?: return@withLock null
        val value = rawValue ?: mStore?.get(key) ?: return@withLock null
        parseValue(key, value)
    }

    override fun getCandidates(rawKey: String): List<String>? =
        getEntry(rawKey)?.candidates?.distinct()

    fun addEntry(key: String, value: String, okurigana: String) = mLock.withLock {
        val store = mStore ?: return@withLock
        val hiraganaKey = katakana2hiragana(key) ?: return@withLock
        val oldVal: String? = store.get(hiraganaKey)

        val newVal = oldVal?.let { parseValue(hiraganaKey, it) }?.let { entry ->
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

        mOldKey = hiraganaKey
        mOldValue = oldVal.orEmpty()
        store.set(hiraganaKey, newVal).commit()
    }

    fun removeEntry(key: String, value: String, okurigana: String) = mLock.withLock {
        val store = mStore ?: return@withLock
        val hiraganaKey = katakana2hiragana(key) ?: return@withLock
        // 「だい4かい」がなければ「だい#かい」を削除する
        val (usedKey, oldVal) = store.get(hiraganaKey)?.let { hiraganaKey to it }
            ?: hiraganaKey.replace(Regex("\\d+(\\.\\d+)?"), "#")
                .let { it to store.get(it) }
        val entry = oldVal?.let { parseValue(usedKey, it) } ?: return@withLock

        val candidates = entry.candidates.toMutableList() // 送/遅/贈;ユーザー辞書にも注釈がある
        val okuriganaBlocks = entry.okuriganaBlocks.toMutableList() // [ら/送/]/[り/送/]/[る/送;注釈もありうる?/]
        val rawVal = value.takeWhile { it != ';' } // 注釈を無視して探す

        if (okuriganaBlocks.isEmpty() || !okuriganaBlocks.removeIf { pair ->
                pair.first == okurigana && pair.second.takeWhile { it != ';' } == rawVal
            } // 送りブロックが残らない場合は丸ごと消す
        ) candidates.removeIf { old -> old.takeWhile { it != ';' } == rawVal }

        val newVal = candidates.joinToString("/", prefix = "/", postfix = "/") +
                okuriganaBlocks.joinToString("") { (okuri, kanji) -> "[$okuri/$kanji/]/" }

        if (newVal.isEmpty() || (Regex("/*").matchEntire(newVal) != null)) {
            store.delete(usedKey)
        } else {
            store.set(usedKey, newVal)
        }.commit()
    }

    fun replaceEntry(key: String, value: String) = mLock.withLock {
        val store = mStore ?: return@withLock
        val hiraganaKey = katakana2hiragana(key) ?: return@withLock
        // ここは再変換と関係ないので mOldKey / mOldValue を更新しない
        if (value.isEmpty() || (Regex("/*").matchEntire(value) != null)) {
            store.delete(hiraganaKey)
        } else {
            store.set(hiraganaKey, value)
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
        try {
            close()
            runBlocking(Dispatchers.IO) {
                openDB(mFilePath, mBtreeName, writable = true).let { newStore ->
                    mLock.withLock { mStore = newStore }
                }
            }
        } catch (e: Exception) {
            Log.e("SKK", "Error in reopening the dictionary $mFilePath: $e")
            if (BuildConfig.DEBUG) throw e
        }
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
            if (isASCII && !mvFile.exists() && !dbFile.exists()) {
                context.extractDictionary(dbFile.nameWithoutExtension)
            }
            return try {
                val store = runBlocking(Dispatchers.IO) {
                    openDB(filePath, btreeName, writable = true)
                }
                SKKUserDictionary(store, isASCII, filePath, btreeName)
            } catch (e: Exception) {
                Log.e("SKK", "Error in opening the dictionary: $e")
                null
            }
        }
    }
}

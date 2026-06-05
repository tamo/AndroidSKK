package jp.deadend.noname.skk

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class SKKUserDictionary private constructor(
    override var mStore: SKKDictionaryStore?,
    override val mIsASCII: Boolean,
    private val mDictFile: String,
    private val mBtreeName: String
) : SKKDictionaryInterface {
    override val mMutex = Mutex()
    private var mOldKey: String = ""
    private var mOldValue: String = ""

    class Entry(
        val candidates: List<String>,
        val okuriganaBlocks: List<Pair<String, String>>
    )

    fun getEntry(rawKey: String, rawValue: String? = null): Entry? {
        val key = katakana2hiragana(rawKey) ?: return null
        val value: String = rawValue ?: mStore?.find(key) ?: return null

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
                else null
                    .also { Log.e("SKK", "Invalid: Key=$key okuriganaBlock=$block in $value") }
            }
        }

        return Entry(candidates, okuriganaBlocks)
    }

    override fun getCandidates(rawKey: String): List<String>? =
        getEntry(rawKey)?.candidates?.distinct()

    fun addEntry(key: String, value: String, okurigana: String) {
        val store = mStore ?: return
        val oldVal: String? = store.find(key)

        val newVal = getEntry(key, oldVal)?.let { entry ->
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

        safeRun {
            mOldKey = key
            mOldValue = oldVal.orEmpty()
            mStore?.insert(key, newVal)
            mStore?.commit()
        }
    }

    fun removeEntry(key: String, value: String, okurigana: String) {
        val entry = getEntry(key) ?: getEntry(
            // 「だい4かい」がなければ「だい#かい」を削除する
            key.replace(Regex("\\d+(\\.\\d+)?"), "#")
        ) ?: return
        val candidates = entry.candidates.toMutableList() // 送/遅/贈;ユーザー辞書にも注釈がある
        val okuriganaBlocks = entry.okuriganaBlocks.toMutableList() // [ら/送/]/[り/送/]/[る/送;注釈もありうる?/]
        val rawVal = value.takeWhile { it != ';' } // 注釈を無視して探す

        if (okuriganaBlocks.isEmpty() || !okuriganaBlocks.removeIf { pair ->
                pair.first == okurigana && pair.second.takeWhile { it != ';' } == rawVal
            } // 送りブロックが残らない場合は丸ごと消す
        ) candidates.removeIf { old -> old.takeWhile { it != ';' } == rawVal }

        val newVal = candidates.fold("/") { acc, str -> "$acc$str/" } +
                okuriganaBlocks.fold("") { acc, pair -> "$acc[${pair.first}/${pair.second}/]/" }
        replaceEntry(key, newVal)
    }

    fun replaceEntry(key: String, value: String) {
        safeRun {
            // ここは再変換と関係ないので mOldKey / mOldValue を更新しない
            if (value.isEmpty() || Regex("/*").matchEntire(value) != null) {
                mStore?.remove(key)
            } else {
                mStore?.insert(key, value)
            }
            mStore?.commit()
        }
    }

    fun rollBack() {
        if (mOldKey.isEmpty()) return

        safeRun {
            if (mOldValue.isEmpty()) {
                mStore?.remove(mOldKey)
            } else {
                mStore?.insert(mOldKey, mOldValue)
            }
            mStore?.commit()
        }

        mOldValue = ""
        mOldKey = ""
    }

    override fun close() {
        safeRun { mStore?.commit() }
        super.close()
        mOldKey = ""
        mOldValue = ""
        mStore = null
    }

    fun reopen() {
        close()
        mStore = runBlocking { openDB(mDictFile, mBtreeName, writable = true) }
    }

    private inline fun <T> safeRun(crossinline block: () -> T): T =
        runBlocking(Dispatchers.IO) { mMutex.withLock { block() } }

    companion object {
        fun newInstance(
            context: SKKService,
            mDictFile: String,
            btreeName: String,
            isASCII: Boolean
        ): SKKUserDictionary? {
            val mvFile = File("$mDictFile.mv")
            val dbFile = File("$mDictFile.db")
            if (isASCII && !mvFile.exists() && !dbFile.exists()) {
                context.extractDictionary(dbFile.nameWithoutExtension)
            }
            try {
                val store = runBlocking { openDB(mDictFile, btreeName, writable = true) }
                return SKKUserDictionary(store, isASCII, mDictFile, btreeName)
            } catch (e: Exception) {
                Log.e("SKK", "Error in opening the dictionary: $e")
                return null
            }
        }
    }
}

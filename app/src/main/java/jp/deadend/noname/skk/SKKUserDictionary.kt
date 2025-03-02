package jp.deadend.noname.skk

import android.util.Log
import jdbm.RecordManager
import jdbm.RecordManagerFactory
import jdbm.btree.BTree
import jdbm.helper.StringComparator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class SKKUserDictionary private constructor(
    override var mRecMan: RecordManager?,
    override var mBTree: BTree<String, String>?,
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

    fun getEntry(rawKey: String): Entry? {
        val key = katakana2hiragana(rawKey) ?: return null
        val value: String = mBTree?.find(key) ?: return null

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
        mOldKey = key
        val newVal = StringBuilder()
        val entry = getEntry(key)

        if (entry == null) {
            newVal.append("/", value, "/")
            if (okurigana.isNotEmpty()) newVal.append("[", okurigana, "/", value, "/]/")
            mOldValue = ""
        } else {
            val candidates = entry.candidates.toMutableList()
            candidates.remove(value)
            candidates.add(0, value)

            val okrs = mutableListOf<Pair<String, String>>()
            if (okurigana.isNotEmpty()) {
                var matched = false
                for (pair in entry.okuriganaBlocks) {
                    if (pair.first == okurigana && pair.second == value) {
                        okrs.add(0, pair)
                        matched = true
                    } else {
                        okrs.add(pair)
                    }
                }
                if (!matched) {
                    okrs.add(Pair(okurigana, value))
                }
            }

            for (str in candidates) {
                newVal.append("/", str)
            }
            for (pair in okrs) {
                newVal.append("/[", pair.first, "/", pair.second, "/]")
            }
            newVal.append("/")

            mOldValue = mBTree?.find(key).orEmpty()
        }

        safeRun {
            mBTree?.insert(key, newVal.toString(), true)
            mRecMan?.commit()
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

        val newVal = candidates.fold("") { acc, str -> "$acc/$str" } +
                okuriganaBlocks.fold("") { acc, pair -> "$acc/[${pair.first}/${pair.second}/]" } +
                "/"
        replaceEntry(key, newVal)
    }

    fun replaceEntry(key: String, value: String) {
        safeRun {
            // ここは再変換と関係ないので mOldKey / mOldValue を更新しない
            if (value.isEmpty() || Regex("/*").matchEntire(value) != null) {
                mBTree?.remove(key)
            } else {
                mBTree?.insert(key, value, true)
            }
            mRecMan?.commit()
        }
    }

    fun rollBack() {
        if (mOldKey.isEmpty()) return

        safeRun {
            if (mOldValue.isEmpty()) {
                mBTree?.remove(mOldKey)
            } else {
                mBTree?.insert(mOldKey, mOldValue, true)
            }
            mRecMan?.commit()
        }

        mOldValue = ""
        mOldKey = ""
    }

    override fun close() {
        safeRun { mRecMan?.commit() }
        super.close()
        mOldKey = ""
        mOldValue = ""
        mRecMan = null
        mBTree = null
    }

    fun reopen() {
        close()
        openDB(mDictFile, mBtreeName).let {
            mRecMan = it.first
            mBTree = it.second
        }
    }

    private inline fun <T> safeRun(crossinline block: () -> T): T =
        runBlocking(Dispatchers.IO) { mMutex.withLock { block() } }

    companion object {
        fun openDB(
            filename: String,
            btreeName: String
        ): Pair<RecordManager, BTree<String, String>> {
            val recMan = RecordManagerFactory.createRecordManager(filename)
            val recID = recMan.getNamedObject(btreeName)
            if (recID == 0L) {
                val btree = BTree<String, String>(recMan, StringComparator())
                recMan.setNamedObject(btreeName, btree.recordId)
                recMan.commit()
                dLog("New user dictionary created")
                return recMan to btree
            }
            return recMan to BTree<String, String>().load(recMan, recID)
        }

        fun newInstance(
            context: SKKService,
            mDictFile: String,
            btreeName: String,
            isASCII: Boolean
        ): SKKUserDictionary? {
            val dbFile = File("$mDictFile.db")
            if (isASCII && !dbFile.exists()) {
                context.extractDictionary(dbFile.nameWithoutExtension)
            }
            try {
                val (recMan, btree) = openDB(mDictFile, btreeName)
                return SKKUserDictionary(recMan, btree, isASCII, mDictFile, btreeName)
            } catch (e: Exception) {
                Log.e("SKK", "Error in opening the dictionary: $e")
                return null
            }
        }
    }
}
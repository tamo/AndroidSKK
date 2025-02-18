package jp.deadend.noname.skk

import android.util.Log
import jdbm.RecordManager
import jdbm.RecordManagerFactory
import jdbm.btree.BTree
import jdbm.helper.StringComparator
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File

class SKKUserDictionary private constructor(
    override var mRecMan: RecordManager,
    override var mRecID: Long,
    override var mBTree: BTree<String, String>,
    override val mIsASCII: Boolean,
    private val mDicFile: String,
    private val mBtreeName: String
) : SKKDictionaryInterface {
    override var mIsLocked = false

    private var mOldKey: String = ""
    private var mOldValue: String = ""

    class Entry(
        val candidates: MutableList<String>,
        val okuriganaBlocks: MutableList<Pair<String, String>>
    )

    fun getEntry(rawKey: String): Entry? {
        val key = katakana2hiragana(rawKey) ?: return null
        val cd = mutableListOf<String>()
        val okr = mutableListOf<Pair<String, String>>()

        val value: String? = safeRun { mBTree.find(key) }
        if (value == null) return null

        val valList = value.substring(1, value.length - 1).split("/")  // 先頭と最後のスラッシュをとってから分割
        if (valList.isEmpty()) {
            Log.e("SKK", "Invalid value found: Key=$key value=$value")
            return null
        }

        // 送りがなブロック以外の部分を追加
        valList.takeWhile { !it.startsWith("[") }.forEach { cd.add(it) }

        if (value.contains("/[") && value.contains("/]")) {
            // 送りがなブロック
            val regex = """/\[.*?/]""".toRegex()
            regex.findAll(value).forEach { result: MatchResult ->
                result.value.substring(2, result.value.length - 2) // "/[" と "/]" をとる
                    .split('/')
                    .let { pair ->
                        if (pair.size == 2) {
                            okr.add(pair[0] to pair[1])
                        } else {
                            Log.e("SKK", "Invalid: Key=$key value=$value (${result.value})")
                        }
                    }
            }
        }

        return Entry(cd, okr)
    }

    override fun getCandidates(rawKey: String): List<String>? =
        getEntry(rawKey)?.candidates?.distinct()

    fun addEntry(key: String, value: String, okurigana: String?) {
        mOldKey = key
        val newVal = StringBuilder()
        val entry = getEntry(key)

        if (entry == null) {
            newVal.append("/", value, "/")
            if (okurigana != null) newVal.append("[", okurigana, "/", value, "/]/")
            mOldValue = ""
        } else {
            val candidates = entry.candidates
            candidates.remove(value)
            candidates.add(0, value)

            val okrs = mutableListOf<Pair<String, String>>()
            if (okurigana != null) {
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

            safeRun {
                mOldValue = mBTree.find(key)
            }
        }

        safeRun {
            mBTree.insert(key, newVal.toString(), true)
            mRecMan.commit()
        }
    }

    fun removeEntry(key: String, value: String, okurigana: String?) {
        val entry = getEntry(key) ?: return
        val candidates = entry.candidates // 送/遅/贈;ユーザー辞書にも注釈がある
        val okuriganaBlocks = entry.okuriganaBlocks // [ら/送/]/[り/送/]/[る/送;注釈もありうる?/]
        val rawVal = value.takeWhile { it != ';' } // 注釈を無視して探す

        if (!okuriganaBlocks.removeIf { pair ->
                pair.first == okurigana && pair.second.takeWhile { it != ';' } == rawVal
            } || okuriganaBlocks.isEmpty()) { // 送りブロックが残らない場合は丸ごと消す、でいいのか?
            candidates.removeIf { old ->
                old.takeWhile { it != ';' } == rawVal
            }
        }

        val newVal = candidates.fold("") { acc, str -> "$acc/$str" } +
                okuriganaBlocks.fold("") { acc, pair -> "$acc/[${pair.first}/${pair.second}/]" } +
                "/"
        replaceEntry(key, newVal)
    }

    fun replaceEntry(key: String, value: String) {
        safeRun {
            // ここは再変換と関係ないので old を更新しない
            //mOldKey = key
            //mOldValue = mBTree.find(key)
            if (value.isEmpty() || Regex("/*").matchEntire(value) != null) {
                mBTree.remove(key)
            } else {
                mBTree.insert(key, value, true)
            }
            mRecMan.commit()
        }
    }

    fun rollBack() {
        if (mOldKey.isEmpty()) return

        safeRun {
            if (mOldValue.isEmpty()) {
                mBTree.remove(mOldKey)
            } else {
                mBTree.insert(mOldKey, mOldValue, true)
            }
            mRecMan.commit()
        }

        mOldValue = ""
        mOldKey = ""
    }

    fun commitChanges() {
        safeRun {
            mRecMan.commit()
        }
    }

    fun reopen() {
        safeRun {
            close()
            mOldKey = ""
            mOldValue = ""
            openDB(mDicFile, mBtreeName).let {
                mRecMan = it.first
                mRecID = it.second
                mBTree = it.third
            }
        }
    }

    private fun <T> safeRun(block: () -> T): T {
        return try {
            runBlocking { while (mIsLocked) delay(50) }
            mIsLocked = true
            val result = block()
            mIsLocked = false
            result
        } catch (e: Exception) {
            mIsLocked = false
            throw RuntimeException(e)
        }
    }

    companion object {
        fun openDB(
            filename: String,
            btreeName: String
        ): Triple<RecordManager, Long, BTree<String, String>> {
            val recMan = RecordManagerFactory.createRecordManager(filename)
            val recID = recMan.getNamedObject(btreeName)
            if (recID == 0L) {
                val btree = BTree<String, String>(recMan, StringComparator())
                recMan.setNamedObject(btreeName, btree.recordId)
                recMan.commit()
                dLog("New user dictionary created")
                return Triple(recMan, recID, btree)
            }
            return Triple(recMan, recID, BTree<String, String>().load(recMan, recID))
        }

        fun newInstance(
            context: SKKService,
            mDicFile: String,
            btreeName: String,
            isASCII: Boolean
        ): SKKUserDictionary? {
            val dbFile = File("$mDicFile.db")
            if (isASCII && !dbFile.exists()) {
                context.extractDictionary(dbFile.nameWithoutExtension)
            }
            try {
                val (recMan, recID, btree) = openDB(mDicFile, btreeName)
                return SKKUserDictionary(recMan, recID, btree, isASCII, mDicFile, btreeName)
            } catch (e: Exception) {
                Log.e("SKK", "Error in opening the dictionary: $e")
                return null
            }
        }
    }
}
package jp.deadend.noname.skk

import android.util.Log
import jdbm.RecordManager
import jdbm.RecordManagerFactory
import jdbm.btree.BTree
import jdbm.helper.StringComparator
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File

class SKKUserDictionary private constructor (
    override var mRecMan: RecordManager,
    override var mRecID: Long,
    override var mBTree: BTree<String, String>,
    override val mIsASCII: Boolean,
    val mDicFile: String,
    val mBtreeName: String
): SKKDictionaryInterface {
    override var mIsLocked = false

    private var mOldKey: String = ""
    private var mOldValue: String = ""

    class Entry(val candidates: MutableList<String>, val okuriBlocks: MutableList<Pair<String, String>>)

    fun getEntry(rawKey: String): Entry? {
        val key = katakana2hiragana(rawKey) ?: return null
        val cd = mutableListOf<String>()
        val okr = mutableListOf<Pair<String, String>>()

        val value: String? = safeRun { mBTree.find(key) }
        if (value == null) { return null }

        val valList = value.substring(1, value.length - 1).split("/")  // 先頭と最後のスラッシュをとってから分割
        if (valList.isEmpty()) {
            Log.e("SKK", "Invalid value found: Key=$key value=$value")
            return null
        }

        // 送りがなブロック以外の部分を追加
        valList.takeWhile { !it.startsWith("[") }.forEach { cd.add(it) }

        if (value.contains("/[") && value.contains("/]")) {
            // 送りがなブロック
            val regex = """/\[.*?/\]""".toRegex()
            regex.findAll(value).forEach { result: MatchResult ->
                result.value.substring(2, result.value.length - 2) // "/[" と "/]" をとる
                    .split('/')
                    .let { pair ->
                        if (pair.size == 2) {
                            okr.add(pair[0] to pair[1])
                        } else {
                            Log.e("SKK", "Invalid value found: Key=$key value=$value (${result.value})")
                        }
                    }
            }
        }

        return Entry(cd, okr)
    }

    fun addEntry(key: String, value: String, okuri: String?) {
        mOldKey = key
        val newVal = StringBuilder()
        val entry = getEntry(key)

        if (entry == null) {
            newVal.append("/", value, "/")
            if (okuri != null) newVal.append("[", okuri, "/", value, "/]/")
            mOldValue = ""
        } else {
            val cands = entry.candidates
            cands.remove(value)
            cands.add(0, value)

            val okrs = mutableListOf<Pair<String, String>>()
            if (okuri != null) {
                var matched = false
                for (pair in entry.okuriBlocks) {
                    if (pair.first == okuri && pair.second == value) {
                        okrs.add(0, pair)
                        matched = true
                    } else {
                        okrs.add(pair)
                    }
                }
                if (!matched) { okrs.add(Pair(okuri, value)) }
            }

            for (str in cands) { newVal.append("/", str) }
            for (pair in okrs) { newVal.append("/[", pair.first, "/", pair.second, "/]") }
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

    fun removeEntry(key: String) {
        safeRun {
            mOldValue = mBTree.find(key)?.also {
                mBTree.remove(key)
                mRecMan.commit()
            } ?: ""
            mOldKey = key
        }
    }

    fun replaceEntry(key: String, value: String, okuri: String?) {
        removeEntry(key)
        val oldValue = mOldValue
        addEntry(key, value, okuri)
        mOldValue = oldValue
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

    private fun <T>safeRun(block: () -> T): T {
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
        fun openDB(filename: String, btreeName: String): Triple<RecordManager, Long, BTree<String, String>> {
            val recman = RecordManagerFactory.createRecordManager(filename)
            val recid = recman.getNamedObject(btreeName)
            if (recid == 0L) {
                val btree = BTree<String, String>(recman, StringComparator())
                recman.setNamedObject(btreeName, btree.recordId)
                recman.commit()
                dlog("New user dictionary created")
                return Triple(recman, recid, btree)
            }
            return Triple(recman, recid, BTree<String, String>().load(recman, recid))
        }
        fun newInstance(context: SKKService, mDicFile: String, btreeName: String, isASCII: Boolean): SKKUserDictionary? {
            val dbFile = File("$mDicFile.db")
            if (!dbFile.exists()) {
                context.extractDictionary(dbFile.nameWithoutExtension)
            }
            try {
                val (recman, recid, btree) = openDB(mDicFile, btreeName)
                return SKKUserDictionary(recman, recid, btree, isASCII, mDicFile, btreeName)
            } catch (e: Exception) {
                Log.e("SKK", "Error in opening the dictionary: $e")
                return null
            }
        }
    }
}
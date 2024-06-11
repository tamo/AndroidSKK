package jp.deadend.noname.skk

import android.util.Log
import java.io.IOException
import jdbm.btree.BTree
import jdbm.helper.StringComparator
import jdbm.RecordManager
import jdbm.RecordManagerFactory

class SKKUserDictionary private constructor (
    override val mRecMan: RecordManager,
    override val mRecID: Long,
    override val mBTree: BTree
): SKKDictionaryInterface {
    private var mOldKey: String = ""
    private var mOldValue: String = ""

    class Entry(val candidates: MutableList<String>, val okuriBlocks: MutableList<Pair<String, String>>)

    fun getEntry(key: String): Entry? {
        val cd = mutableListOf<String>()
        val okr = mutableListOf<Pair<String, String>>()

        val value: String?
        try {
            value = mBTree.find(key) as? String
        } catch (e: IOException) {
            throw RuntimeException(e)
        }

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
                okr.add(
                    result.value.substring(2, result.value.length - 2) // "/[" と "/]" をとる
                        .split('/')
                        .let { Pair(it[0], it[1]) }
                )
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

            try {
                mOldValue = mBTree.find(key) as String
            } catch (e: IOException) {
                throw RuntimeException(e)
            }
        }

        try {
            mBTree.insert(key, newVal.toString(), true)
            mRecMan.commit()
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
    }

    fun rollBack() {
        if (mOldKey.isEmpty()) return

        try {
            if (mOldValue.isEmpty()) {
                mBTree.remove(mOldKey)
            } else {
                mBTree.insert(mOldKey, mOldValue, true)
            }
            mRecMan.commit()
        } catch (e: IOException) {
            throw RuntimeException(e)
        }

        mOldValue = ""
        mOldKey = ""
    }

    fun commitChanges() {
        try {
            mRecMan.commit()
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    companion object {
        fun newInstance(mDicFile: String, btreeName: String): SKKUserDictionary? {
            try {
                val recman = RecordManagerFactory.createRecordManager(mDicFile)
                val recid = recman.getNamedObject(btreeName)
                if (recid == 0L) {
                    val btree = BTree.createInstance(recman, StringComparator())
                    recman.setNamedObject(btreeName, btree.recid)
                    recman.commit()
                    dlog("New user dictionary created")
                    return SKKUserDictionary(recman, recid, btree)
                } else {
                    return SKKUserDictionary(recman, recid, BTree.load(recman, recid))
                }
            } catch (e: Exception) {
                Log.e("SKK", "Error in opening the dictionary: $e")
                return null
            }
        }
    }
}
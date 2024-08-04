package jp.deadend.noname.skk

import android.util.Log
import java.io.IOException
import jdbm.btree.BTree
import jdbm.helper.StringComparator
import jdbm.RecordManager
import jdbm.RecordManagerFactory
import java.io.File
import kotlin.coroutines.CoroutineContext

class SKKUserDictionary private constructor (
    private val mService: SKKService,
    override val mRecMan: RecordManager,
    override val mRecID: Long,
    override val mBTree: BTree<String, String>,
    override val mIsASCII: Boolean
): SKKDictionaryInterface {
    override val coroutineContext: CoroutineContext
        get() = mService.coroutineContext

    private var mOldKey: String = ""
    private var mOldValue: String = ""

    class Entry(val candidates: MutableList<String>, val okuriBlocks: MutableList<Pair<String, String>>)

    fun getEntry(rawKey: String): Entry? {
        val key = katakana2hiragana(rawKey) ?: return null
        val cd = mutableListOf<String>()
        val okr = mutableListOf<Pair<String, String>>()

        val value: String?
        try {
            value = mBTree.find(key)
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
                mOldValue = mBTree.find(key)
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
        fun newInstance(context: SKKService, mDicFile: String, btreeName: String, isASCII: Boolean): SKKUserDictionary? {
            if (isASCII && !File("$mDicFile.db").exists()) {
                context.extractDictionary()
            }
            try {
                val recman = RecordManagerFactory.createRecordManager(mDicFile)
                val recid = recman.getNamedObject(btreeName)
                if (recid == 0L) {
                    val btree = BTree<String, String>(recman, StringComparator())
                    recman.setNamedObject(btreeName, btree.recordId)
                    recman.commit()
                    dlog("New user dictionary created")
                    return SKKUserDictionary(context, recman, recid, btree, isASCII)
                } else {
                    return SKKUserDictionary(context, recman, recid, BTree<String, String>().load(recman, recid), isASCII)
                }
            } catch (e: Exception) {
                Log.e("SKK", "Error in opening the dictionary: $e")
                return null
            }
        }
    }
}
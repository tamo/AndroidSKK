package jp.deadend.noname.skk

import android.util.Log
import java.io.IOException
import jdbm.RecordManager
import jdbm.RecordManagerFactory
import jdbm.btree.BTree

class SKKDictionary private constructor (
        override val mRecMan: RecordManager,
        override val mRecID: Long,
        override val mBTree: BTree<String, String>
): SKKDictionaryInterface {
    override val mIsASCII = false
    override var mIsLocked = false

    override fun getCandidates(rawKey: String): List<String>? {
        val key = katakana2hiragana(rawKey) ?: return null
        val value: String?
        try {
            value = mBTree.find(key)
        } catch (e: IOException) {
            Log.e("SKK", "Error in getCandidates(): $e")
            throw RuntimeException(e)
        }

        if (value == null) return null

        val valArray = value.substring(1).split("/").dropLastWhile { it.isEmpty() }
        // 先頭のスラッシュをとってから分割
        if (valArray.isEmpty()) {
            Log.e("SKK", "Invalid value found: Key=$key value=$value")
            return null
        }

        return valArray
    }

    companion object {
        fun newInstance(mDicFile: String, btreeName: String): SKKDictionary? {
            return try {
                val recman = RecordManagerFactory.createRecordManager(mDicFile)
                val recid = recman.getNamedObject(btreeName)
                val btree = BTree<String, String>().load(recman, recid)

                SKKDictionary(recman, recid, btree)
            } catch (e: Exception) {
                Log.e("SKK", "Error in opening the dictionary: $e")

                null
            }
        }
    }
}
package jp.deadend.noname.skk

import android.util.Log
import jdbm.RecordManager
import jdbm.btree.BTree
import kotlinx.coroutines.sync.Mutex
import java.io.IOException

class SKKDictionary private constructor(
    override val mRecMan: RecordManager,
    override val mBTree: BTree<String, String>
) : SKKDictionaryInterface {
    override val mIsASCII = false
    override val mMutex = Mutex()

    override fun getCandidates(rawKey: String): List<String>? {
        val key = katakana2hiragana(rawKey) ?: return null
        val value = try {
            mBTree.find(key)
        } catch (e: IOException) {
            Log.e("SKK", "Error in getCandidates(): $e")
            throw RuntimeException(e)
        } ?: return null

        val valArray = value.trim('/').split('/')
        if (valArray.isEmpty()) {
            Log.e("SKK", "Invalid value found: Key=$key value=$value")
            return null
        }

        return valArray
    }

    companion object {
        fun newInstance(mDictFile: String, btreeName: String): SKKDictionary? {
            var recMan: RecordManager? = null
            return try {
                val props = java.util.Properties().apply {
                    setProperty(jdbm.RecordManagerOptions.DISABLE_TRANSACTIONS, "true")
                }
                recMan = jdbm.RecordManagerFactory.createRecordManager(mDictFile, props)
                val recID = recMan.getNamedObject(btreeName)
                val btree = BTree<String, String>().load(recMan, recID)

                SKKDictionary(recMan, btree)
            } catch (e: Exception) {
                Log.e("SKK", "Error in opening the dictionary: $e")
                try {
                    recMan?.close()
                } catch (ee: Exception) {
                    Log.e("SKK", "Error in closing the dictionary: $ee")
                }

                null
            }
        }
    }
}
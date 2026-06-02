package jp.deadend.noname.skk

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import java.io.File

class SKKDictionary private constructor(
    override val mStore: SKKDictionaryStore
) : SKKDictionaryInterface {
    override val mIsASCII = false
    override val mMutex = Mutex()

    override fun getCandidates(rawKey: String): List<String>? {
        val key = katakana2hiragana(rawKey) ?: return null
        val value = mStore.find(key) ?: return null

        val valArray = value.trim('/').split('/')
        if (valArray.isEmpty()) {
            Log.e("SKK", "Invalid value found: Key=$key value=$value")
            return null
        }

        return valArray
    }

    companion object {
        fun newInstance(mDictFile: String, btreeName: String, toaster: () -> Unit): SKKDictionary? {
            var store: SKKDictionaryStore? = null
            return try {
                val mvFile = File("$mDictFile.mv")
                if (mvFile.exists()) {
                    store = MVStoreDictionaryStore.open("$mDictFile.mv", btreeName)
                } else if (File("$mDictFile.db").exists()) {
                    toaster.invoke()
                    store = openDB(mDictFile, btreeName)
                }

                if (store != null) {
                    SKKDictionary(store)
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e("SKK", "Error in opening the dictionary: $e")
                store?.close()
                null
            }
        }
    }
}

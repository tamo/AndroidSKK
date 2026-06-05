package jp.deadend.noname.skk

import android.util.Log
import kotlinx.coroutines.runBlocking
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
            return try {
                if (File("$mDictFile.db").exists() && !File("$mDictFile.mv").exists()) {
                    toaster.invoke()
                }
                val store = runBlocking { openDB(mDictFile, btreeName, writable = false) }
                SKKDictionary(store)
            } catch (e: Exception) {
                Log.e("SKK", "Error in opening the dictionary: $e")
                null
            }
        }
    }
}

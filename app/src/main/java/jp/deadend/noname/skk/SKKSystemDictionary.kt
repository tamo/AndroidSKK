package jp.deadend.noname.skk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class SKKSystemDictionary private constructor(
    override var mStore: SKKStore?,
    override val mFilePath: String
) : SKKDictionaryInterface {
    override val mIsASCII = false
    override val mLock = ReentrantLock()

    override fun getCandidates(rawKey: String): List<String>? {
        val key = katakana2hiragana(rawKey) ?: return null
        val value = mLock.withLock { mStore?.get(key) } ?: return null

        val valArray = value.trim('/').split('/')
        if (valArray.isEmpty()) {
            SKKLog.e("Invalid value found: Key=$key value=$value")
            return null
        }

        return valArray
    }

    override fun close() = mLock.withLock {
        mStore?.close() // readonly なので commit 不要
        mStore = null
    }

    companion object {
        fun newInstance(
            filePath: String, btreeName: String, migrationNotifier: () -> Unit
        ): SKKSystemDictionary? {
            return runCatching {
                if (File("$filePath.db").exists() && !File("$filePath.mv").exists()) {
                    migrationNotifier.invoke()
                }
                val store = runBlocking(Dispatchers.IO) {
                    openDB(filePath, btreeName, writable = false)
                }
                SKKSystemDictionary(store, filePath)
            }.onFailure { SKKLog.e("Error in opening the dictionary", it) }.getOrNull()
        }
    }
}

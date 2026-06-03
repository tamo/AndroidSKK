package jp.deadend.noname.skk

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import kotlin.math.max

@Throws(IOException::class)
private fun appendToEntry(key: String, value: String, store: SKKDictionaryStore) {
    val oldVal = store.find(key)

    if (oldVal != null) {
        val valList = value.trim('/').split('/')
        val oldValList = oldVal.trim('/').split('/')

        val newValue = valList.union(oldValList).joinToString("/", prefix = "/", postfix = "/")
        store.insert(key, newValue)
    } else {
        store.insert(key, value)
    }
}

internal fun isTextDictInEucJp(inputStream: InputStream): Boolean {
    val decoder = Charset.forName("EUC-JP").newDecoder()
    decoder.onMalformedInput(CodingErrorAction.REPORT)
    decoder.onUnmappableCharacter(CodingErrorAction.REPORT)
    var failed = false
    BufferedReader(InputStreamReader(inputStream, decoder)).use { bufferedReader ->
        var count = 0
        var prevLine = "0: (nothing has been read)"
        try {
            bufferedReader.forEachLine { line ->
                prevLine = "${++count}: $line"
                // if (count > 1000) { return@forEachLine }
            }
        } catch (_: CharacterCodingException) {
            dLog("euc checker: failed after $prevLine")
            failed = true
        }
        dLog("euc checker: read $count lines")
    }
    return !failed
}

@Throws(IOException::class)
internal fun loadFromTextDict(
    inputStream: InputStream,
    charset: String,
    isWordList: Boolean,
    store: SKKDictionaryStore,
    overwrite: Boolean,
    callback: (count: Int) -> Unit
) {
    val decoder = Charset.forName(charset).newDecoder()
    decoder.onMalformedInput(CodingErrorAction.REPORT)
    decoder.onUnmappableCharacter(CodingErrorAction.REPLACE)

    val loadSKKLine = fun(line: String) {
        if (line.startsWith(";;")) return
        val parts = line.split(' ', limit = 2)
        if (parts.size == 2) {
            val (key, value) = parts
            if (overwrite) {
                store.insert(key, value)
            } else {
                appendToEntry(key, value, store)
            }
        }
    }

    var prevKey = ""
    var prevFreq = 0
    var isShortCut: Boolean
    val loadWordListLine = fun(line: String) {
        val csv = line.split(',', '=')
        if (csv.size < 4) return
        val key = csv[1]
        val freq = if (csv[0] == " word" && csv[2] == "f") {
            val f = try {
                csv[3].toInt()
            } catch (_: NumberFormatException) {
                0
            }
            prevKey = key
            prevFreq = f
            isShortCut = false
            if ("not_a_word" in csv) {
                if (overwrite) store.remove(key)
                return
            }
            f
        } else if (csv[0] == "  shortcut" && csv[2] == "f") {
            isShortCut = true
            try {
                csv[3].toInt()
            } catch (_: NumberFormatException) {
                prevFreq
            }
        } else return
        if (freq == 0) return
        if (!isShortCut && overwrite) {
            store.insert(key, "/$freq/$key/")
        } else {
            val pairs = (store.find(prevKey).orEmpty())
                .split('/').asSequence().filter { it.isNotEmpty() }
                .zipWithNext().filterIndexed { index, _ -> index % 2 == 0 }
                .associate { (f, s) ->
                    s to try {
                        f.toInt()
                    } catch (_: NumberFormatException) {
                        0
                    }
                }.toMutableMap()
            val oldFreq = pairs[key] ?: 0
            pairs[key] = max(freq, oldFreq)
            pairs.flatMap { (k, v) -> listOf(v.toString(), k) }
                .reduce { acc, s -> "$acc/$s" }
                .let { store.insert(prevKey, "/$it/") }
        }
    }

    val loadLine = if (isWordList) loadWordListLine else loadSKKLine
    BufferedReader(InputStreamReader(inputStream, decoder)).use { bufferedReader ->
        var count = 0
        bufferedReader.forEachLine { line ->
            loadLine(line)
            callback(count)
            if (++count % 1000 == 0) {
                store.commit()
            }
        }
        store.commit()
    }
}

interface SKKDictionaryInterface {
    val mStore: SKKDictionaryStore?
    val mIsASCII: Boolean
    val mMutex: Mutex

    suspend fun findKeys(scope: CoroutineScope, rawKey: String): List<Pair<String, String>> {
        val key = katakana2hiragana(rawKey) ?: return listOf()
        val list = mutableListOf<Triple<String, String, Int>>()
        val browser: SKKDictionaryBrowser

        val topFreq = mutableListOf<Int>()

        try {
            browser = mStore?.browse(key) ?: return listOf()

            // 絵文字は1500ほどあるし CandidatesView の行数が可変になったので多めが良さそう
            while (list.size < if (mIsASCII) 1500 else 15) {
                scope.ensureActive()

                val resultTuple = browser.getNext() ?: break
                val str = resultTuple.key
                when {
                    !str.startsWith(key) -> break

                    mIsASCII -> {
                        resultTuple.value
                            .split('/')
                            .filter { it.isNotEmpty() }
                            .zipWithNext()
                            .filterIndexed { index, _ -> index % 2 == 0 }
                            .forEach {
                                val freq = it.first.toInt() +
                                        if (str == key) 50 else 0 // 完全一致を優先
                                if (freq !in topFreq) {
                                    topFreq.add(freq)
                                    topFreq.sortDescending()
                                    if (topFreq.size > 5) topFreq.removeAt(topFreq.lastIndex)
                                }
                                if (freq >= topFreq.last()) { // 頻度が5位に入らなければだめ
                                    list.add(Triple(str, it.second, freq))
                                }
                            }
                        continue
                    }

                    // 送りありエントリ
                    !isAlphabet(str.first().code) && isAlphabet(str.last().code) ->
                        if (skkPrefs.completeOkuri) {
                            str.dropLast(1).let { list.add(Triple(it, str, 0)) }
                        } else continue

                    else -> list.add(Triple(str, str, 0))
                }
            }
        } catch (_: CancellationException) {
            return listOf()
        } catch (e: Exception) {
            Log.e("SKK", "Error in findKeys(): ${e.stackTrace}")
            throw RuntimeException(e)
        }
        if (mIsASCII) {
            list.sortByDescending { it.third }
        }

        return list.map { it.first to it.second }
    }

    fun getCandidates(rawKey: String): List<String>? = null

    fun close() {
        runBlocking(Dispatchers.IO) {
            mMutex.withLock {
                mStore?.let {
                    it.commit()
                    it.close()
                }
            }
        }
    }
}

internal fun openDB(
    filename: String,
    btreeName: String
): SKKDictionaryStore {
    val mvFile = File("$filename.mv")
    if (mvFile.exists()) {
        return MVStoreDictionaryStore.open(mvFile.absolutePath, btreeName)
    }

    val dbFile = File("$filename.db")
    if (dbFile.exists()) {
        // Migrate from JDBM to MVStore
        dLog("Migrating $filename to MVStore...")
        val jdbmStore = JDBMDictionaryStore.open(filename, btreeName)
        val mvStore = MVStoreDictionaryStore.open(mvFile.absolutePath, btreeName)

        val browser = jdbmStore.browse()
        if (browser != null) {
            var count = 0
            while (true) {
                val result = browser.getNext() ?: break
                mvStore.insert(result.key, result.value)
                if (++count % 1000 == 0) mvStore.commit()
            }
        }
        mvStore.commit()
        jdbmStore.close()

        // Clean up JDBM files
        dbFile.delete()
        File("$filename.lg").delete()
        dLog("Migration finished.")
        return mvStore
    }

    // Create new MVStore
    return MVStoreDictionaryStore.open(mvFile.absolutePath, btreeName)
}

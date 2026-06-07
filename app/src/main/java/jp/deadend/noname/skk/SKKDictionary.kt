package jp.deadend.noname.skk

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds

@Throws(IOException::class)
private fun appendToEntry(key: String, value: String, store: SKKStore) {
    val oldVal = store.get(key)

    if (oldVal != null) {
        val valList = value.trim('/').split('/')
        val oldValList = oldVal.trim('/').split('/')

        val newValue = valList.union(oldValList).joinToString("/", prefix = "/", postfix = "/")
        store.set(key, newValue)
    } else {
        store.set(key, value)
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
    store: SKKStore,
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
                store.set(key, value)
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
                if (overwrite) store.delete(key)
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
            store.set(key, "/$freq/$key/")
        } else {
            val pairs = (store.get(prevKey).orEmpty())
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
                .let { store.set(prevKey, "/$it/") }
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
    val mStore: SKKStore?
    val mFilePath: String
    val mIsASCII: Boolean
    val mLock: ReentrantLock

    suspend fun findKeys(scope: CoroutineScope, rawKey: String)
            : List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val store = mStore ?: return@withContext listOf()
        val key = katakana2hiragana(rawKey) ?: return@withContext listOf()
        val list = mutableListOf<Triple<String, String, Int>>()
        val browser: SKKStoreCursor

        val topFreq = mutableListOf<Int>()

        try {
            browser = mLock.withLock { store.cursor(key) } ?: return@withContext listOf()

            // 絵文字は1500ほどあるし CandidatesView の行数が可変になったので多めが良さそう
            while (list.size < if (mIsASCII) 1500 else 15) {
                scope.ensureActive()
                if (mStore !== store) throw CancellationException()

                val resultTuple = mLock.withLock { browser.next() } ?: break
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
            return@withContext listOf()
        } catch (e: Exception) {
            Log.e("SKK", "Error in findKeys(): ${e.stackTrace}")
            return@withContext listOf()
        }
        if (mIsASCII) {
            list.sortByDescending { it.third }
        }

        list.map { it.first to it.second }
    }

    fun getCandidates(rawKey: String): List<String>? = null

    fun close() = mLock.withLock { mStore?.commit()?.close() }
}

internal suspend fun openDB(
    filePath: String,
    btreeName: String,
    writable: Boolean = true
): SKKStore = withContext(Dispatchers.IO) {
    var retryCount = 0
    val maxRetries = 10
    val retryDelay = 200

    while (true) {
        try {
            val mvFile = File("$filePath.mv")
            if (mvFile.exists()) {
                return@withContext MVStoreStore.open(
                    mvFile.absolutePath,
                    btreeName,
                    writable
                )
            }

            val dbFile = File("$filePath.db")
            if (dbFile.exists()) {
                // Migrate from JDBM to MVStore
                dLog("Migrating $filePath to MVStore...")
                val jdbmStore = JDBMStore.open(filePath, btreeName)
                val mvStore =
                    MVStoreStore.open(mvFile.absolutePath, btreeName, writable = true)

                val browser = jdbmStore.cursor()
                if (browser != null) {
                    var count = 0
                    while (true) {
                        val result = browser.next() ?: break
                        mvStore.set(result.key, result.value)
                        if (++count % 1000 == 0) mvStore.commit()
                    }
                }
                mvStore.commit()
                jdbmStore.close()

                // Clean up JDBM files
                dbFile.delete()
                File("$filePath.lg").delete()
                dLog("Migration finished.")

                // Return a store with requested writability
                if (writable) return@withContext mvStore else {
                    mvStore.close()
                    return@withContext MVStoreStore.open(
                        mvFile.absolutePath,
                        btreeName,
                        writable = false
                    )
                }
            }

            // Create new MVStore
            return@withContext MVStoreStore.open(mvFile.absolutePath, btreeName, writable)
        } catch (e: Exception) {
            if (e.toString().contains("locked") && retryCount < maxRetries) {
                retryCount++
                dLog("openDB: database locked, retrying ($retryCount/$maxRetries)...")
                delay(retryDelay.milliseconds)
            } else {
                throw e
            }
        }
    }
    @Suppress("UNREACHABLE_CODE")
    throw IllegalStateException("Should not reach here")
}

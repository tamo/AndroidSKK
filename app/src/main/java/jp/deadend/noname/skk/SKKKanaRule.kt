package jp.deadend.noname.skk

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException

object SKKKanaRule {
    internal const val INTERNAL_FILE_NAME = "kana-rule.conf"
    internal const val DEFAULT_RULE_FILE = "skk-kana-rule.conf"
    private const val AZIK_RULE_FILE = "azik-kana-rule.conf"
    private const val MAX_FILE_SIZE = 1 * 1024 * 1024 // 1MB

    fun getInternalFile(context: Context): File {
        val file = File(context.filesDir, INTERNAL_FILE_NAME)
        if (!file.exists()) clear(context)
        return file
    }

    /**
     * kana-rule.conf テキストをパースして Map<入力列, ひらがな> を返す。
     * フォーマット: 入力,ひらがな
     * (# で始まる行と空行は無視する。)
     */
    fun parse(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val fields = trimmed.split(",")
            if (fields.size < 2) continue
            val input = fields[0].trim()
                .replace("&sharp;", "#", ignoreCase = true)
                .replace("&comma;", ",", ignoreCase = true)
            val hiragana = fields[1].trim()
            if (input.isNotEmpty() && hiragana.isNotEmpty()) {
                result[input] = hiragana
            }
        }
        return result
    }

    /**
     * 内部ストレージの kana-rule.conf を読み込んでパース結果を返す。
     * ファイルが存在しない場合は null を返す。
     */
    fun loadFromInternalStorage(context: Context): Map<String, String>? {
        val file = getInternalFile(context)
        if (file.length() > MAX_FILE_SIZE) {
            SKKLog.e("loadFromInternalStorage() Error: File is too large")
            return null
        }
        return runCatching {
            parse(file.bufferedReader(Charsets.UTF_8).use { it.readText() })
        }.onFailure { SKKLog.e("loadFromInternalStorage() Error", it) }.getOrNull()
    }

    /**
     * SAF URI から内部ストレージへ kana-rule.conf をコピーする。
     * 成功したら true、失敗したら false を返す。
     */
    fun saveFromUri(context: Context, uri: Uri): Boolean {
        // Check size first to prevent OOM
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (sizeIndex != -1 && cursor.moveToFirst()) {
                    val size = cursor.getLong(sizeIndex)
                    if (size > MAX_FILE_SIZE) {
                        SKKLog.e("saveFromUri() Error: File is too large ($size bytes)")
                        return false
                    }
                }
            }
        }.onFailure { SKKLog.w("Failed to check file size", it) }

        return runCatching {
            val text = context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } ?: return false
            getInternalFile(context).writeText(text, Charsets.UTF_8)
            true
        }.getOrElse { e ->
            when (e) {
                is IOException -> SKKLog.e("saveFromUri() Error", e)
                is SecurityException -> SKKLog.e("saveFromUri() Security Error", e)
                else -> throw e
            }
            false
        }
    }

    /**
     * 内部ストレージの kana-rule.conf を初期化する。
     */
    fun clear(context: Context) {
        val file = File(context.filesDir, INTERNAL_FILE_NAME)
        val defaultRule = context.resources.assets.open(DEFAULT_RULE_FILE)
            .bufferedReader().use { it.readText() }
        file.writeText(defaultRule)
    }

    fun loadAzik(context: Context) {
        val file = File(context.filesDir, INTERNAL_FILE_NAME)
        val defaultRule = context.resources.assets.open(AZIK_RULE_FILE)
            .bufferedReader().use { it.readText() }
        file.writeText(defaultRule)
    }
}

package jp.deadend.noname.skk

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.IOException

object SKKKanaRule {
    internal const val INTERNAL_FILE_NAME = "kana-rule.conf"
    private const val MAX_FILE_SIZE = 1 * 1024 * 1024 // 1MB

    fun exists(context: Context): Boolean =
        getInternalFile(context).exists()

    private fun getInternalFile(context: Context): File =
        File(context.filesDir, INTERNAL_FILE_NAME)

    /**
     * kana-rule.conf テキストをパースして Map<入力列, ひらがな> を返す。
     * フォーマット: 入力,ひらがな[,カタカナ]
     * # で始まる行と空行は無視する。
     */
    fun parse(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val fields = trimmed.split(",")
            if (fields.size < 2) continue
            val input = fields[0].trim()
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
        if (!file.exists()) return null
        if (file.length() > MAX_FILE_SIZE) {
            Log.e("SKK", "SKKKanaRule#loadFromInternalStorage() Error: File is too large")
            return null
        }
        return try {
            parse(file.readText(Charsets.UTF_8))
        } catch (e: IOException) {
            Log.e("SKK", "SKKKanaRule#loadFromInternalStorage() Error: $e")
            null
        }
    }

    /**
     * SAF URI から内部ストレージへ kana-rule.conf をコピーする。
     * 成功したら true、失敗したら false を返す。
     */
    fun saveFromUri(context: Context, uri: Uri): Boolean {
        // Check size first to prevent OOM
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (sizeIndex != -1 && cursor.moveToFirst()) {
                    val size = cursor.getLong(sizeIndex)
                    if (size > MAX_FILE_SIZE) {
                        Log.e("SKK", "SKKKanaRule#saveFromUri() Error: File is too large ($size bytes)")
                        return false
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("SKK", "Failed to check file size: $e")
        }

        return try {
            val text = context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readText()
            } ?: return false
            getInternalFile(context).writeText(text, Charsets.UTF_8)
            true
        } catch (e: IOException) {
            Log.e("SKK", "SKKKanaRule#saveFromUri() Error: $e")
            false
        } catch (e: SecurityException) {
            Log.e("SKK", "SKKKanaRule#saveFromUri() Security Error: $e")
            false
        }
    }

    /**
     * 内部ストレージの kana-rule.conf を削除する。
     */
    fun clear(context: Context) {
        getInternalFile(context).delete()
    }
}

package jp.deadend.noname.skk

import android.content.Context
import android.net.Uri
import java.io.File

data class FlickKeyConfig(
    val label: String,
    val labels: Array<String>,
    val actions: Array<String>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FlickKeyConfig) return false
        return label == other.label &&
                labels.contentEquals(other.labels) &&
                actions.contentEquals(other.actions)
    }

    override fun hashCode(): Int {
        var result = label.hashCode()
        result = 31 * result + labels.contentHashCode()
        result = 31 * result + actions.contentHashCode()
        return result
    }

    companion object {
        fun createEmpty(): FlickKeyConfig = FlickKeyConfig("", Array(15) { "" }, Array(15) { "" })
    }
}

object SKKFlickRule {
    internal const val INTERNAL_FILE_NAME = "flick-rule.conf"
    private const val DEFAULT_RULE_FILE = "default-flick-rule.conf"
    private const val MAX_FILE_SIZE = 1 * 1024 * 1024 // 1MB

    fun getInternalFile(context: Context): File {
        val file = File(context.filesDir, INTERNAL_FILE_NAME)
        if (BuildConfig.DEBUG || !file.exists()) clear(context) // TODO: remove debug
        return file
    }

    fun parse(text: String): Map<String, Map<Int, Pair<FlickKeyConfig, FlickKeyConfig?>>> {
        val result = mutableMapOf<String, MutableMap<Int, Pair<FlickKeyConfig, FlickKeyConfig?>>>()
        var currentSection = "JP"

        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                currentSection = trimmed.removeSurrounding("[", "]")
                continue
            }

            val fields = trimmed.split(",").map {
                it.replace("(Comma)", ",")
                    .replace("\\n", "\n")
            }
            if (fields.size < 2) continue

            var idx = 0
            val idStr = fields[idx++].trim()
            val isShifted = idStr.endsWith("S")
            val id = idStr.removeSuffix("S").toIntOrNull() ?: continue

            val label = fields[idx++]
            val labels = Array(15) { "" }
            val actions = Array(15) { "" }

            for (i in 0 until 15) {
                val labelIdx = idx + i * 2
                val actionIdx = idx + 1 + i * 2
                if (labelIdx < fields.size) labels[i] = fields[labelIdx].trim()
                if (actionIdx < fields.size) actions[i] = fields[actionIdx].trim()

                if (actions[i].isEmpty() && labels[i].isNotEmpty())
                    actions[i] = "(Commit)" +
                            if (labels[i].startsWith("(K)")) labels[i].removePrefix("(K)")
                            else labels[i]
            }

            val config = FlickKeyConfig(label, labels, actions)
            val sectionMap = result.getOrPut(currentSection) { mutableMapOf() }
            val currentPair = sectionMap[id] ?: (FlickKeyConfig.createEmpty() to null)
            sectionMap[id] = if (isShifted) {
                currentPair.first to config
            } else {
                config to currentPair.second
            }
        }
        return result
    }

    fun loadFromInternalStorage(context: Context): Map<String, Map<Int, Pair<FlickKeyConfig, FlickKeyConfig?>>>? {
        val file = getInternalFile(context)
        if (file.length() > MAX_FILE_SIZE) {
            SKKLog.e("SKKFlickRule: File too large")
            return null
        }
        return runCatching {
            parse(file.readText(Charsets.UTF_8))
        }.onFailure { SKKLog.e("SKKFlickRule: Parse error", it) }.getOrNull()
    }

    fun saveFromUri(context: Context, uri: Uri): Boolean {
        return runCatching {
            val text = context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } ?: return false
            getInternalFile(context).writeText(text, Charsets.UTF_8)
            true
        }.getOrElse { false }
    }

    fun clear(context: Context) {
        runCatching {
            val defaultRule = context.resources.assets.open(DEFAULT_RULE_FILE)
                .bufferedReader().use { it.readText() }
            File(context.filesDir, INTERNAL_FILE_NAME).writeText(defaultRule)
        }
    }
}

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
    internal const val SECTION_MAIN = "Main"
    internal const val SECTION_ASCII = "ASCII"
    internal const val SECTION_NUMBER = "Number"
    internal const val SECTION_VOICE = "Voice"

    internal const val MARKER_KATAKANA_ONLY = "(K)"

    internal const val ACTION_COMMIT = "(Commit)"
    internal const val ACTION_COMMIT_CANDIDATE = "(N)"
    internal const val ACTION_DEDUPE_N = "(n)"
    internal const val ACTION_ZENKAKU = "(Zenkaku)"
    internal const val ACTION_IN_ZENKAKU = "(Z)"
    internal const val ACTION_TOGGLE_SHIFT = "(ToggleShift)"
    internal const val ACTION_CAPS = "(Caps)"
    internal const val ACTION_KBD_QWERTY = "(KbdQwerty)"
    internal const val ACTION_ENTER = "(Enter)"
    internal const val ACTION_BACKSPACE = "(Backspace)"
    internal const val ACTION_DPAD_LEFT = "(DpadLeft)"
    internal const val ACTION_DPAD_RIGHT = "(DpadRight)"
    internal const val ACTION_DPAD_UP = "(DpadUp)"
    internal const val ACTION_DPAD_DOWN = "(DpadDown)"
    internal const val ACTION_SETTINGS = "(Settings)"
    internal const val ACTION_KBD_VOICE = "(KbdVoice)"
    internal const val ACTION_KBD_NUMBER = "(KbdNumber)"
    internal const val ACTION_RESET = "(Reset)"
    internal const val ACTION_HANKAKU_KANA = "(HankakuKana)"
    internal const val ACTION_SMALL_LAST = "(SmallLast)"
    internal const val ACTION_DAKUTEN_LAST = "(DakutenLast)"
    internal const val ACTION_HANDAKUTEN_LAST = "(HandakutenLast)"
    internal const val ACTION_TRANS_LAST = "(TransLast)"
    internal const val ACTION_SHIFT_LAST = "(ShiftLast)"
    internal const val ACTION_CANCEL = "(Cancel)"
    internal const val ACTION_EMOJI = "(Emoji)"
    internal const val ACTION_SYMBOL = "(Symbol)"
    internal const val ACTION_GOOGLE = "(Google)"
    internal const val ACTION_PASTE = "(Paste)"
    internal const val ACTION_SPEECH = "(Speech)"
    internal const val ACTION_MUSHROOM = "(Mushroom)"
    internal const val ACTION_SPACE = "(Space)"

    internal const val ICON_SHIFT = "(IconShift)"
    internal const val ICON_BACKSPACE = "(IconBackspace)"
    internal const val ICON_LEFT = "(IconLeft)"
    internal const val ICON_RIGHT = "(IconRight)"
    internal const val ICON_RETURN = "(IconReturn)"
    internal const val ICON_SPACE = "(IconSpace)"

    internal const val INTERNAL_FILE_NAME = "flick-rule.conf"
    private const val DEFAULT_RULE_FILE = "default-flick-rule.conf"
    private const val GODAN_RULE_FILE = "godan-flick-rule.conf"
    private const val GODAN_SIMPLE_RULE_FILE = "godan-simple-flick-rule.conf"
    private const val MAX_FILE_SIZE = 1 * 1024 * 1024 // 1MB

    fun getInternalFile(context: Context): File {
        val file = File(context.filesDir, INTERNAL_FILE_NAME)
        if (!file.exists()) clear(context)
        return file
    }

    fun parse(text: String): Map<String, Map<Int, Pair<FlickKeyConfig, FlickKeyConfig?>>> {
        val result = mutableMapOf<String, MutableMap<Int, Pair<FlickKeyConfig, FlickKeyConfig?>>>()
        var currentSection = SECTION_MAIN

        for (line in text.lines()) {
            // スペース等も使うので trim() しない
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                currentSection = line.removeSurrounding("[", "]")
                continue
            }

            val fields = line.split(",").map {
                it.replace("(Comma)", ",")
                    .replace("\\n", "\n")
            }
            if (fields.size < 2) continue

            var idx = 0
            val idStr = fields[idx++]
            val isShifted = idStr.endsWith("S")
            val id = idStr.removeSuffix("S").toIntOrNull() ?: continue

            val label = fields[idx++]
            val labels = Array(15) { "" }
            val actions = Array(15) { "" }

            for (i in 0 until 15) {
                val labelIdx = idx + i * 2
                val actionIdx = idx + 1 + i * 2
                if (labelIdx < fields.size) labels[i] = fields[labelIdx]
                if (actionIdx < fields.size) actions[i] = fields[actionIdx]

                if (actions[i].isEmpty() && labels[i].isNotEmpty())
                    actions[i] = ACTION_COMMIT + labels[i].removePrefix(MARKER_KATAKANA_ONLY)
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

    fun loadGodan(context: Context, simple: Boolean = false) = runCatching {
        val fileName = if (simple) GODAN_SIMPLE_RULE_FILE else GODAN_RULE_FILE
        val rule = context.resources.assets.open(fileName)
            .bufferedReader().use { it.readText() }
        File(context.filesDir, INTERNAL_FILE_NAME).writeText(rule)
    }.onFailure { SKKLog.e("loadGodan failed", it) }
}

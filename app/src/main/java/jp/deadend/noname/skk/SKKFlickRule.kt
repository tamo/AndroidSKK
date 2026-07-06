package jp.deadend.noname.skk

import android.content.Context
import android.net.Uri
import java.io.File

data class FlickKeyConfig(
    val label: String, val labels: Array<String>, val actions: Array<String>,
    val comments: List<String> = emptyList()
) {
    fun toMutable() =
        MutableFlickKeyConfig(label, labels.clone(), actions.clone(), comments.toMutableList())

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FlickKeyConfig) return false
        return label == other.label &&
                labels.contentEquals(other.labels) &&
                actions.contentEquals(other.actions) &&
                comments == other.comments
    }

    override fun hashCode(): Int {
        var result = label.hashCode()
        result = 31 * result + labels.contentHashCode()
        result = 31 * result + actions.contentHashCode()
        result = 31 * result + comments.hashCode()
        return result
    }

    companion object {
        fun createEmpty(): FlickKeyConfig =
            FlickKeyConfig("", Array(15) { "" }, Array(15) { "" })
    }
}

class MutableFlickKeyConfig(
    var label: String, var labels: Array<String>, var actions: Array<String>,
    val comments: MutableList<String> = mutableListOf()
) {
    fun toImmutable() = FlickKeyConfig(label, labels.clone(), actions.clone(), comments.toList())

    companion object {
        fun createEmpty(): MutableFlickKeyConfig =
            MutableFlickKeyConfig("", Array(15) { "" }, Array(15) { "" })
    }
}

data class FlickEntry(
    val id: Int, val normal: FlickKeyConfig, val shifted: FlickKeyConfig? = null,
    private val comments: List<String> = emptyList()
) {
    fun toMutable() =
        MutableFlickEntry(id, normal.toMutable(), shifted?.toMutable(), comments.toMutableList())
}

class MutableFlickEntry(
    var id: Int, var normal: MutableFlickKeyConfig, var shifted: MutableFlickKeyConfig? = null,
    private val comments: MutableList<String> = mutableListOf()
) {
    fun toImmutable() =
        FlickEntry(id, normal.toImmutable(), shifted?.toImmutable(), comments.toList())

    companion object {
        fun createEmpty(id: Int): MutableFlickEntry =
            MutableFlickEntry(id, MutableFlickKeyConfig.createEmpty())
    }
}

data class FlickSection(
    val name: String, val entries: Map<Int, FlickEntry> = emptyMap(),
    val comments: List<String> = emptyList()
) {
    fun toMutable() =
        MutableFlickSection(
            name, entries.mapValues { it.value.toMutable() }.toMutableMap(),
            comments.toMutableList()
        )
}

class MutableFlickSection(
    var name: String, val entries: MutableMap<Int, MutableFlickEntry> = mutableMapOf(),
    val comments: MutableList<String> = mutableListOf()
) {
    fun toImmutable() =
        FlickSection(name, entries.mapValues { it.value.toImmutable() }.toMap(), comments.toList())
}

data class FlickRule(val sections: Map<String, FlickSection> = emptyMap()) {
    fun toMutable() = MutableFlickRule(sections.mapValues { it.value.toMutable() }.toMutableMap())
}

class MutableFlickRule(val sections: MutableMap<String, MutableFlickSection> = mutableMapOf()) {
    fun toImmutable() = FlickRule(sections.mapValues { it.value.toImmutable() }.toMap())
}

object SKKFlickRule {
    internal const val SECTION_MAIN = "Main"
    internal const val SECTION_ASCII = "ASCII"
    internal const val SECTION_NUMBER = "Number"
    internal const val SECTION_VOICE = "Voice"

    internal const val MARKER_KATAKANA_ONLY = "(K)"

    internal const val ACTION_NOOP = "()"
    internal const val ACTION_COMMIT = "(Commit)"
    internal const val ACTION_COMMIT_CANDIDATE = "(N)"
    internal const val ACTION_DEDUPE_N = "(n)"
    internal const val ACTION_ZENKAKU = "(Zenkaku)"
    internal const val ACTION_IN_ZENKAKU = "(Z)"
    internal const val ACTION_TOGGLE_SHIFT = "(ToggleShift)"
    internal const val ACTION_CAPS = "(Caps)"
    internal const val ACTION_ASCII = "(Ascii)"
    internal const val ACTION_KBD_QWERTY = "(KbdQwerty)"
    internal const val ACTION_ENTER = "(Enter)"
    internal const val ACTION_BACKSPACE = "(Backspace)"
    internal const val ACTION_DELETE = "(Delete)"
    internal const val ACTION_DPAD_LEFT = "(DpadLeft)"
    internal const val ACTION_DPAD_RIGHT = "(DpadRight)"
    internal const val ACTION_DPAD_UP = "(DpadUp)"
    internal const val ACTION_DPAD_DOWN = "(DpadDown)"
    internal const val ACTION_SETTINGS = "(Settings)"
    internal const val ACTION_KBD_VOICE = "(KbdVoice)"
    internal const val ACTION_KBD_NUMBER = "(KbdNumber)"
    internal const val ACTION_RESET = "(Reset)"
    internal const val ACTION_ABBREV = "(Abbrev)"
    internal const val ACTION_KATAKANA = "(Katakana)"
    internal const val ACTION_HANKAKU_KANA = "(HankakuKana)"
    internal const val ACTION_TRANS_SMALL = "(TransSmall)"
    internal const val ACTION_TRANS_DAKUTEN = "(TransDakuten)"
    internal const val ACTION_TRANS_HANDAKUTEN = "(TransHandakuten)"
    internal const val ACTION_TRANS_AUTO = "(TransAuto)"
    internal const val ACTION_TRANS_SHIFT = "(TransShift)"
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
    internal const val ICON_DELETE = "(IconDelete)"
    internal const val ICON_LEFT = "(IconLeft)"
    internal const val ICON_RIGHT = "(IconRight)"
    internal const val ICON_RETURN = "(IconReturn)"
    internal const val ICON_SPACE = "(IconSpace)"

    internal const val ACTION_MOD_CTRL = "(C)"
    internal const val ACTION_MOD_ALT = "(A)"
    internal const val ACTION_MOD_META = "(M)"

    internal val ACTIONS = this::class.java.declaredFields
        .filter { it.name.startsWith("ACTION_") }
        .map { it.get(this) as String }
        .sortedByDescending { it.length }

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

    fun load(context: Context): MutableFlickRule =
        parse(getInternalFile(context).readText()).toMutable()

    fun serialize(rule: FlickRule): String {
        val sb = StringBuilder()
        val sections = listOf(SECTION_MAIN, SECTION_ASCII, SECTION_NUMBER, SECTION_VOICE)
        val otherSections = rule.sections.keys - sections.toSet()

        fun serializeSection(sectionName: String) {
            val section = rule.sections[sectionName] ?: return

            section.comments.forEach { sb.append(it).append("\n") }
            sb.append("[$sectionName]\n")

            fun String.cn(): String = replace(",", "(Comma)").replace("\n", "\\n")
            section.entries.entries.sortedBy { it.value.id }.forEach { (_, entry) ->
                fun serializeConfig(config: FlickKeyConfig, idStr: String) {
                    if (config.label.isEmpty() && config.labels.all { it.isEmpty() } && config.actions.all { it.isEmpty() }) return

                    config.comments.forEach { sb.append(it).append("\n") }
                    sb.append(idStr).append(",")

                    sb.append(config.label.cn())
                    for (i in 0 until 15) {
                        val label = config.labels[i]
                        val action = config.actions[i]
                        val autoAction = ACTION_COMMIT + label.removePrefix(MARKER_KATAKANA_ONLY)
                        sb.append(",").append(label.cn()).append(",").append(
                            if (action == autoAction && label.isNotEmpty()) "" else action.cn()
                        )
                    }
                    sb.append("\n")
                }
                serializeConfig(entry.normal, entry.id.toString())
                entry.shifted?.let { serializeConfig(it, "${entry.id}S") }
            }
        }

        sections.forEach { serializeSection(it) }
        otherSections.forEach { serializeSection(it) }

        return sb.toString()
    }

    private fun ensureStandardKeys(rule: MutableFlickRule) {
        val standardSections = listOf(SECTION_MAIN, SECTION_ASCII, SECTION_NUMBER, SECTION_VOICE)
        for (sectionName in standardSections) {
            val section = rule.sections[sectionName] ?: continue
            for (i in 0..19) {
                section.entries.getOrPut(i) { MutableFlickEntry.createEmpty(i) }
            }
            val hasGodan = section.entries.keys.any { it > 19 }
            if (hasGodan) for (i in 20..23) {
                section.entries.getOrPut(i) { MutableFlickEntry.createEmpty(i) }
            }
        }
    }

    internal fun parse(text: String): FlickRule {
        val rule = MutableFlickRule()
        var currentSection: MutableFlickSection? = null
        val pendingComments = mutableListOf<String>()

        for (line in text.lines()) {
            if (line.isBlank() || line.startsWith("#")) {
                pendingComments.add(line)
                continue
            }

            if (line.startsWith("[") && line.endsWith("]")) {
                val sectionName = line.removeSurrounding("[", "]")
                currentSection =
                    rule.sections.getOrPut(sectionName) { MutableFlickSection(sectionName) }
                currentSection.comments.addAll(pendingComments)
                pendingComments.clear()
                continue
            }

            if (currentSection == null) currentSection =
                rule.sections.getOrPut(SECTION_MAIN) { MutableFlickSection(SECTION_MAIN) }

            val fields = line.split(",").map {
                it.replace("(Comma)", ",")
                    .replace("\\n", "\n")
            }
            if (fields.size < 2) {
                pendingComments.clear()
                continue
            }

            var idx = 0
            val idStr = fields[idx++]
            val isShifted = idStr.endsWith("S")
            val id = idStr.removeSuffix("S").toIntOrNull() ?: run {
                pendingComments.clear()
                continue
            }

            val label = fields[idx++]
            val labels = Array(15) { "" }
            val actions = Array(15) { "" }

            for (i in 0 until 15) {
                val labelIdx = idx + i * 2
                val actionIdx = idx + 1 + i * 2
                if (labelIdx < fields.size) labels[i] = fields[labelIdx]
                if (actionIdx < fields.size) actions[i] = fields[actionIdx]

                if (actions[i].isEmpty() && labels[i].isNotEmpty()) {
                    actions[i] = ACTION_COMMIT + labels[i].removePrefix(MARKER_KATAKANA_ONLY)
                }
            }

            val config = MutableFlickKeyConfig(label, labels, actions)
            config.comments.addAll(pendingComments)
            pendingComments.clear()

            val entry = currentSection.entries.getOrPut(id) { MutableFlickEntry.createEmpty(id) }
            if (isShifted) entry.shifted = config else entry.normal = config
        }
        ensureStandardKeys(rule)
        return rule.toImmutable()
    }

    fun loadFromInternalStorage(context: Context): FlickRule? = runCatching {
        val file = getInternalFile(context)
        if (file.length() > MAX_FILE_SIZE) return null
            .also { SKKLog.w("SKKFlickRule: File too large") }
        parse(file.readText(Charsets.UTF_8))
    }.onFailure { SKKLog.e("SKKFlickRule: Parse error", it) }.getOrNull()

    fun saveFromUri(context: Context, uri: Uri): Boolean = runCatching {
        val text = context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } ?: return false
        getInternalFile(context).writeText(text, Charsets.UTF_8)
        true
    }.getOrElse { false }

    fun exportToUri(context: Context, uri: Uri, text: String): Boolean = runCatching {
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            stream.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
        } ?: return false
        true
    }.getOrElse { false }

    fun clear(context: Context) = runCatching {
        val defaultRule = context.resources.assets.open(DEFAULT_RULE_FILE)
            .bufferedReader().use { it.readText() }
        File(context.filesDir, INTERNAL_FILE_NAME).writeText(defaultRule)
    }.onFailure { SKKLog.e("clear failed", it) }

    fun loadGodan(context: Context, simple: Boolean = false) = runCatching {
        val fileName = if (simple) GODAN_SIMPLE_RULE_FILE else GODAN_RULE_FILE
        val rule = context.resources.assets.open(fileName)
            .bufferedReader().use { it.readText() }
        File(context.filesDir, INTERNAL_FILE_NAME).writeText(rule)
    }.onFailure { SKKLog.e("loadGodan failed", it) }
}

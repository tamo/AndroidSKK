package jp.deadend.noname.skk

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.addTextChangedListener
import jp.deadend.noname.skk.databinding.ActivityFlickRuleManagerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

class SKKFlickRuleManager : AppCompatActivity() {
    private lateinit var binding: ActivityFlickRuleManagerBinding
    private var service: SKKService? = null
    private var isModified = false
    private var ruleMap: MutableFlickRule = MutableFlickRule()
    private var currentSection = SKKFlickRule.SECTION_MAIN
    private var editingKey: Int? = null

    private val selectFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val success = SKKFlickRule.saveFromUri(this, uri)
            if (success) {
                isModified = true
                ruleMap = SKKFlickRule.load(this)
                updateEditorUI()
            } else {
                SimpleDialogFragment.newInstance(getString(R.string.error_kana_rule_load))
                    .show(supportFragmentManager, "dialog")
            }
        }
    }

    private val exportFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val text = if (binding.guiEditorContainer.isVisible) {
            SKKFlickRule.serialize(ruleMap.toImmutable())
        } else {
            binding.textEditor.text.toString()
        }
        if (SKKFlickRule.exportToUri(this, uri, text)) {
            val fileName = getFileNameFromUri(this, uri)
            SimpleDialogFragment.newInstance(
                getString(R.string.message_tools_written_to_external_storage, fileName)
            ).show(supportFragmentManager, "dialog")
        } else {
            SimpleDialogFragment.newInstance(
                getString(R.string.error_write_to_external_storage, "")
            ).show(supportFragmentManager, "dialog")
        }
    }

    private val editorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val keyIndex = editingKey ?: return@registerForActivityResult
            val flickIndex = data.getIntExtra(FlickRuleEditorActivity.EXTRA_FLICK_INDEX, 0)

            fun String?.nn(): String = this?.replace("\\n", "\n") ?: ""
            val nKeyLabel = data.getStringExtra(FlickRuleEditorActivity.EXTRA_NORMAL_KEY_LABEL).nn()
            val nLabel = data.getStringExtra(FlickRuleEditorActivity.EXTRA_NORMAL_LABEL).nn()
            val nAction = data.getStringExtra(FlickRuleEditorActivity.EXTRA_NORMAL_ACTION).nn()
            val sKeyLabel =
                data.getStringExtra(FlickRuleEditorActivity.EXTRA_SHIFTED_KEY_LABEL).nn()
            val sLabel = data.getStringExtra(FlickRuleEditorActivity.EXTRA_SHIFTED_LABEL).nn()
            val sAction = data.getStringExtra(FlickRuleEditorActivity.EXTRA_SHIFTED_ACTION).nn()

            updateRule(keyIndex, flickIndex, nKeyLabel, nLabel, nAction, sKeyLabel, sLabel, sAction)
            isModified = true
            updateKeyboardPreview()
        }
        editingKey = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent = Intent(this, SKKService::class.java)
        intent.putExtra(SKKService.KEY_COMMAND, SKKService.COMMAND_READ_PREFS)
        startService(intent)
        MainScope().launch(Dispatchers.Default) {
            service = SKKService.waitForInstance()
            while (service?.mFlickJPPreview == null) delay(100.milliseconds)
            withContext(Dispatchers.Main) { bindPreview() }
        }

        binding = ActivityFlickRuleManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout() or
                        WindowInsetsCompat.Type.ime()
            ).run { view.updatePadding(left, top, right, bottom) }
            binding.textEditor.let { editor ->
                editor.bringPointIntoView(editor.selectionEnd)
            }
            WindowInsetsCompat.CONSUMED
        }
        setSupportActionBar(binding.flickRuleManagerToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        ruleMap = SKKFlickRule.load(this)

        setupUI()
        updateEditorUI()
    }

    private fun setupUI() {
        binding.modeToggle.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.radioGuiMode) { // Text -> GUI
                val text = binding.textEditor.text.toString()
                ruleMap = SKKFlickRule.parse(text).toMutable()
                binding.guiEditorContainer.visibility = View.VISIBLE
                binding.textEditorContainer.visibility = View.GONE
                updateEditorUI()
            } else { // GUI -> Text
                binding.textEditor.setText(SKKFlickRule.serialize(ruleMap.toImmutable()))
                binding.guiEditorContainer.visibility = View.GONE
                binding.textEditorContainer.visibility = View.VISIBLE
            }
        }

        binding.checkUseGodan.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked) {
                val currentSect = ruleMap.sections[currentSection]
                val hasGodanKeys = (currentSect?.entries?.keys?.maxOrNull() ?: 0) > 19
                if (hasGodanKeys) {
                    val dialog = SimpleDialogFragment.newInstance(
                        getString(R.string.message_confirm_delete_godan_keys), true
                    )
                    dialog.setListener(object : SimpleDialogFragment.Listener {
                        override fun onPositiveClick() {
                            currentSect?.let { sect ->
                                sect.entries.keys.filter { it > 19 }
                                    .forEach { sect.entries.remove(it) }
                            }
                            isModified = true
                            updateKeyboardPreview()
                        }

                        override fun onNegativeClick() {
                            binding.checkUseGodan.isChecked = true
                        }
                    })
                    dialog.show(supportFragmentManager, "dialog")
                }
            } else {
                val currentSect =
                    ruleMap.sections.getOrPut(currentSection) { MutableFlickSection(currentSection) }
                var keysAdded = false
                for (i in 20..23) if (!currentSect.entries.containsKey(i)) {
                    currentSect.entries[i] = MutableFlickEntry.createEmpty(i)
                    keysAdded = true
                }
                if (keysAdded) isModified = true
                updateKeyboardPreview()
            }
        }

        binding.checkEnableAscii.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked) {
                binding.radioSectionAscii.visibility = View.GONE
                if (currentSection == SKKFlickRule.SECTION_ASCII) {
                    binding.radioSectionMain.isChecked = true
                }

                if (ruleMap.sections.containsKey(SKKFlickRule.SECTION_ASCII)) {
                    val dialog = SimpleDialogFragment.newInstance(
                        getString(R.string.message_confirm_delete_ascii_section), true
                    )
                    dialog.setListener(object : SimpleDialogFragment.Listener {
                        override fun onPositiveClick() {
                            ruleMap.sections.remove(SKKFlickRule.SECTION_ASCII)
                            isModified = true
                        }

                        override fun onNegativeClick() {
                            binding.checkEnableAscii.isChecked = true
                        }
                    })
                    dialog.show(supportFragmentManager, "dialog")
                }
            } else {
                binding.radioSectionAscii.visibility = View.VISIBLE
            }
        }

        binding.sectionToggle.setOnCheckedChangeListener { _, checkedId ->
            currentSection = when (checkedId) {
                R.id.radioSectionMain -> SKKFlickRule.SECTION_MAIN
                R.id.radioSectionNumber -> SKKFlickRule.SECTION_NUMBER
                R.id.radioSectionVoice -> SKKFlickRule.SECTION_VOICE
                R.id.radioSectionAscii -> SKKFlickRule.SECTION_ASCII
                else -> SKKFlickRule.SECTION_MAIN
            }
            val currentSect = ruleMap.sections[currentSection]
            binding.checkUseGodan.isChecked = (currentSect?.entries?.keys?.maxOrNull() ?: 0) > 19
            updateKeyboardPreview()
        }

        binding.btnShiftToggle.setOnCheckedChangeListener { _, isChecked ->
            service?.mFlickJPPreview?.isShifted = isChecked
            updateKeyboardPreview()
            binding.btnShiftToggle.setBackgroundColor(getColor(if (isChecked) R.color.key_checked_color else R.color.key_background_color))
        }

        binding.textEditor.addTextChangedListener(afterTextChanged = { isModified = true })
    }

    override fun onResume() {
        super.onResume()
        if (service != null) bindPreview()
    }

    private fun bindPreview() = service?.mFlickJPPreview?.let { kv ->
        if (kv.parent != binding.previewContainer) {
            (kv.parent as? android.view.ViewGroup)?.removeView(kv)
            binding.previewContainer.removeAllViews()
            binding.previewContainer.addView(kv)
            kv.onFlickListener = object : FlickJPKeyboardView.OnFlickListener {
                override fun onFlick(keyIndex: Int, flickIndex: Int) {
                    startRuleEditor(keyIndex, flickIndex)
                }
            }
        }
        updateKeyboardPreview()
    }

    private fun updateEditorUI() {
        val currentSect =
            ruleMap.sections.getOrPut(currentSection) { MutableFlickSection(currentSection) }
        // Ensure base keys 0..19
        for (i in 0..19) {
            currentSect.entries.getOrPut(i) { MutableFlickEntry.createEmpty(i) }
        }

        binding.checkUseGodan.isChecked = (currentSect.entries.keys.maxOrNull() ?: 0) > 19
        binding.checkEnableAscii.isChecked =
            ruleMap.sections.containsKey(SKKFlickRule.SECTION_ASCII)
        binding.radioSectionAscii.visibility =
            if (binding.checkEnableAscii.isChecked) View.VISIBLE else View.GONE

        if (binding.modeToggle.checkedRadioButtonId == R.id.radioTextMode) {
            binding.textEditor.setText(SKKFlickRule.serialize(ruleMap.toImmutable()))
        } else {
            updateKeyboardPreview()
        }
    }

    private fun updateKeyboardPreview() {
        val kv = service?.mFlickJPPreview ?: return
        kv.setFlickRules(ruleMap.toImmutable())
        kv.prepareNewKeyboard(this, kv.width, kv.height)

        when (currentSection) {
            SKKFlickRule.SECTION_NUMBER ->
                kv.mNumKeyboard?.let { kv.keyboard = it }.also { kv.isHankaku = false }

            SKKFlickRule.SECTION_VOICE ->
                kv.mVoiceKeyboard?.let { kv.keyboard = it }.also { kv.isHankaku = false }

            else -> kv.mJPKeyboard?.let { kv.keyboard = it }
        }

        kv.updateKeyLabels(
            when (currentSection) { // Mock state for labels
                SKKFlickRule.SECTION_ASCII -> jp.deadend.noname.skk.engine.SKKASCIIState
                else -> jp.deadend.noname.skk.engine.SKKHiraganaState
            }
        )
    }

    private fun startRuleEditor(keyIndex: Int, flick: Int) {
        editingKey = keyIndex

        val sectionData = ruleMap.sections[currentSection]
        val entry = sectionData?.entries?.get(keyIndex)
        val n = entry?.normal ?: MutableFlickKeyConfig.createEmpty()
        val s = entry?.shifted

        val intent = Intent(this, FlickRuleEditorActivity::class.java).apply {
            fun String?.nn(): String = this?.replace("\n", "\\n") ?: ""
            putExtra(FlickRuleEditorActivity.EXTRA_SECTION, currentSection)
            putExtra(FlickRuleEditorActivity.EXTRA_KEY_INDEX, keyIndex)
            putExtra(FlickRuleEditorActivity.EXTRA_FLICK_INDEX, flick)
            putExtra(FlickRuleEditorActivity.EXTRA_NORMAL_KEY_LABEL, n.label.nn())
            putExtra(FlickRuleEditorActivity.EXTRA_SHIFTED_KEY_LABEL, s?.label.nn())
            putExtra(FlickRuleEditorActivity.EXTRA_NORMAL_LABEL, n.labels[flick].nn())
            putExtra(FlickRuleEditorActivity.EXTRA_NORMAL_ACTION, n.actions[flick].nn())
            putExtra(FlickRuleEditorActivity.EXTRA_SHIFTED_LABEL, s?.labels?.get(flick).nn())
            putExtra(FlickRuleEditorActivity.EXTRA_SHIFTED_ACTION, s?.actions?.get(flick).nn())
        }
        editorLauncher.launch(intent)
    }

    private fun updateRule(
        keyIndex: Int, flickIndex: Int,
        nKeyLabel: String, nLabel: String, nAction: String,
        sKeyLabel: String, sLabel: String, sAction: String
    ) {
        val sectionData =
            ruleMap.sections.getOrPut(currentSection) { MutableFlickSection(currentSection) }
        val entry = sectionData.entries.getOrPut(keyIndex) {
            MutableFlickEntry.createEmpty(keyIndex)
        }

        entry.normal.label = nKeyLabel
        entry.normal.labels[flickIndex] = nLabel
        entry.normal.actions[flickIndex] = nAction

        val s = entry.shifted ?: MutableFlickKeyConfig.createEmpty()
        s.label = sKeyLabel
        s.labels[flickIndex] = sLabel
        s.actions[flickIndex] = sAction
        entry.shifted = s
    }

    override fun onPause() {
        if (isModified) {
            val finalMap = if (binding.modeToggle.checkedRadioButtonId == R.id.radioTextMode) {
                SKKFlickRule.parse(binding.textEditor.text.toString())
            } else ruleMap.toImmutable()

            SKKFlickRule.getInternalFile(this@SKKFlickRuleManager)
                .writeText(SKKFlickRule.serialize(finalMap))

            if (SKKService.isRunning()) {
                val intent = Intent(this@SKKFlickRuleManager, SKKService::class.java)
                intent.putExtra(SKKService.KEY_COMMAND, SKKService.COMMAND_READ_PREFS)
                startService(intent)
            }
            isModified = false
        }
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_flick_rule_manager, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()

            R.id.menu_flick_rule_load_godan, R.id.menu_flick_rule_load_godan_simple -> {
                val isSimple = item.itemId == R.id.menu_flick_rule_load_godan_simple
                val dialog = SimpleDialogFragment.newInstance(
                    getString(R.string.message_confirm_load_godan_rule), true
                )
                dialog.setListener(object : SimpleDialogFragment.Listener {
                    override fun onPositiveClick() {
                        SKKFlickRule.loadGodan(this@SKKFlickRuleManager, isSimple)
                        isModified = true
                        ruleMap = SKKFlickRule.load(this@SKKFlickRuleManager)
                        updateEditorUI()
                    }

                })
                dialog.show(supportFragmentManager, "dialog")
            }

            R.id.menu_flick_rule_select -> {
                selectFileLauncher.launch(arrayOf("*/*"))
            }

            R.id.menu_flick_rule_export -> {
                exportFileLauncher.launch(SKKFlickRule.INTERNAL_FILE_NAME)
            }

            R.id.menu_flick_rule_clear -> {
                val dialog = SimpleDialogFragment.newInstance(
                    getString(R.string.message_confirm_clear_flick_rule), true
                )
                dialog.setListener(object : SimpleDialogFragment.Listener {
                    override fun onPositiveClick() {
                        SKKFlickRule.clear(this@SKKFlickRuleManager)
                        isModified = true
                        ruleMap = SKKFlickRule.load(this@SKKFlickRuleManager)
                        updateEditorUI()
                    }

                })
                dialog.show(supportFragmentManager, "dialog")
            }

            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }
}

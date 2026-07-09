package jp.deadend.noname.skk

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.addTextChangedListener
import jp.deadend.noname.skk.databinding.ActivityKanaRuleManagerBinding

class SKKKanaRuleManager : AppCompatActivity() {
    private lateinit var binding: ActivityKanaRuleManagerBinding
    private var isModified = false

    private val selectFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val success = SKKKanaRule.saveFromUri(this, uri)
            if (success) {
                isModified = true
                updateEditorText()
            } else {
                SimpleDialogFragment.newInstance(getString(R.string.error_kana_rule_load))
                    .show(supportFragmentManager, "dialog")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKanaRuleManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout() or
                        WindowInsetsCompat.Type.ime()
            )
            view.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = bars.bottom,
            )
            binding.kanaRuleEditor.let { editor ->
                // 常にカーソルが表示されていることを保証
                editor.bringPointIntoView(editor.selectionEnd)
            }
            WindowInsetsCompat.CONSUMED
        }
        setSupportActionBar(binding.kanaRuleManagerToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        updateEditorText()
        binding.kanaRuleEditor.let { editor ->
            editor.setSelection(0) // カーソルを末尾ではなく先頭に置く
            editor.addTextChangedListener(afterTextChanged = { isModified = true })
        }
    }

    override fun onPause() {
        if (isModified) {
            SKKKanaRule.getInternalFile(this@SKKKanaRuleManager)
                .writeText(binding.kanaRuleEditor.text.toString())
            if (SKKService.isRunning()) {
                val intent = Intent(this@SKKKanaRuleManager, SKKService::class.java)
                intent.putExtra(SKKService.KEY_COMMAND, SKKService.COMMAND_READ_PREFS)
                startService(intent)
            }
            isModified = false
        }
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_kana_rule_manager, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()

            R.id.menu_kana_rule_select -> {
                selectFileLauncher.launch(arrayOf("*/*"))
            }

            R.id.menu_kara_rule_azik -> {
                val dialog = SimpleDialogFragment.newInstance(
                    getString(R.string.message_confirm_load_azik_rule), true
                )
                dialog.setListener(object : SimpleDialogFragment.Listener {
                    override fun onPositiveClick() {
                        SKKKanaRule.loadAzik(this@SKKKanaRuleManager)
                        isModified = true
                        updateEditorText()
                    }

                })
                dialog.show(supportFragmentManager, "dialog")
            }

            R.id.menu_kana_rule_clear -> {
                val dialog = SimpleDialogFragment.newInstance(
                    getString(R.string.message_confirm_clear_kana_rule), true
                )
                dialog.setListener(object : SimpleDialogFragment.Listener {
                    override fun onPositiveClick() {
                        SKKKanaRule.clear(this@SKKKanaRuleManager)
                        isModified = true
                        updateEditorText()
                    }

                })
                dialog.show(supportFragmentManager, "dialog")
            }

            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun updateEditorText() {
        binding.kanaRuleEditor.text.apply {
            clear()
            append(SKKKanaRule.getInternalFile(this@SKKKanaRuleManager).readText())
        }
    }
}

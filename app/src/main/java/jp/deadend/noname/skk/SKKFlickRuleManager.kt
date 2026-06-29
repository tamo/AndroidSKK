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
import jp.deadend.noname.dialog.ConfirmationDialogFragment
import jp.deadend.noname.dialog.SimpleMessageDialogFragment
import jp.deadend.noname.skk.databinding.ActivityFlickRuleManagerBinding

class SKKFlickRuleManager : AppCompatActivity() {
    private lateinit var binding: ActivityFlickRuleManagerBinding
    private var isModified = false

    private val selectFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val success = SKKFlickRule.saveFromUri(this, uri)
            if (success) {
                isModified = true
                updateEditorText()
            } else {
                SimpleMessageDialogFragment.newInstance(getString(R.string.error_kana_rule_load))
                    .show(supportFragmentManager, "dialog")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFlickRuleManagerBinding.inflate(layoutInflater)
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
            binding.flickRuleEditor.let { editor ->
                editor.bringPointIntoView(editor.selectionEnd)
            }
            WindowInsetsCompat.CONSUMED
        }
        setSupportActionBar(binding.flickRuleManagerToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        updateEditorText()
        binding.flickRuleEditor.let { editor ->
            editor.setSelection(0)
            editor.addTextChangedListener(afterTextChanged = { isModified = true })
        }
    }

    override fun onPause() {
        if (isModified) {
            SKKFlickRule.getInternalFile(this@SKKFlickRuleManager)
                .writeText(binding.flickRuleEditor.text.toString())
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
                val dialog = ConfirmationDialogFragment.newInstance(
                    getString(R.string.message_confirm_load_godan_rule)
                )
                dialog.setListener(object : ConfirmationDialogFragment.Listener {
                    override fun onPositiveClick() {
                        SKKFlickRule.loadGodan(this@SKKFlickRuleManager, isSimple)
                        isModified = true
                        updateEditorText()
                    }

                    override fun onNegativeClick() {}
                })
                dialog.show(supportFragmentManager, "dialog")
            }

            R.id.menu_flick_rule_select -> {
                selectFileLauncher.launch(arrayOf("*/*"))
            }

            R.id.menu_flick_rule_clear -> {
                val dialog = ConfirmationDialogFragment.newInstance(
                    getString(R.string.message_confirm_clear_flick_rule)
                )
                dialog.setListener(object : ConfirmationDialogFragment.Listener {
                    override fun onPositiveClick() {
                        SKKFlickRule.clear(this@SKKFlickRuleManager)
                        isModified = true
                        updateEditorText()
                    }

                    override fun onNegativeClick() {}
                })
                dialog.show(supportFragmentManager, "dialog")
            }

            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun updateEditorText() {
        binding.flickRuleEditor.text.apply {
            clear()
            append(SKKFlickRule.getInternalFile(this@SKKFlickRuleManager).readText())
        }
    }
}

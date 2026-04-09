package jp.deadend.noname.skk

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import jp.deadend.noname.dialog.ConfirmationDialogFragment
import jp.deadend.noname.dialog.SimpleMessageDialogFragment
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
                updateStatusView()
            } else {
                SimpleMessageDialogFragment.newInstance(getString(R.string.error_kana_rule_load))
                    .show(supportFragmentManager, "dialog")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKanaRuleManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        updateStatusView()
    }

    override fun onPause() {
        if (isModified) {
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
            R.id.menu_kana_rule_clear -> {
                val dialog = ConfirmationDialogFragment.newInstance(
                    getString(R.string.message_confirm_clear_kana_rule)
                )
                dialog.setListener(object : ConfirmationDialogFragment.Listener {
                    override fun onPositiveClick() {
                        SKKKanaRule.clear(this@SKKKanaRuleManager)
                        isModified = true
                        updateStatusView()
                    }
                    override fun onNegativeClick() {}
                })
                dialog.show(supportFragmentManager, "dialog")
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun updateStatusView() {
        binding.kanaRuleStatus.text = if (SKKKanaRule.exists(this)) {
            getString(R.string.label_kana_rule_current, SKKKanaRule.INTERNAL_FILE_NAME)
        } else {
            getString(R.string.label_kana_rule_not_set)
        }
    }
}

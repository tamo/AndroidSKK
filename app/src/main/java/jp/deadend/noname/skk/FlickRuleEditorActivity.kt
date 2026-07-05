package jp.deadend.noname.skk

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import jp.deadend.noname.skk.databinding.ActivityFlickRuleEditorBinding

class FlickRuleEditorActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFlickRuleEditorBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val section = intent.getStringExtra(EXTRA_SECTION)
        val keyIndex = intent.getIntExtra(EXTRA_KEY_INDEX, -1)
        val flickIndex = intent.getIntExtra(EXTRA_FLICK_INDEX, -1)
        if (section == null || keyIndex == -1 || flickIndex == -1) finish()

        binding = ActivityFlickRuleEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.title = getString(R.string.title_flick_rule_editor)
        binding.editorHeader.text = getString(
            R.string.label_flick_rule_editor, section, keyIndex, getFlickDirectionName(flickIndex)
        )

        binding.normalKeyLabel.setText(intent.getStringExtra(EXTRA_NORMAL_KEY_LABEL))
        binding.shiftedKeyLabel.setText(intent.getStringExtra(EXTRA_SHIFTED_KEY_LABEL))
        binding.normalLabel.setText(intent.getStringExtra(EXTRA_NORMAL_LABEL))
        binding.normalAction.setText(intent.getStringExtra(EXTRA_NORMAL_ACTION))
        binding.shiftedLabel.setText(intent.getStringExtra(EXTRA_SHIFTED_LABEL))
        binding.shiftedAction.setText(intent.getStringExtra(EXTRA_SHIFTED_ACTION))

        val adapter = ArrayAdapter(
            this, android.R.layout.simple_dropdown_item_1line, SKKFlickRule.ACTIONS
        ) // 冒頭に '(' を入力すると補完される
        binding.normalAction.setAdapter(adapter)
        binding.shiftedAction.setAdapter(adapter)

        binding.btnSave.setOnClickListener {
            val resultIntent = Intent().apply {
                //putExtra(EXTRA_SECTION, section)
                //putExtra(EXTRA_KEY_INDEX, keyIndex)
                putExtra(EXTRA_FLICK_INDEX, flickIndex)
                putExtra(EXTRA_NORMAL_KEY_LABEL, binding.normalKeyLabel.text.toString())
                putExtra(EXTRA_SHIFTED_KEY_LABEL, binding.shiftedKeyLabel.text.toString())
                putExtra(EXTRA_NORMAL_LABEL, binding.normalLabel.text.toString())
                putExtra(EXTRA_NORMAL_ACTION, binding.normalAction.text.toString())
                putExtra(EXTRA_SHIFTED_LABEL, binding.shiftedLabel.text.toString())
                putExtra(EXTRA_SHIFTED_ACTION, binding.shiftedAction.text.toString())
            }
            setResult(RESULT_OK, resultIntent)
            finish()
        }

        binding.btnCancel.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    private fun getFlickDirectionName(index: Int): String = when (index) {
        0 -> "フリックなし"
        1 -> "フリック ←"
        2 -> "フリック ↑"
        3 -> "フリック →"
        4 -> "フリック ↓"
        5 -> "フリックなし カーブ ↖"
        6 -> "フリックなし カーブ ↗"
        7 -> "フリック ← カーブ ↓"
        8 -> "フリック ← カーブ ↑"
        9 -> "フリック ↑ カーブ ←"
        10 -> "フリック ↑ カーブ →"
        11 -> "フリック → カーブ ↑"
        12 -> "フリック → カーブ ↓"
        13 -> "フリック ↓ カーブ →"
        14 -> "フリック ↓ カーブ ←"
        else -> index.toString()
    }

    companion object {
        const val EXTRA_SECTION = "section"
        const val EXTRA_KEY_INDEX = "key_index"
        const val EXTRA_FLICK_INDEX = "flick_index"
        const val EXTRA_NORMAL_KEY_LABEL = "normal_key_label"
        const val EXTRA_NORMAL_LABEL = "normal_label"
        const val EXTRA_NORMAL_ACTION = "normal_action"
        const val EXTRA_SHIFTED_KEY_LABEL = "shifted_key_label"
        const val EXTRA_SHIFTED_LABEL = "shifted_label"
        const val EXTRA_SHIFTED_ACTION = "shifted_action"
    }
}

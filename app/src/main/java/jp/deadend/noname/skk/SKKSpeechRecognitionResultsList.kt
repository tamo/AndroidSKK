package jp.deadend.noname.skk

import android.content.Intent
import android.os.Bundle
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import jp.deadend.noname.skk.databinding.ActivityListBinding

class SKKSpeechRecognitionResultsList : AppCompatActivity() {
    private lateinit var binding: ActivityListBinding
    private lateinit var mResults: List<String>

    override fun onCreate(icicle: Bundle?) {
        super.onCreate(icicle)
        binding = ActivityListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val extras = intent.extras
        mResults = if (extras == null) listOf() else { extras.getStringArrayList(RESULTS_KEY) ?: listOf() }
        binding.listView.emptyView = binding.emptyView
        binding.listView.adapter = ArrayAdapter(this, R.layout.listitem_text_row, mResults)
        binding.listView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val retIntent = Intent(SKKService.ACTION_COMMAND)
            retIntent.putExtra(SKKService.KEY_COMMAND, SKKService.COMMAND_SPEECH_RECOGNITION)
            retIntent.putExtra(RESULTS_KEY, mResults[position])
            sendBroadcast(retIntent)
            finish()
        }
    }

    companion object {
        const val RESULTS_KEY = "speech_recognition_results_key"
    }
}
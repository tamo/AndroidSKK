package jp.deadend.noname.skk

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import jp.deadend.noname.skk.databinding.ActivityListBinding

class SKKMushroom : AppCompatActivity() {
    private lateinit var binding: ActivityListBinding
    private lateinit var mStr: String

    private class AppInfo(
        val icon: Drawable,
        val appName: String,
        val packageName: String,
        val className: String
    )

    private val callMushroom =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result : ActivityResult ->
            if (result.resultCode == Activity.RESULT_OK) {
                val extras = result.data?.extras
                val s = if (extras == null) "" else extras.getString(REPLACE_KEY)

                val retIntent = Intent(ACTION_BROADCAST)
                retIntent.setPackage(packageName)
                retIntent.addCategory(CATEGORY_BROADCAST)
                retIntent.putExtra(REPLACE_KEY, s)
                sendBroadcast(retIntent)
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val extras = intent.extras
        mStr = if (extras == null) "" else { extras.getString(REPLACE_KEY) ?: "" }
        binding.listView.emptyView = binding.emptyView
        binding.listView.adapter = AppListAdapter(this, loadMushroomAppList())
        binding.listView.onItemClickListener = AdapterView.OnItemClickListener { l, _, position, _ ->
            val info = l.getItemAtPosition(position) as AppInfo

            val intent = Intent(ACTION_SIMEJI_MUSHROOM)
            intent.addCategory(CATEGORY_SIMEJI_MUSHROOM)
            intent.setClassName(info.packageName, info.className)
            intent.putExtra(REPLACE_KEY, mStr)
            // intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            callMushroom.launch(intent)
        }
        binding.emptyView.text = getString(R.string.error_no_mushroom)
    }

    private fun loadMushroomAppList(): List<AppInfo> {
        val pm = packageManager
        val intent = Intent(ACTION_SIMEJI_MUSHROOM)
        intent.addCategory(CATEGORY_SIMEJI_MUSHROOM)

        val result = pm.queryIntentActivities(intent, 0).map {
            val ai = it.activityInfo
            val icon = it.loadIcon(pm)
            icon.setBounds(0, 0, 48, 48)
            AppInfo(icon, it.loadLabel(pm).toString(), ai.packageName, ai.name)
        }

        return result.sortedBy { it.appName }
    }

    private inner class AppListAdapter(
            context: Context,
            items: List<AppInfo>
    ) : ArrayAdapter<AppInfo>(context, 0, items) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val tv = convertView ?: TextView(this@SKKMushroom).apply {
                textSize = 20f
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(4, 4, 4, 4)
                compoundDrawablePadding = 8
            }

            val item = getItem(position)
            if (item != null) {
                (tv as TextView).apply {
                    setCompoundDrawables(item.icon, null, null, null)
                    text = item.appName
                }
            }

            return tv
        }
    }

    companion object {
        const val ACTION_SIMEJI_MUSHROOM = "com.adamrocker.android.simeji.ACTION_INTERCEPT"
        const val CATEGORY_SIMEJI_MUSHROOM = "com.adamrocker.android.simeji.REPLACE"

        const val ACTION_BROADCAST = "jp.deadend.noname.skk.MUSHROOM_RESULT"
        const val CATEGORY_BROADCAST = "jp.deadend.noname.skk.MUSHROOM_VALUE"

        const val REPLACE_KEY = "replace_key"
    }
}

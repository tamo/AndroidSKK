package jp.deadend.noname.skk

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.preference.PreferenceManager
import androidx.appcompat.app.AppCompatActivity
import android.util.Log
import android.view.*
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import java.io.IOException
import java.nio.charset.CharacterCodingException
import jdbm.RecordManager
import jdbm.RecordManagerFactory
import jdbm.btree.BTree
import jdbm.helper.StringComparator
import jdbm.helper.Tuple
import jp.deadend.noname.dialog.ConfirmationDialogFragment
import jp.deadend.noname.dialog.SimpleMessageDialogFragment
import jp.deadend.noname.dialog.TextInputDialogFragment
import jp.deadend.noname.skk.databinding.ActivityDicManagerBinding

class SKKDicManager : AppCompatActivity() {
    private lateinit var binding: ActivityDicManagerBinding
    private val mDics = mutableListOf<Tuple>()
    private var isModified = false

    private val addDicFileLauncher = registerForActivityResult(
                                        ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { addDic(uri) }
    }


    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDicManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        mDics.add(Tuple(getString(R.string.label_dicmanager_ldic), ""))
        val optDics = PreferenceManager.getDefaultSharedPreferences(this)
                .getString(getString(R.string.prefkey_optional_dics), "") ?: ""
        if (optDics.isNotEmpty()) {
            optDics.split("/").dropLastWhile { it.isEmpty() }.chunked(2).forEach {
                mDics.add(Tuple(it[0], it[1]))
            }
        }

        binding.dicManagerList.adapter = TupleAdapter(this, mDics)
        binding.dicManagerList.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            if (position == 0) {
                SimpleMessageDialogFragment.newInstance(getString(R.string.message_main_dic))
                    .show(supportFragmentManager, "dialog")
            } else {
                val dialog =
                    ConfirmationDialogFragment.newInstance(getString(R.string.message_confirm_remove_dic))
                dialog.setListener(
                    object : ConfirmationDialogFragment.Listener {
                        override fun onPositiveClick() {
                            val dicName = mDics[position].value as String
                            deleteFile("$dicName.db")
                            deleteFile("$dicName.lg")
                            mDics.removeAt(position)
                            (binding.dicManagerList.adapter as TupleAdapter).notifyDataSetChanged()
                            isModified = true
                        }

                        override fun onNegativeClick() {}
                    })
                dialog.show(supportFragmentManager, "dialog")
            }
        }
    }

    override fun onPause() {
        if (isModified) {
            val dics = StringBuilder()
            for (i in 1 until mDics.size) {
                dics.append(mDics[i].key, "/", mDics[i].value, "/")
            }

            PreferenceManager.getDefaultSharedPreferences(this)
                    .edit()
                    .putString(getString(R.string.prefkey_optional_dics), dics.toString())
                    .apply()


            val intent = Intent(SKKService.ACTION_COMMAND)
            intent.putExtra(SKKService.KEY_COMMAND, SKKService.COMMAND_RELOAD_DICS)
            sendBroadcast(intent)
        }

        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_dic_manager, menu)

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            R.id.menu_dic_manager_add -> {
                addDicFileLauncher.launch(arrayOf("*/*"))
            }
            R.id.menu_dic_manager_reset -> {
                val dialog = ConfirmationDialogFragment.newInstance(
                    getString(R.string.message_confirm_clear_dics)
                )
                dialog.setListener(
                    object : ConfirmationDialogFragment.Listener {
                        override fun onPositiveClick() {
                            fileList().forEach { file ->
                                if (!file.startsWith("skk_userdict")) { deleteFile(file) }
                            }
                            try {
                                unzipFile(resources.assets.open(SKKService.DICT_ZIP_FILE), filesDir)
                            } catch (e: IOException) {
                                SimpleMessageDialogFragment.newInstance(
                                    getString(R.string.error_extracting_dic_failed)
                                ).show(supportFragmentManager, "dialog")
                            }
                            mDics.clear()
                            mDics.add(Tuple(getString(R.string.label_dicmanager_ldic), ""))
                            (binding.dicManagerList.adapter as TupleAdapter).notifyDataSetChanged()
                            isModified = true
                        }
                        override fun onNegativeClick() {}
                    })
                dialog.show(supportFragmentManager, "dialog")
            }
            else -> return super.onOptionsItemSelected(item)
        }

        return true
    }

    private fun addDic(uri: Uri) {
        val dicFileBaseName = loadDic(uri)
        if (dicFileBaseName != null) {
            val dialog = TextInputDialogFragment.newInstance(getString(R.string.label_dicmanager_input_name))
            dialog.setSingleLine(true)
            dialog.isCancelable = false
            dialog.setListener(
                    object : TextInputDialogFragment.Listener {
                        override fun onPositiveClick(result: String) {
                            val dicName = if (result.isEmpty()) {
                                getString(R.string.label_dicmanager_optionaldic)
                            } else {
                                result.replace("/", "")
                            }
                            var name = dicName
                            var suffix = 1
                            while (containsName(name)) {
                                suffix++
                                name = "$dicName($suffix)"
                            }
                            mDics.add(Tuple(name, dicFileBaseName))
                            (binding.dicManagerList.adapter as TupleAdapter).notifyDataSetChanged()
                            isModified = true
                        }

                        override fun onNegativeClick() {
                            deleteFile("$dicFileBaseName.db")
                            deleteFile("$dicFileBaseName.lg")
                        }
                    })
            dialog.show(supportFragmentManager, "dialog")
        }
    }

    private fun loadDic(uri: Uri): String? {
        val name = getFileNameFromUri(this, uri)
        if (name == null) {
            SimpleMessageDialogFragment.newInstance(
                getString(R.string.error_open_dicfile)
            ).show(supportFragmentManager, "dialog")
            return null
        }

        val dicFileBaseName = if (name.startsWith("SKK-JISYO.")) {
            "skk_dict_" + name.substring(10)
        } else {
            "skk_dict_" + name.replace(".", "_")
        }

        val filesDir = filesDir
        val filesList = filesDir?.listFiles()
        if (filesDir == null || filesList == null) {
            SimpleMessageDialogFragment.newInstance(
                getString(R.string.error_access_failed, filesDir)
            ).show(supportFragmentManager, "dialog")
            return null
        }
        if (filesList.any { it.name == "$dicFileBaseName.db" }) {
            SimpleMessageDialogFragment.newInstance(
                getString(R.string.error_dic_exists, dicFileBaseName)
            ).show(supportFragmentManager, "dialog")
            return null
        }

        var recMan: RecordManager? = null
        try {
            recMan = RecordManagerFactory.createRecordManager(
                    filesDir.absolutePath + "/" + dicFileBaseName
            )
            val btree = BTree.createInstance(recMan, StringComparator())
            recMan.setNamedObject(getString(R.string.btree_name), btree.recid)
            recMan.commit()

            contentResolver.openInputStream(uri)?.use { inputStream ->
                loadFromTextDic(inputStream, recMan, btree, false)
            }
        } catch (e: IOException) {
            if (e is CharacterCodingException) {
                SimpleMessageDialogFragment.newInstance(
                    getString(R.string.error_text_dic_coding)
                ).show(supportFragmentManager, "dialog")
            } else {
                SimpleMessageDialogFragment.newInstance(
                    getString(R.string.error_file_load, name)
                ).show(supportFragmentManager, "dialog")
            }
            Log.e("SKK", "SKKDicManager#loadDic() Error: $e")
            if (recMan != null) {
                try {
                    recMan.close()
                } catch (ee: IOException) {
                    Log.e("SKK", "SKKDicManager#loadDic() can't close(): $ee")
                }

            }
            deleteFile("$dicFileBaseName.db")
            deleteFile("$dicFileBaseName.lg")
            return null
        }

        try {
            recMan.close()
        } catch (ee: IOException) {
            Log.e("SKK", "SKKDicManager#loadDic() can't close(): $ee")
            return null
        }

        return dicFileBaseName
    }

    private fun containsName(s: String) = mDics.any { s == it.key }

    private class TupleAdapter(
            context: Context,
            items: List<Tuple>
    ) : ArrayAdapter<Tuple>(context, 0, items) {
        private val mLayoutInflater = LayoutInflater.from(context)

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                    ?: mLayoutInflater.inflate(android.R.layout.simple_list_item_1, parent, false)
            getItem(position)?.let {
                (view as TextView).text = it.key as String
            }

            return view
        }
    }
}

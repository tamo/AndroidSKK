package jp.deadend.noname.skk

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckedTextView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import jdbm.RecordManager
import jdbm.RecordManagerFactory
import jdbm.btree.BTree
import jdbm.helper.StringComparator
import jdbm.helper.Tuple
import jp.deadend.noname.dialog.ConfirmationDialogFragment
import jp.deadend.noname.dialog.SimpleMessageDialogFragment
import jp.deadend.noname.dialog.TextInputDialogFragment
import jp.deadend.noname.skk.databinding.ActivityDicManagerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.util.zip.GZIPInputStream

class SKKDicManager : AppCompatActivity() {
    private lateinit var binding: ActivityDicManagerBinding
    private val mPrefOptDics: String by lazy {
        PreferenceManager.getDefaultSharedPreferences(this)
            .getString(getString(R.string.prefkey_optional_dics), "") ?: ""
    }
    private val mDics = mutableListOf<Tuple<String, String>>()
    private var isModified = false

    private val addDicFileLauncher = registerForActivityResult(
                                        ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { loadDic(uri) }
    }

    private val commonDics = listOf(
        "S", "M", "ML", "L", "L.unannotated", "jinmei", "geo", "station", "propernoun"
    ).map { type -> Tuple("SKK $type 辞書", "/skk_dict_${type}") }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDicManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // 一般的な辞書リストをまず列挙
        commonDics.forEach { mDics.add(it) }
        // インストール済みかどうかチェック
        fileList()
            .filter { it.startsWith("skk_dict_") && it.endsWith(".db") }
            .forEach {
                val entry = it.dropLast(".db".length)
                val type = entry.drop("skk_dict_".length)
                val dupIndex = mDics.indexOfFirst { dict ->
                    dict.value == "/${entry}"
                }
                if (dupIndex == -1) {
                    mDics.add(Tuple("SKK $type 辞書", entry))
                } else {
                    mDics[dupIndex].value = entry
                }
            }
        // 設定済みの辞書
        if (mPrefOptDics.isNotEmpty()) {
            mPrefOptDics.split("/").dropLastWhile { it.isEmpty() }.chunked(2).forEach {
                mDics.find { dict -> dict.value == it[1] }
                    ?.let { dup -> mDics.remove(dup) } // 一般的な辞書の名前を prefs の名前に更新
                mDics.add(Tuple(it[0], it[1]))
            }
        }
        mDics.sortBy { it.key }

        binding.dicManagerList.adapter = TupleAdapter(this, mDics)
        binding.dicManagerList.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            if (mDics[position].value.startsWith('/')) {
                downloadDic(mDics[position].value.drop("/skk_dict_".length))
            } else {
                val dialog =
                    ConfirmationDialogFragment.newInstance(getString(R.string.message_confirm_remove_dic))
                dialog.setListener(
                    object : ConfirmationDialogFragment.Listener {
                        override fun onPositiveClick() {
                            val dicName = mDics[position].value
                            deleteFile("$dicName.db")
                            deleteFile("$dicName.lg")
                            if (dicName.startsWith("skk_dict_")) {
                                mDics[position].value = "/${dicName}"
                            } else {
                                mDics.removeAt(position)
                            }
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
        val dics = StringBuilder()
        mDics
            .filter { !it.value.startsWith('/') }
            .forEach { dics.append(it.key, "/", it.value, "/") }

        if (isModified && (mPrefOptDics != dics.toString())) {
            PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .putString(getString(R.string.prefkey_optional_dics), dics.toString())
                .apply()

            if (SKKService.isRunning()) { // まだ起動していないなら不要
                val intent = Intent(this@SKKDicManager, SKKService::class.java)
                    .putExtra(SKKService.KEY_COMMAND, SKKService.COMMAND_RELOAD_DICS)
                startService(intent)
            }
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
                                if (!file.startsWith(getString(R.string.dic_name_user)) &&
                                    !file.startsWith(getString(R.string.dic_name_ascii))
                                ) { deleteFile(file) }
                            }
                            try {
                                unzipFile(resources.assets.open(SKKService.DICT_ASCII_ZIP_FILE), filesDir)
                            } catch (e: IOException) {
                                SimpleMessageDialogFragment.newInstance(
                                    getString(R.string.error_extracting_dic_failed)
                                ).show(supportFragmentManager, "dialog")
                            }
                            mDics.clear()
                            commonDics.forEach { mDics.add(it) }
                            mDics.sortBy { it.key }
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

    private fun downloadDic(type: String) {
        val dialog =
            ConfirmationDialogFragment.newInstance(getString(R.string.message_confirm_download_dic, type))
        dialog.setListener(
            object : ConfirmationDialogFragment.Listener {
                val path = File("${filesDir.absolutePath}/SKK-JISYO.${type}.gz")

                override fun onPositiveClick() {
                    MainScope().launch(Dispatchers.IO) {
                        if (path.exists()) {
                            deleteFile(path.name)
                        }
                        URL("https://skk-dev.github.io/dict/SKK-JISYO.${type}.gz")
                            .openStream()
                            .copyTo(FileOutputStream(path))
                        withContext(Dispatchers.Main) {
                            loadDic(path)
                        }
                    }.start()
                }

                override fun onNegativeClick() {
                    if (path.exists()) {
                        loadDic(path)
                    }
                }
            }
        )
        dialog.show(supportFragmentManager, "dialog")
    }

    private fun addDic(dicFileBaseName: String?, fixed: Boolean = false) {
        if (dicFileBaseName == null) return
        val dictPrefix = "skk_dict_"
        if (fixed && dicFileBaseName.startsWith(dictPrefix)) {
            val dupIndex = mDics.indexOfFirst { it.value == "/${dicFileBaseName}" }
            if (dupIndex == -1) {
                val dictName = "SKK ${dicFileBaseName.drop(dictPrefix.length)} 辞書"
                mDics.add(Tuple(dictName, dicFileBaseName))
            } else {
                mDics[dupIndex].value = dicFileBaseName
            }
            (binding.dicManagerList.adapter as TupleAdapter).notifyDataSetChanged()
            isModified = true
            return
        }

        val dialog =
            TextInputDialogFragment.newInstance(getString(R.string.label_dicmanager_input_name))
        dialog.setSingleLine(true)
        dialog.setPlaceHolder(dicFileBaseName)
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

    private fun loadDic(file: File) {
        return loadDic(file.toUri(), fixed = true)
    }

    private fun loadDic(uri: Uri, fixed: Boolean = false) {
        val name = getFileNameFromUri(this, uri)
        if (name == null) {
            SimpleMessageDialogFragment.newInstance(
                getString(R.string.error_open_dicfile)
            ).show(supportFragmentManager, "dialog")
            return
        }

        val isGzip = name.endsWith(".gz")
        val nameWithoutGZ = if (isGzip) name.substring(0, name.length - 3) else name

        val dicFileBaseName = if (name.startsWith("SKK-JISYO.")) {
            "skk_dict_" + nameWithoutGZ.substring("SKK-JISYO.".length)
        } else {
            "dict_" + nameWithoutGZ.replace(".", "_")
        }

        val filesDir = filesDir
        val filesList = filesDir?.listFiles()
        if (filesDir == null || filesList == null) {
            SimpleMessageDialogFragment.newInstance(
                getString(R.string.error_access_failed, filesDir)
            ).show(supportFragmentManager, "dialog")
            return
        }
        if (filesList.any { it.name == "$dicFileBaseName.db" }) {
            SimpleMessageDialogFragment.newInstance(
                getString(R.string.error_dic_exists, dicFileBaseName)
            ).show(supportFragmentManager, "dialog")
            return
        }

        MainScope().launch(Dispatchers.IO) {
            var recMan: RecordManager? = null
            try {
                recMan = RecordManagerFactory.createRecordManager(
                    filesDir.absolutePath + "/" + dicFileBaseName
                )
                val btree = BTree<String, String>(recMan, StringComparator())
                recMan.setNamedObject(getString(R.string.btree_name), btree.recordId)
                recMan.commit()

                val charset = if (contentResolver.openInputStream(uri)!!.use { inputStream ->
                        val processedInputStream =
                            if (isGzip) GZIPInputStream(inputStream) else inputStream
                        isTextDicInEucJp(processedInputStream)
                    }) "EUC-JP" else "UTF-8"
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val processedInputStream =
                        if (isGzip) GZIPInputStream(inputStream) else inputStream
                    loadFromTextDic(processedInputStream, charset, recMan, btree, false)
                }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    if (e is CharacterCodingException) {
                        SimpleMessageDialogFragment.newInstance(
                            getString(R.string.error_text_dic_coding)
                        ).show(supportFragmentManager, "dialog")
                    } else {
                        SimpleMessageDialogFragment.newInstance(
                            getString(R.string.error_file_load, name)
                        ).show(supportFragmentManager, "dialog")
                    }
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
                return@launch
            }

            try {
                recMan.close()
            } catch (ee: IOException) {
                Log.e("SKK", "SKKDicManager#loadDic() can't close(): $ee")
                return@launch
            }

            withContext(Dispatchers.Main) {
                addDic(dicFileBaseName, fixed)
            }
        }.start()
    }

    private fun containsName(s: String) = mDics.any { s == it.key }

    private class TupleAdapter(
            context: Context,
            items: List<Tuple<String, String>>
    ) : ArrayAdapter<Tuple<String, String>>(context, 0, items) {
        private val mLayoutInflater = LayoutInflater.from(context)

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                    ?: mLayoutInflater.inflate(android.R.layout.simple_list_item_checked, parent, false)
            getItem(position)?.let {
                (view as TextView).text = it.key
                (view as CheckedTextView).isChecked = !it.value.startsWith('/')
            }

            return view
        }
    }
}

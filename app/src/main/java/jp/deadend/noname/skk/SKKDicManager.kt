package jp.deadend.noname.skk

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter.formatShortFileSize
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckedTextView
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlin.math.floor
import kotlin.math.sqrt

class SKKDicManager : AppCompatActivity() {
    private lateinit var binding: ActivityDicManagerBinding
    private val mAdapter: TupleAdapter
        get() = binding.dicManagerList.adapter as TupleAdapter
    private val mPrefOptDics: String by lazy {
        PreferenceManager.getDefaultSharedPreferences(this)
            .getString(getString(R.string.prefkey_optional_dics), "") ?: ""
    }
    private val mDics = mutableListOf<Tuple<String, String>>()
    private var isModified = false

    private val addDicFileLauncher = registerForActivityResult(
                                        ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { loadDic(uri, -1, false) }
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
        mDics.addAll(commonDics)
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
                downloadDic(mDics[position].value.drop("/skk_dict_".length), position)
            } else {
                val dialog =
                    ConfirmationDialogFragment.newInstance(getString(R.string.message_confirm_remove_dic))
                dialog.setListener(
                    object : ConfirmationDialogFragment.Listener {
                        override fun onPositiveClick() {
                            val item = mAdapter.getItem(position)!!
                            val dicName = item.key
                            val dicPath = item.value
                            deleteFile("$dicPath.db")
                            deleteFile("$dicPath.lg")
                            mAdapter.remove(item)
                            if (dicPath.startsWith("skk_dict_")) {
                                mAdapter.insert(Tuple(dicName, "/$dicPath"), position)
                            }
                            mAdapter.notifyDataSetChanged()
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
                            mAdapter.apply {
                                clear()
                                addAll(commonDics)
                                val comparator = StringComparator()
                                sort { a, b -> comparator.compare(a.key, b.key) }
                                notifyDataSetChanged()
                            }
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

    private fun downloadDic(type: String, position: Int) {
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
                        var isDownloading = true
                        if (position != -1) launch { // 先に進捗表示を設置しておく
                            val item = mAdapter.getItem(position)!!
                            val itemName = item.key
                            while (true) {
                                delay(300)
                                if (isDownloading) {
                                    val size = formatShortFileSize(applicationContext, path.length())
                                    withContext(Dispatchers.Main) {
                                        item.key = "$itemName ($size)"
                                        mAdapter.notifyDataSetChanged()
                                    }
                                } else { // 終了
                                    withContext(Dispatchers.Main) {
                                        item.key = itemName
                                        mAdapter.notifyDataSetChanged()
                                    }
                                    break
                                }
                            }
                        }
                        URL("https://skk-dev.github.io/dict/SKK-JISYO.${type}.gz")
                            .openStream()
                            .copyTo(FileOutputStream(path))
                        isDownloading = false
                        withContext(Dispatchers.Main) {
                            loadCommonDic(path, position)
                        }
                    }.start()
                }

                override fun onNegativeClick() {
                    if (path.exists()) {
                        loadCommonDic(path, position)
                    }
                }
            }
        )
        dialog.show(supportFragmentManager, "dialog")
    }

    private fun addDic(dicFileBaseName: String?, position: Int, loader: (Int) -> Unit = {}) {
        if (dicFileBaseName == null) return

        if (position != -1) { // 既存のものを loadDic した後の処理
            mAdapter.getItem(position)!!.value = dicFileBaseName
            mAdapter.notifyDataSetChanged()
            isModified = true
            return
        }

        // 新規 item を作ってから loader で loadDic する
        val dialog =
            TextInputDialogFragment.newInstance(getString(R.string.label_dicmanager_input_name))
        dialog.setSingleLine(true)
        dialog.setPlaceHolder(dicFileBaseName.removePrefix("/").removePrefix("dict_"))
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
                    val item = Tuple(name, "/$dicFileBaseName")
                    mAdapter.add(item)
                    isModified = true

                    loader(mAdapter.getPosition(item))
                }

                override fun onNegativeClick() {
                    deleteFile("$dicFileBaseName.db")
                    deleteFile("$dicFileBaseName.lg")
                }
            })
        dialog.show(supportFragmentManager, "dialog")
    }

    private fun loadCommonDic(file: File, position: Int) {
        return loadDic(file.toUri(), position, true)
    }

    private fun loadDic(uri: Uri, position: Int, common: Boolean) {
        val name = getFileNameFromUri(this, uri)
        if (name == null) {
            SimpleMessageDialogFragment.newInstance(
                getString(R.string.error_open_dicfile)
            ).show(supportFragmentManager, "dialog")
            return
        }

        val isGzip = name.endsWith(".gz")
        val nameWithoutGZ = if (isGzip) name.substring(0, name.length - 3) else name

        val dicFileBaseName = if (common && name.startsWith("SKK-JISYO.")) {
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
            val dialog = ConfirmationDialogFragment.newInstance(
                getString(R.string.error_dic_exists, dicFileBaseName)
            )
            dialog.setListener(
                object : ConfirmationDialogFragment.Listener {
                    override fun onPositiveClick() {
                        deleteFile("$dicFileBaseName.db")
                        deleteFile("$dicFileBaseName.lg")
                        loadDic(uri, position, common)
                    }
                    override fun onNegativeClick() {}
                }
            )
            dialog.show(supportFragmentManager, "dialog")
            return
        }

        if (position == -1) { // view がないと進捗が分からないので addDic してからやり直し
            addDic("/$dicFileBaseName", -1) {
                loadDic(uri, it, false)
            }
            return
        }

        MainScope().launch(Dispatchers.IO) {
            val item = mAdapter.getItem(position)
            val itemName = item?.key
            var recMan: RecordManager? = null
            try {
                recMan = RecordManagerFactory.createRecordManager(
                    filesDir.absolutePath + "/" + dicFileBaseName
                )
                val btree = BTree<String, String>(recMan, StringComparator())
                recMan.setNamedObject(getString(R.string.btree_name), btree.recordId)
                recMan.commit()

                if (item != null) withContext(Dispatchers.Main) {
                    item.key = "$itemName (識別中)"
                    mAdapter.notifyDataSetChanged()
                }
                val charset = if (contentResolver.openInputStream(uri)!!.use { inputStream ->
                        val processedInputStream =
                            if (isGzip) GZIPInputStream(inputStream) else inputStream
                        isTextDicInEucJp(processedInputStream)
                    }) "EUC-JP" else "UTF-8"
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val processedInputStream =
                        if (isGzip) GZIPInputStream(inputStream) else inputStream
                    loadFromTextDic(processedInputStream, charset, recMan, btree, false) {
                        if (floor(sqrt(it.toFloat())) % 70 == 0f) {
                            if (item != null) MainScope().launch {
                                item.key = "$itemName ($it 行目)"
                                mAdapter.notifyDataSetChanged()
                            }
                        }
                    }
                }
                if (item != null) withContext(Dispatchers.Main) {
                    item.key = itemName
                    mAdapter.notifyDataSetChanged()
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
                    if (item != null) {
                        mAdapter.remove(item)
                        mAdapter.notifyDataSetChanged()
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
                addDic(dicFileBaseName.removePrefix("/"), position)
            }
        }.start()
    }

    private fun containsName(s: String) = mDics.any { s == it.key }

    private class TupleAdapter(
            context: Context,
            items: List<Tuple<String, String>>
    ) : ArrayAdapter<Tuple<String, String>>(context, 0, items) {
        private val mLayoutInflater = LayoutInflater.from(context)

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): CheckedTextView {
            val view = (convertView
                ?: mLayoutInflater.inflate(android.R.layout.simple_list_item_checked, parent, false))
                    as CheckedTextView
            getItem(position)?.let {
                view.text = it.key
                view.isChecked = !it.value.startsWith('/')
            }

            return view
        }
    }
}

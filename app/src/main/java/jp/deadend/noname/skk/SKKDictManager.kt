package jp.deadend.noname.skk

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter.formatShortFileSize
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import jdbm.RecordManager
import jdbm.RecordManagerFactory
import jdbm.btree.BTree
import jdbm.helper.StringComparator
import jdbm.helper.Tuple
import jp.deadend.noname.dialog.ConfirmationDialogFragment
import jp.deadend.noname.dialog.SimpleMessageDialogFragment
import jp.deadend.noname.dialog.TextInputDialogFragment
import jp.deadend.noname.skk.databinding.ActivityCheckedTextBinding
import jp.deadend.noname.skk.databinding.ActivityDictManagerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.util.Collections
import java.util.zip.GZIPInputStream
import kotlin.math.floor
import kotlin.math.sqrt

class SKKDictManager : AppCompatActivity() {
    private lateinit var binding: ActivityDictManagerBinding
    private val mAdapter: TupleAdapter
        get() = binding.dictManagerList.adapter as TupleAdapter
    private var mDictList = listOf<Tuple<String, String>>()

    private val addDictFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            loadDict(uri, -1, false)
        }
    }

    private val commonDictList: List<Tuple<String, String>> by lazy {
        listOf(
            Tuple("ユーザー辞書", getString(R.string.dict_name_user)),
            Tuple("絵文字辞書", getString(R.string.dict_name_emoji))
        ) + listOf(
            "lisplike", "S", "M", "ML", "L", "L.unannotated",
            "jinmei", "geo", "station", "propernoun",
        ).map { type -> Tuple("SKK $type 辞書", "/skk_dict_${type}") }
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDictManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = bars.bottom,
            )
            WindowInsetsCompat.CONSUMED
        }
        setSupportActionBar(binding.dictManagerToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val dictList = skkPrefs.dictOrder // インストール済み辞書をまず列挙
            .split("/")
            .dropLastWhile { it.isEmpty() }
            .asSequence()
            .chunked(2)
            .map { Tuple(it[0], it[1]) }
            .plus(commonDictList) // 一般的な辞書を追加
            .distinctBy { it.value.removePrefix("/") } // 重複を消去
            .toMutableList()
        // インストール済みかどうかチェック
        fileList()
            .filter { it.startsWith("skk_dict_") && it.endsWith(".db") }
            .forEach {
                val entry = it.dropLast(".db".length)
                val type = entry.drop("skk_dict_".length)
                val dupIndex = dictList.indexOfFirst { dict ->
                    entry == dict.value.removePrefix("/")
                }
                if (dupIndex == -1) {
                    dictList.add(Tuple("SKK $type 辞書", entry))
                } else {
                    dictList[dupIndex].value = entry
                }
            }

        binding.dictManagerList.apply {
            layoutManager = LinearLayoutManager(this@SKKDictManager)
            adapter = TupleAdapter(::itemClickListener) // 以降は mAdapter としてアクセス可能
        }
        mAdapter.submitList(dictList)
        mDictList = dictList

        val callback = TupleItemTouchHelperCallback { from, to ->
            val newList = mDictList.toMutableList()
            Collections.swap(newList, from, to)
            mAdapter.submitList(newList)
            mDictList = newList
        }
        ItemTouchHelper(callback)
            .attachToRecyclerView(binding.dictManagerList)
    }

    private fun itemClickListener(position: Int) {
        val newList = mDictList.toMutableList()
        if (newList[position].value.startsWith("/skk_dict_")) {
            downloadDict(newList[position].value.drop("/skk_dict_".length), position)
        } else {
            when (newList[position].value) {
                getString(R.string.dict_name_user) -> return
                getString(R.string.dict_name_emoji) -> return
            }
            val dialog =
                ConfirmationDialogFragment.newInstance(getString(R.string.message_dict_manager_confirm_remove_dict))
            dialog.setListener(
                object : ConfirmationDialogFragment.Listener {
                    override fun onPositiveClick() {
                        val item = newList[position]
                        val dictName = item.key
                        val dictPath = item.value
                        deleteFile("$dictPath.db")
                        deleteFile("$dictPath.lg")
                        if (dictPath.startsWith("skk_dict_")) {
                            newList[position] = Tuple(dictName, "/${dictPath}")
                        } else {
                            newList.remove(item)
                        }
                        mAdapter.submitList(newList)
                        mDictList = newList
                    }

                    override fun onNegativeClick() {}
                })
            dialog.show(supportFragmentManager, "dialog")
        }
    }

    override fun onPause() {
        val dictListString = StringBuilder()
        mDictList
            .filter { !it.value.startsWith('/') }
            .forEach { dictListString.append(it.key, "/", it.value, "/") }

        if (skkPrefs.dictOrder != dictListString.toString()) {
            skkPrefs.dictOrder = dictListString.toString()

            if (SKKService.isRunning()) { // まだ起動していないなら不要
                val intent = Intent(this@SKKDictManager, SKKService::class.java)
                    .putExtra(SKKService.KEY_COMMAND, SKKService.COMMAND_RELOAD_DICT)
                startService(intent)
            }
        }

        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_dict_manager, menu)

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            R.id.menu_dict_manager_add -> {
                addDictFileLauncher.launch(arrayOf("*/*"))
            }

            R.id.menu_dict_manager_reset -> {
                val dialog = ConfirmationDialogFragment.newInstance(
                    getString(R.string.message_dict_manager_confirm_clear)
                )
                dialog.setListener(
                    object : ConfirmationDialogFragment.Listener {
                        override fun onPositiveClick() {
                            fileList().forEach { file ->
                                if (!file.startsWith(getString(R.string.dict_name_user)) &&
                                    !file.startsWith(getString(R.string.dict_name_ascii)) &&
                                    !file.startsWith(getString(R.string.dict_name_emoji))
                                ) {
                                    deleteFile(file)
                                }
                            }
                            try {
                                //unzipFile(resources.assets.open(getString(R.string.dict_name_user) + ".zip"), filesDir)
                                unzipFile(
                                    resources.assets.open(
                                        getString(R.string.dict_name_ascii) + ".zip"
                                    ), filesDir
                                )
                                unzipFile(
                                    resources.assets.open(
                                        getString(R.string.dict_name_emoji) + ".zip"
                                    ), filesDir
                                )
                            } catch (e: IOException) {
                                SimpleMessageDialogFragment.newInstance(
                                    getString(R.string.error_extracting_dict_failed)
                                ).show(supportFragmentManager, "dialog")
                            }
                            mAdapter.submitList(commonDictList)
                            mDictList = commonDictList
                        }

                        override fun onNegativeClick() {}
                    })
                dialog.show(supportFragmentManager, "dialog")
            }

            else -> return super.onOptionsItemSelected(item)
        }

        return true
    }

    private fun downloadDict(type: String, position: Int) {
        val dialog =
            ConfirmationDialogFragment.newInstance(
                getString(
                    R.string.message_dict_manager_confirm_download_dict,
                    type
                )
            )
        dialog.setListener(
            object : ConfirmationDialogFragment.Listener {
                val path = File("${filesDir.absolutePath}/SKK-JISYO.${type}.gz")

                override fun onPositiveClick() {
                    MainScope().launch(Dispatchers.IO) {
                        if (path.exists()) {
                            deleteFile(path.name)
                        }
                        val item = mDictList[position]
                        val progressJob = launch { // 先に進捗表示を設置しておく
                            while (true) {
                                delay(100)
                                ensureActive()
                                val size = formatShortFileSize(applicationContext, path.length())
                                withContext(Dispatchers.Main) {
                                    val newList = mDictList.toMutableList()
                                    newList[position] = Tuple("${item.key} ($size)", item.value)
                                    mAdapter.submitList(newList)
                                    mDictList = newList
                                }
                            }
                        }
                        progressJob.start()
                        URL(
                            if (type == "lisplike") {
                                "https://raw.githubusercontent.com/tamo/AndroidSKK/refs/heads/master/SKK-JISYO.${type}.gz"
                            } else "https://skk-dev.github.io/dict/SKK-JISYO.${type}.gz"
                        )
                            .openStream().use { us ->
                                FileOutputStream(path).use { fs ->
                                    us.copyTo(fs)
                                }
                            }
                        progressJob.let {
                            it.cancelAndJoin()
                            withContext(Dispatchers.Main) {
                                val newList = mDictList.toMutableList()
                                newList[position] = item
                                mAdapter.submitList(newList)
                                mDictList = newList
                            }
                        }
                        withContext(Dispatchers.Main) {
                            loadCommonDict(path, position)
                        }
                    }
                }

                override fun onNegativeClick() {
                    if (path.exists()) {
                        loadCommonDict(path, position)
                    }
                }
            }
        )
        dialog.show(supportFragmentManager, "dialog")
    }

    private fun addDict(dictFileBaseName: String?, position: Int, loader: (Int) -> Unit = {}) {
        if (dictFileBaseName == null) return

        if (position != -1) { // 既存のものを loadDict した後の処理
            val newList = mDictList.toMutableList()
            newList[position] = Tuple(newList[position].key, dictFileBaseName)
            mAdapter.submitList(newList)
            mDictList = newList
            return
        }

        // 新規 item を作ってから loader で loadDict する
        val dialog =
            TextInputDialogFragment.newInstance(getString(R.string.label_dict_manager_input_name))
        dialog.setSingleLine(true)
        dialog.setPlaceHolder(dictFileBaseName.removePrefix("/").removePrefix("dict_"))
        dialog.setListener(
            object : TextInputDialogFragment.Listener {
                override fun onPositiveClick(result: String) {
                    val dictName = if (result.isEmpty()) {
                        getString(R.string.label_dict_manager_optional_dict)
                    } else {
                        result.replace("/", "")
                    }
                    var name = dictName
                    var suffix = 1
                    while (containsName(name)) {
                        suffix++
                        name = "$dictName($suffix)"
                    }
                    val item = Tuple(name, "/$dictFileBaseName")
                    val newList = mDictList.plus(item)
                    mAdapter.submitList(newList)
                    mDictList = newList

                    loader(mDictList.indexOf(item))
                }

                override fun onNegativeClick() {
                    deleteFile("$dictFileBaseName.db")
                    deleteFile("$dictFileBaseName.lg")
                }
            })
        dialog.show(supportFragmentManager, "dialog")
    }

    private fun loadCommonDict(file: File, position: Int) {
        return loadDict(file.toUri(), position, true)
    }

    private fun loadDict(uri: Uri, position: Int, common: Boolean) {
        val name = getFileNameFromUri(this, uri)
        if (name == null) {
            SimpleMessageDialogFragment.newInstance(
                getString(R.string.error_open_dict)
            ).show(supportFragmentManager, "dialog")
            return
        }

        val isGzip = name.endsWith(".gz")
        val nameWithoutGZ = if (isGzip) name.substring(0, name.length - 3) else name

        val dictFileBaseName = if (common && name.startsWith("SKK-JISYO.")) {
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
        if (filesList.any { it.name == "$dictFileBaseName.db" }) {
            val dialog = ConfirmationDialogFragment.newInstance(
                getString(R.string.error_dict_exists, dictFileBaseName)
            )
            dialog.setListener(
                object : ConfirmationDialogFragment.Listener {
                    override fun onPositiveClick() {
                        deleteFile("$dictFileBaseName.db")
                        deleteFile("$dictFileBaseName.lg")
                        loadDict(uri, position, common)
                    }

                    override fun onNegativeClick() {}
                }
            )
            dialog.show(supportFragmentManager, "dialog")
            return
        }

        if (position == -1) { // view がないと進捗が分からないので addDict してからやり直し
            addDict("/$dictFileBaseName", -1) {
                loadDict(uri, it, false)
            }
            return
        }

        MainScope().launch(Dispatchers.IO) {
            val item = mDictList[position]
            var recMan: RecordManager? = null
            try {
                recMan = RecordManagerFactory.createRecordManager(
                    filesDir.absolutePath + "/" + dictFileBaseName
                )
                val btree = BTree<String, String>(recMan, StringComparator())
                recMan.setNamedObject(getString(R.string.btree_name), btree.recordId)
                recMan.commit()

                withContext(Dispatchers.Main) {
                    val newList = mDictList.toMutableList()
                    newList[position] = Tuple("${item.key} (識別中)", item.value)
                    mAdapter.submitList(newList)
                    mDictList = newList
                }
                val charset = if (contentResolver.openInputStream(uri)!!.use { inputStream ->
                        val processedInputStream =
                            if (isGzip) GZIPInputStream(inputStream) else inputStream
                        isTextDictInEucJp(processedInputStream)
                    }) "EUC-JP" else "UTF-8"
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val processedInputStream =
                        if (isGzip) GZIPInputStream(inputStream) else inputStream
                    loadFromTextDict(processedInputStream, charset, false, recMan, btree, false) {
                        if (floor(sqrt(it.toFloat())) % 70 == 0f) {
                            MainScope().launch {
                                val newList = mDictList.toMutableList()
                                newList[position] = Tuple("${item.key} ($it 行目)", item.value)
                                mAdapter.submitList(newList)
                                mDictList = newList
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    val newList = mDictList.toMutableList()
                    newList[position] = item
                    mAdapter.submitList(newList)
                    mDictList = newList
                }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    if (e is CharacterCodingException) {
                        SimpleMessageDialogFragment.newInstance(
                            getString(R.string.error_text_dict_coding)
                        ).show(supportFragmentManager, "dialog")
                    } else {
                        SimpleMessageDialogFragment.newInstance(
                            getString(R.string.error_file_load, name)
                        ).show(supportFragmentManager, "dialog")
                    }
                    val newList = mDictList.minus(item)
                    mAdapter.submitList(newList)
                    mDictList = newList
                }
                Log.e("SKK", "SKKDictManager#loadDict() Error: $e")
                if (recMan != null) {
                    try {
                        recMan.close()
                    } catch (ee: IOException) {
                        Log.e("SKK", "SKKDictManager#loadDict() can't close(): $ee")
                    }

                }
                deleteFile("$dictFileBaseName.db")
                deleteFile("$dictFileBaseName.lg")
                return@launch
            }

            try {
                recMan.close()
            } catch (ee: IOException) {
                Log.e("SKK", "SKKDictManager#loadDict() can't close(): $ee")
                return@launch
            }

            withContext(Dispatchers.Main) {
                addDict(dictFileBaseName.removePrefix("/"), position)
            }
        }
    }

    private fun containsName(s: String) = mDictList.any { s == it.key }

    class TupleAdapter(private val onItemClickListener: (Int) -> Unit) :
        ListAdapter<Tuple<String, String>,
                TupleAdapter.TupleViewHolder>(TupleDiffCallback()) {
        class TupleViewHolder(
            binding: ActivityCheckedTextBinding,
            private val onItemClickListener: (Int) -> Unit
        ) : RecyclerView.ViewHolder(binding.root) {
            val view = binding.dictManagerListItem
            fun bind(item: Tuple<String, String>) {
                view.text = item.key
                view.isChecked = !item.value.startsWith('/')
                view.setOnClickListener {
                    onItemClickListener(adapterPosition)
                }
                android.R.layout.simple_list_item_checked
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TupleViewHolder {
            val binding = ActivityCheckedTextBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return TupleViewHolder(binding, onItemClickListener)
        }

        override fun onBindViewHolder(holder: TupleViewHolder, position: Int) {
            val item = getItem(position)
            holder.bind(item)
        }
    }

    class TupleDiffCallback : DiffUtil.ItemCallback<Tuple<String, String>>() {
        override fun areItemsTheSame(
            oldItem: Tuple<String, String>,
            newItem: Tuple<String, String>
        ): Boolean {
            return oldItem.key == newItem.key && oldItem.value == newItem.value
        }

        override fun areContentsTheSame(
            oldItem: Tuple<String, String>,
            newItem: Tuple<String, String>
        ): Boolean {
            return areItemsTheSame(oldItem, newItem)
        }
    }

    class TupleItemTouchHelperCallback(
        private val onMoveListener: (Int, Int) -> Unit
    ) : ItemTouchHelper.Callback() {

        override fun getMovementFlags(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder
        ): Int {
            val dragFlags = ItemTouchHelper.UP or ItemTouchHelper.DOWN
            return makeMovementFlags(dragFlags, 0)
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            val from = viewHolder.adapterPosition
            val to = target.adapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) {
                return false
            }
            onMoveListener(from, to)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

        override fun isLongPressDragEnabled(): Boolean {
            return true
        }
    }
}

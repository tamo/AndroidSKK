package jp.deadend.noname.skk

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter.formatShortFileSize
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
import jp.deadend.noname.dialog.ConfirmationDialogFragment
import jp.deadend.noname.dialog.SimpleMessageDialogFragment
import jp.deadend.noname.dialog.TextInputDialogFragment
import jp.deadend.noname.skk.databinding.ActivityCheckedTextBinding
import jp.deadend.noname.skk.databinding.ActivityDictManagerBinding
import kotlinx.coroutines.CoroutineScope
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
import java.nio.file.Files
import java.util.Collections
import java.util.zip.GZIPInputStream
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.milliseconds

class SKKDictManager : AppCompatActivity() {
    private lateinit var binding: ActivityDictManagerBinding
    private val mAdapter: TupleAdapter
        get() = binding.dictManagerList.adapter as TupleAdapter
    private var mDictList = listOf<SKKStoreTuple>()
    private var mCurrentPreviewingItem: SKKStoreTuple? = null

    private val addDictFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            loadDict(uri, -1, false)
        }
    }

    private val commonDictList: List<SKKStoreTuple> by lazy {
        listOf(
            SKKStoreTuple("ユーザー辞書", getString(R.string.dict_name_user))
        ) + listOf(
            "lisplike", "L+", "L", "L.unannotated", "S",
            "jinmei", "geo", "station", "propernoun",
            "itaiji", "fullname", "emoji",
            "assoc", "edict2", "okinawa", "JIS2"
        ).map { type -> SKKStoreTuple("SKK $type 辞書", "/skk_dict_${type}") }
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

        binding.dictManagerList.apply {
            layoutManager = LinearLayoutManager(this@SKKDictManager)
            adapter = TupleAdapter(::itemClickListener) // 以降は mAdapter としてアクセス可能
        }

        MainScope().launch {
            val dictList = withContext(Dispatchers.IO) {
                val list = skkPrefs.dictOrder // インストール済み辞書をまず列挙
                    .split("/")
                    .dropLastWhile { it.isEmpty() }
                    .asSequence()
                    .chunked(2)
                    .map { SKKStoreTuple(it[0], it[1]) }
                    .plus(commonDictList) // 一般的な辞書を追加
                    .distinctBy { it.value.removePrefix("/") } // 重複を消去
                    .toMutableList()
                // インストール済みかどうかチェック
                Files.newDirectoryStream(filesDir.toPath(), "skk_dict_*.mv").use { stream ->
                    stream.forEach { path ->
                        val fullName = path.fileName.toString()
                        val filename = fullName.removeSuffix(".mv")
                        val existing = list.find { it.value.removePrefix("/") == filename }

                        if (existing == null) {
                            val type = filename.removePrefix("skk_dict_")
                            list.add(SKKStoreTuple("SKK $type 辞書", filename))
                        } else {
                            val index = list.indexOf(existing)
                            list[index] = existing.copy(value = filename)
                        }
                    }
                }
                list
            }

            mAdapter.submitList(dictList)
            mDictList = dictList
        }

        val callback = TupleItemTouchHelperCallback { from, to ->
            val newList = mDictList.toMutableList()
            Collections.swap(newList, from, to)
            mAdapter.submitList(newList)
            mDictList = newList
        }
        ItemTouchHelper(callback)
            .attachToRecyclerView(binding.dictManagerList)

        binding.dictManagerUninstallButton.setOnClickListener {
            val position = mDictList.indexOf(mCurrentPreviewingItem)
            if (position != -1) {
                confirmUninstall(mCurrentPreviewingItem!!, position)
            }
        }
        binding.dictManagerPreviewText.movementMethod =
            android.text.method.ScrollingMovementMethod()
        binding.dictManagerPreviewText.setHorizontallyScrolling(true)
    }

    private fun itemClickListener(position: Int) {
        val item = mDictList[position]
        if (item.value.startsWith("/skk_dict_")) {
            downloadDict(item.value.removePrefix("/skk_dict_"), position)
        } else {
            showPreview(item)
        }
    }

    private fun showPreview(item: SKKStoreTuple) {
        if (mCurrentPreviewingItem == item) {
            hidePreview()
            return
        }

        mCurrentPreviewingItem = item
        binding.dictManagerPreviewLayout.visibility = android.view.View.VISIBLE
        binding.dictManagerPreviewTitle.text = item.key
        binding.dictManagerPreviewText.text = "読み込み中..."

        val isCore = item.value == getString(R.string.dict_name_user)
        binding.dictManagerUninstallButton.visibility =
            if (isCore) android.view.View.GONE else android.view.View.VISIBLE

        MainScope().launch(Dispatchers.IO) {
            val filePath = filesDir.absolutePath + "/" + item.value
            val previewText = runCatching {
                SKKService.getStore(filePath)?.let { openedStore ->
                    previewText(openedStore)
                } ?: openDB(filePath, getString(R.string.btree_name), false).use { store ->
                    previewText(store)
                }
            }.getOrElse { "Error: ${it.message}" }

            withContext(Dispatchers.Main) {
                binding.dictManagerPreviewText.apply {
                    text = previewText
                    scrollX = 0
                    scrollY = 0
                }
            }
        }
    }

    private fun CoroutineScope.previewText(store: SKKStore): String {
        val sb = StringBuilder()
        val maxSamples = binding.dictManagerPreviewText.maxLines
        val storeSize = store.size()
        val step = (storeSize / maxSamples).coerceAtLeast(1)
        val padLen = storeSize.toString().length
        for (i in 0 until maxSamples) {
            ensureActive()
            val index = i * step
            if (index >= storeSize) break
            val paddedIndex = index.toString().padStart(padLen, '0')
            store.get(index)?.let { sb.append("$paddedIndex: ${it.key} ${it.value}\n") }
        }

        return if (sb.isNotEmpty()) sb.toString() else getString(R.string.label_tools_no_entry_found)
    }

    private fun hidePreview() {
        mCurrentPreviewingItem = null
        binding.dictManagerPreviewLayout.visibility = android.view.View.GONE
    }

    private fun confirmUninstall(item: SKKStoreTuple, position: Int) = ConfirmationDialogFragment
        .newInstance(getString(R.string.message_dict_manager_confirm_remove_dict)).let {
            it.setListener(object : ConfirmationDialogFragment.Listener {
                override fun onNegativeClick() {}
                override fun onPositiveClick() {
                    val dictName = item.key
                    val dictPath = item.value
                    deleteFile("$dictPath.mv")
                    val newList = mDictList.toMutableList()
                    if (dictPath.startsWith("skk_dict_")) {
                        newList[position] = SKKStoreTuple(dictName, "/${dictPath}")
                    } else {
                        newList.removeAt(position)
                    }
                    mAdapter.submitList(newList)
                    mDictList = newList
                    if (mCurrentPreviewingItem == item) {
                        hidePreview()
                    }
                }
            })
            it.show(supportFragmentManager, "dialog")
        }

    override fun onPause() {
        val dictListString = mDictList
            .filterNot { it.value.startsWith('/') }
            .joinToString("") { "${it.key}/${it.value}/" }

        if (skkPrefs.dictOrder != dictListString) {
            skkPrefs.dictOrder = dictListString

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
                fun ifDeletable(callback: (String) -> Boolean) {
                    val protectedPrefixes = listOf(
                        R.string.dict_name_user, R.string.dict_name_ascii,
                        R.string.dict_name_emoji, R.string.dict_name_symbol
                    ).map { getString(it) }

                    fun isDeletable(file: String): Boolean =
                        protectedPrefixes.none { file.startsWith(it) } &&
                                file != SKKKanaRule.INTERNAL_FILE_NAME

                    Files.newDirectoryStream(filesDir.toPath()).use { stream ->
                        stream.forEach { path ->
                            val file = path.fileName.toString()
                            if (isDeletable(file)) callback(file)
                        }
                    }
                }

                val files = mutableListOf<String>()
                ifDeletable { files.add(it) }
                val dialog = ConfirmationDialogFragment.newInstance(
                    getString(R.string.message_dict_manager_confirm_clear, files.joinToString())
                )
                dialog.setListener(
                    object : ConfirmationDialogFragment.Listener {
                        override fun onPositiveClick() {
                            ifDeletable { deleteFile(it) }
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
                                delay(100.milliseconds)
                                ensureActive()
                                val size = formatShortFileSize(applicationContext, path.length())
                                withContext(Dispatchers.Main) {
                                    val newList = mDictList.toMutableList()
                                    newList[position] =
                                        SKKStoreTuple("${item.key} ($size)", item.value)
                                    mAdapter.submitList(newList)
                                    mDictList = newList
                                }
                            }
                        }
                        progressJob.start()
                        URL(
                            when (type) {
                                "lisplike" ->
                                    "https://raw.githubusercontent.com/tamo/AndroidSKK/refs/heads/master/SKK-JISYO.${type}.gz"

                                else ->
                                    "https://tamo.github.io/dict/SKK-JISYO.${type}.gz"
                            }
                        ).openStream().buffered().use { us ->
                            FileOutputStream(path).buffered().use { fs ->
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

                override fun onNegativeClick() {}
            }
        )
        dialog.show(supportFragmentManager, "dialog")
    }

    private fun addDict(dictFileBaseName: String?, position: Int, loader: (Int) -> Unit = {}) {
        if (dictFileBaseName == null) return

        if (position != -1) { // 既存のものを loadDict した後の処理
            val newList = mDictList.toMutableList()
            newList[position] = SKKStoreTuple(newList[position].key, dictFileBaseName)
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
                    val item = SKKStoreTuple(name, "/$dictFileBaseName")
                    val newList = mDictList.plus(item)
                    mAdapter.submitList(newList)
                    mDictList = newList

                    loader(mDictList.indexOf(item))
                }

                override fun onNegativeClick() {
                    deleteFile("$dictFileBaseName.mv")
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
        val nameWithoutGZ = name.removeSuffix(".gz")

        val dictFileBaseName = if (common && name.startsWith("SKK-JISYO.")) {
            "skk_dict_" + nameWithoutGZ.removePrefix("SKK-JISYO.")
        } else {
            "dict_" + nameWithoutGZ.replace(".", "_")
        }

        val filesDir = filesDir
        if (filesDir == null) {
            SimpleMessageDialogFragment.newInstance(
                getString(R.string.error_access_failed, filesDir)
            ).show(supportFragmentManager, "dialog")
            return
        }
        if (File(filesDir, "$dictFileBaseName.mv").exists()) {
            val dialog = ConfirmationDialogFragment.newInstance(
                getString(R.string.error_dict_exists, dictFileBaseName)
            )
            dialog.setListener(
                object : ConfirmationDialogFragment.Listener {
                    override fun onPositiveClick() {
                        deleteFile("$dictFileBaseName.mv")
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
            var store: SKKStore? = null
            var success = false
            try {
                store = openDB(
                    filesDir.absolutePath + "/" + dictFileBaseName,
                    getString(R.string.btree_name)
                )

                withContext(Dispatchers.Main) {
                    val newList = mDictList.toMutableList()
                    newList[position] = SKKStoreTuple("${item.key} (識別中)", item.value)
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
                    loadFromTextDict(processedInputStream, charset, false, store, false) {
                        if (floor(sqrt(it.toFloat())) % 70 == 0f) {
                            MainScope().launch {
                                val newList = mDictList.toMutableList()
                                newList[position] =
                                    SKKStoreTuple("${item.key} ($it 行目)", item.value)
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
                success = true
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
                SKKLog.e("loadDict() Error", e)
            } finally {
                store?.let { s ->
                    runCatching { s.close() }
                        .onFailure { SKKLog.e("loadDict() can't close()", it) }
                }
                if (!success) {
                    deleteFile("$dictFileBaseName.mv")
                }
            }

            if (success) withContext(Dispatchers.Main) {
                addDict(dictFileBaseName.removePrefix("/"), position)
                showPreview(mDictList[position])
            }
        }
    }

    private fun containsName(s: String) = mDictList.any { s == it.key }

    class TupleAdapter(private val onItemClickListener: (Int) -> Unit) :
        ListAdapter<SKKStoreTuple,
                TupleAdapter.TupleViewHolder>(TupleDiffCallback()) {
        class TupleViewHolder(
            binding: ActivityCheckedTextBinding,
            private val onItemClickListener: (Int) -> Unit
        ) : RecyclerView.ViewHolder(binding.root) {
            val view = binding.dictManagerListItem
            fun bind(item: SKKStoreTuple) {
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

    class TupleDiffCallback : DiffUtil.ItemCallback<SKKStoreTuple>() {
        override fun areItemsTheSame(
            oldItem: SKKStoreTuple,
            newItem: SKKStoreTuple
        ): Boolean {
            return oldItem.key == newItem.key && oldItem.value == newItem.value
        }

        override fun areContentsTheSame(
            oldItem: SKKStoreTuple,
            newItem: SKKStoreTuple
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

package jp.deadend.noname.skk

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.get
import androidx.core.view.updatePadding
import jp.deadend.noname.dialog.ConfirmationDialogFragment
import jp.deadend.noname.dialog.SimpleMessageDialogFragment
import jp.deadend.noname.skk.databinding.ActivityUserDictToolBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.zip.GZIPInputStream
import kotlin.math.floor
import kotlin.math.sqrt

class SKKUserDictTool : AppCompatActivity() {
    private lateinit var mDictName: String
    private var mEntryList = mutableListOf<SKKStoreTuple>()
    private var mFoundList = mutableListOf<SKKStoreTuple>()
    private lateinit var mAdapter: EntryAdapter
    private lateinit var mSearchView: SearchView
    private lateinit var mSearchAdapter: EntryAdapter
    private lateinit var mMenu: Menu
    private var mSearchJob: Job = Job()
    private var mDatabaseJob: Job = Job()
    private var mInFileLauncher = false
    private val mScope = MainScope()

    private val importFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            mInFileLauncher = false
            return@registerForActivityResult
        }
        SKKLog.d("importing $uri")
        mSearchJob.cancel()
        mDatabaseJob.cancel()
        mDatabaseJob = mScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                mAdapter.clear()
                mSearchView.queryHint = "辞書に書き込む準備中"
            }
            withUserDict { store ->
                withContext(Dispatchers.Main) { mMenu.setGroupEnabled(0, false) }

                try {
                    val name = getFileNameFromUri(this@SKKUserDictTool, uri)
                        ?: throw IOException("getFileNameFromUri returned null")
                    val isGzip = name.endsWith(".gz")
                    val isWordList = name.endsWith("combined.gz")

                    withContext(Dispatchers.Main) { mSearchView.queryHint = "文字セット識別中" }
                    val charset = if (!isWordList &&
                        (contentResolver.openInputStream(uri)
                            ?: return@withUserDict).use { inputStream ->
                            val processedInputStream =
                                if (isGzip) GZIPInputStream(inputStream) else inputStream
                            isTextDictInEucJp(processedInputStream)
                        }
                    ) "EUC-JP" else "UTF-8"

                    withContext(Dispatchers.Main) { mSearchView.queryHint = "インポート中" }
                    contentResolver.openInputStream(uri)?.use { inputStream ->
                        val processedInputStream =
                            if (isGzip) GZIPInputStream(inputStream) else inputStream
                        loadFromTextDict(
                            processedInputStream, charset, isWordList, store, false
                        ) {
                            if (floor(sqrt(it.toFloat())) % 50 == 0f) { // 味わい進捗
                                mScope.launch(Dispatchers.Main) {
                                    mSearchView.queryHint = "インポート中 ${it}行目"
                                }
                            }
                        }
                    }
                } catch (e: IOException) {
                    val errorMessage = when {
                        e is CharacterCodingException -> getString(R.string.error_text_dict_coding)
                        e.message.orEmpty().contains("getFileNameFromUri") ->
                            getString(R.string.error_file_load, e.message)

                        else -> getString(
                            R.string.error_file_load,
                            getFileNameFromUri(this@SKKUserDictTool, uri)
                        )
                    }
                    withContext(Dispatchers.Main) {
                        SimpleMessageDialogFragment.newInstance(errorMessage)
                            .show(supportFragmentManager, "dialog")
                    }
                }
            }
            mInFileLauncher = false
            withContext(Dispatchers.Main) {
                mMenu.setGroupEnabled(0, true)
                updateListItems()
            }
        }
    }

    private val exportFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) {
            mInFileLauncher = false
            return@registerForActivityResult
        }
        SKKLog.d("exporting $uri")
        if (mDatabaseJob.isActive) {
            Toast.makeText(
                applicationContext,
                "読み込みが終了してからエクスポートします",
                Toast.LENGTH_SHORT
            ).show()
        }
        mDatabaseJob.cancel()
        mDatabaseJob = mScope.launch(Dispatchers.IO) {
            val errorMessage = withUserDict(writable = false) { store ->
                runCatching {
                    contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use {
                        val browser =
                            store.cursor() ?: throw (IOException("database browser is null"))
                        generateSequence { browser.next() }.forEach { (key, value) ->
                            it.write("$key $value\n")
                        }
                    } ?: throw IOException("Failed to open output stream")
                }.exceptionOrNull()?.let { e -> e.message ?: e.toString() } ?: ""
            } ?: "Failed to open dictionary"

            mInFileLauncher = false
            withContext(Dispatchers.Main) {
                updateListItems()
                SimpleMessageDialogFragment.newInstance(
                    if (errorMessage.isEmpty()) {
                        getString(
                            R.string.message_tools_written_to_external_storage,
                            getFileNameFromUri(this@SKKUserDictTool, uri)
                        )
                    } else {
                        getString(
                            R.string.error_write_to_external_storage,
                            errorMessage
                        )
                    }
                ).show(supportFragmentManager, "dialog")
            }
        }
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mDictName = intent.dataString ?: return
        val binding = ActivityUserDictToolBinding.inflate(layoutInflater)
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
            WindowInsetsCompat.CONSUMED
        }
        setSupportActionBar(binding.userDictToolToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(
            when (mDictName.trimStart { it == '*' || it == '+' }) {
                getString(R.string.dict_name_ascii) -> R.string.label_ascii_tool_activity
                getString(R.string.dict_name_emoji) -> R.string.label_emoji_tool_activity
                getString(R.string.dict_name_symbol) -> R.string.label_symbol_tool_activity
                else -> R.string.label_dict_tool_activity
            }
        )

        binding.userDictToolList.onItemClickListener =
            AdapterView.OnItemClickListener { parent, _, position, _ ->
                if (!mSearchView.isEnabled || mDatabaseJob.isActive) { // 読み込み中
                    return@OnItemClickListener
                }
                val dialog =
                    ConfirmationDialogFragment.newInstance(getString(R.string.message_tools_confirm_remove_entry))
                dialog.setListener(
                    object : ConfirmationDialogFragment.Listener {
                        override fun onPositiveClick() {
                            val adapter = parent.adapter as EntryAdapter
                            val item = adapter.getItem(position) ?: return
                            SKKLog.d("remove $item from $mDictName")
                            mDatabaseJob.cancel()
                            mDatabaseJob = mScope.launch(Dispatchers.IO) {
                                withUserDict { store ->
                                    store.delete(item.key)
                                    withContext(Dispatchers.Main) {
                                        mEntryList.remove(item)
                                        mFoundList.remove(item)
                                        adapter.notifyDataSetChanged()
                                    }
                                }
                            }
                        }

                        override fun onNegativeClick() {}
                    })
                dialog.show(supportFragmentManager, "dialog")
            }

        mSearchView = binding.userDictToolSearch
        mSearchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                mSearchView.clearFocus()
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                binding.userDictToolList.emptyView = binding.EmptyListItem // 初回だけでいいけど

                if (!mSearchView.isEnabled) {
                    if (mSearchView.query.isNotEmpty()) {
                        mSearchView.setQuery("", false)
                    }
                    return true
                }

                mSearchJob.cancel()
                if (newText == null) {
                    binding.userDictToolList.adapter = mAdapter
                    return true
                }
                val regex: Regex? by lazy { runCatching { Regex(newText) }.getOrNull() }
                mFoundList.clear()
                mSearchJob = mScope.launch(Dispatchers.Default) {
                    mFoundList.addAll(mEntryList.filter {
                        ensureActive() // ここでキャンセルされる
                        if (regex != null) {
                            regex!!.containsMatchIn(it.key) || regex!!.containsMatchIn(it.value)
                        } else {
                            newText in it.key || newText in it.value
                        }
                    })
                    withContext(Dispatchers.Main) {
                        binding.userDictToolList.adapter = mSearchAdapter
                    }
                }
                return true
            }
        })

        mAdapter = EntryAdapter(this, mEntryList)
        binding.userDictToolList.adapter = mAdapter
        mSearchAdapter = EntryAdapter(this, mFoundList)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        mMenu = menu
        menuInflater.inflate(R.menu.menu_user_dict_tool, menu)
        if (mDictName == getString(R.string.dict_name_user)) {
            menu[2].isEnabled = false
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_user_dict_tool_import -> {
                mInFileLauncher = true
                importFileLauncher.launch(arrayOf("*/*"))
                return true
            }

            R.id.menu_user_dict_tool_export -> {
                mInFileLauncher = true
                exportFileLauncher.launch("$mDictName.txt")
                return true
            }

            R.id.menu_user_dict_tool_initialize,
            R.id.menu_user_dict_tool_clear -> {
                val cfDialog = ConfirmationDialogFragment.newInstance(
                    getString(R.string.message_tools_confirm_clear)
                )
                cfDialog.setListener(
                    object : ConfirmationDialogFragment.Listener {
                        override fun onPositiveClick() {
                            recreateUserDict(item.itemId == R.id.menu_user_dict_tool_initialize)
                        }

                        override fun onNegativeClick() {}
                    })
                cfDialog.show(supportFragmentManager, "dialog")
                return true
            }

            android.R.id.home -> finish()
        }

        return super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()

        // FileLauncher 系統の ActivityResult の後にもここを通る
        if (!mInFileLauncher && mAdapter.count == 0) {
            when (val commandChar = mDictName.first()) {
                '*', '+' -> {
                    mDictName = mDictName.removePrefix(commandChar.toString())
                    val cfDialog = ConfirmationDialogFragment.newInstance(
                        getString(R.string.message_tools_confirm_clear)
                    )
                    cfDialog.setListener(
                        object : ConfirmationDialogFragment.Listener {
                            override fun onPositiveClick() {
                                recreateUserDict(commandChar == '+')
                            }

                            override fun onNegativeClick() {
                                updateListItems()
                            }
                        })
                    cfDialog.show(supportFragmentManager, "dialog")
                }

                else -> updateListItems()
            }
        }
    }

    public override fun onPause() {
        mSearchJob.cancel()
        mDatabaseJob.cancel()
        super.onPause() // 画面は早めに消してしまう
        reloadDict()
    }

    override fun onDestroy() {
        super.onDestroy()
        mScope.cancel()
    }

    private fun recreateUserDict(extract: Boolean) {
        mAdapter.clear()

        mDatabaseJob.cancel()
        mDatabaseJob = mScope.launch(Dispatchers.IO) {
            deleteFile("$mDictName.mv")

            if (extract) {
                runCatching {
                    unzipFile(resources.assets.open("$mDictName.zip"), filesDir)
                    SKKLog.d("$mDictName.zip extracted")
                }.onFailure { SKKLog.e("I/O error in extracting $mDictName.zip", it) }
                withContext(Dispatchers.Main) {
                    updateListItems()
                }
            } else {
                runCatching {
                    withUserDict { store -> store.commit() }
                }.onFailure { e -> throw RuntimeException(e) }
                SKKLog.d("New user dictionary created")
                withContext(Dispatchers.Main) {
                    mAdapter.clear() // どうせ空なので updateListItems() 不要
                }
            }
        }
    }

    private fun onFailToOpenUserDict() {
        mSearchView.queryHint = ""

        ConfirmationDialogFragment.newInstance(getString(R.string.error_tools_open_user_dict)).let {
            it.setListener(
                object : ConfirmationDialogFragment.Listener {
                    override fun onPositiveClick() {
                        recreateUserDict(extract = false)
                    }

                    override fun onNegativeClick() {
                        reloadDict()
                        finish()
                    }
                })
            it.show(supportFragmentManager, "dialog")
        }
    }

    private suspend fun <T> withUserDict(
        writable: Boolean = true, block: suspend (SKKStore) -> T
    ): T? = withContext(Dispatchers.IO) {
        val dictPath = filesDir.absolutePath + "/" + mDictName
        val openedStore = SKKService.getStore(dictPath)
        val (store: SKKStore, isShared: Boolean) = if (openedStore != null) {
            openedStore to true
        } else {
            val btreeName = getString(R.string.btree_name)
            runCatching {
                openDB(dictPath, btreeName, writable) to false
            }.getOrElse {
                SKKLog.e("withUserDict error opening store", it)
                withContext(Dispatchers.Main) { onFailToOpenUserDict() }
                return@withContext null
            }
        }

        try {
            block(store)
        } catch (e: Exception) {
            when (e) {
                is IllegalStateException if isShared &&
                        e.message?.contains("closed", ignoreCase = true) == true ->
                    SKKLog.w("withUserDict: Shared store was closed", e)

                is CancellationException -> throw e
                else -> SKKLog.e("withUserDict error in block", e)
            }
            null
        } finally {
            if (writable) runCatching {
                store.commit()
            }.onFailure { SKKLog.e("withUserDict commit error", it) }

            if (!isShared) runCatching {
                store.close()
            }.onFailure { SKKLog.e("withUserDict close error", it) }
        }
    }

    private fun updateListItems() {
        SKKLog.d("updateListItems")
        mSearchJob.cancel()
        mDatabaseJob.cancel()
        mDatabaseJob = mScope.launch(Dispatchers.IO) {
            withUserDict(writable = false) { store ->
                withContext(Dispatchers.Main) {
                    mSearchView.isEnabled = false
                    mAdapter.clear()
                }
                runCatching {
                    val browser = store.cursor() ?: run {
                        SKKLog.e("updateListItems: browser=null")
                        withContext(Dispatchers.Main) { onFailToOpenUserDict() }
                        return@withUserDict
                    }

                    val buffer = mutableListOf<SKKStoreTuple>()
                    val bufferSize = 1000
                    while (true) {
                        val result = browser.next() ?: break
                        buffer.add(result)
                        if (buffer.size < bufferSize) continue
                        withContext(Dispatchers.Main) {
                            mAdapter.addAll(buffer)
                            mSearchView.queryHint =
                                "読み込み中 ${100 * mAdapter.count / store.size()}%"
                        }
                        buffer.clear()
                        ensureActive()
                    }
                    withContext(Dispatchers.Main) {
                        mAdapter.addAll(buffer)
                        mSearchView.isEnabled = true
                        mSearchView.queryHint = ""
                    }
                }.onFailure { e ->
                    if (e is CancellationException) {
                        SKKLog.d("updateListItems: canceled")
                        throw e
                    } else {
                        SKKLog.e("updateListItems", e)
                        withContext(Dispatchers.Main) { onFailToOpenUserDict() }
                    }
                }
            }
            SKKLog.d("updateListItems: finished")
        }
    }

    private fun reloadDict() = if (SKKService.isRunning()) {
        val intent = Intent(this@SKKUserDictTool, SKKService::class.java)
        intent.putExtra(SKKService.KEY_COMMAND, SKKService.COMMAND_RELOAD_DICT)
        startService(intent)
    } else SKKLog.d("reloadDict: service not running")

    private class EntryAdapter(
        context: Context,
        items: List<SKKStoreTuple>
    ) : ArrayAdapter<SKKStoreTuple>(context, 0, items) {
        private val mLayoutInflater = LayoutInflater.from(context)

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val tv = convertView
                ?: mLayoutInflater.inflate(android.R.layout.simple_list_item_1, parent, false)

            val item = getItem(position) ?: SKKStoreTuple("", "")
            (tv as TextView).text =
                context.getString(R.string.item_tools_entry, item.key, item.value)

            return tv
        }
    }
}

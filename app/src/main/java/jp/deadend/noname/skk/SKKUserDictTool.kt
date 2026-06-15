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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.zip.GZIPInputStream
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.milliseconds

class SKKUserDictTool : AppCompatActivity() {
    private lateinit var mDictName: String
    private var mStore: SKKStore? = null
    private var mEntryList = mutableListOf<SKKStoreTuple>()
    private var mFoundList = mutableListOf<SKKStoreTuple>()
    private lateinit var mAdapter: EntryAdapter
    private lateinit var mSearchView: SearchView
    private lateinit var mSearchAdapter: EntryAdapter
    private lateinit var mMenu: Menu
    private var mSearchJob: Job = Job()
    private var mDatabaseJob: Job = Job()
    private var mInFileLauncher = false

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
        mDatabaseJob = MainScope().launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                mAdapter.clear()
                mSearchView.queryHint = "辞書に書き込む準備中"
            }
            if (!openUserDict()) {
                mInFileLauncher = false
                return@launch
            }
            withContext(Dispatchers.Main) { mMenu.setGroupEnabled(0, false) }

            val name = getFileNameFromUri(this@SKKUserDictTool, uri)!!
            val isGzip = name.endsWith(".gz")
            val isWordList = name.endsWith("combined.gz")

            try {
                withContext(Dispatchers.Main) { mSearchView.queryHint = "文字セット識別中" }
                val charset = if (!isWordList &&
                    contentResolver.openInputStream(uri)!!.use { inputStream ->
                        val processedInputStream =
                            if (isGzip) GZIPInputStream(inputStream) else inputStream
                        isTextDictInEucJp(processedInputStream)
                    }
                ) "EUC-JP"
                else "UTF-8"

                withContext(Dispatchers.Main) { mSearchView.queryHint = "インポート中" }
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val processedInputStream =
                        if (isGzip) GZIPInputStream(inputStream) else inputStream
                    loadFromTextDict(
                        processedInputStream,
                        charset,
                        isWordList,
                        mStore!!,
                        false
                    ) {
                        if (floor(sqrt(it.toFloat())) % 50 == 0f) { // 味わい進捗
                            MainScope().launch(Dispatchers.Main) {
                                mSearchView.queryHint = "インポート中 ${it}行目"
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    if (e is CharacterCodingException) {
                        SimpleMessageDialogFragment.newInstance(
                            getString(R.string.error_text_dict_coding)
                        ).show(supportFragmentManager, "dialog")
                    } else {
                        SimpleMessageDialogFragment.newInstance(
                            getString(
                                R.string.error_file_load,
                                getFileNameFromUri(this@SKKUserDictTool, uri)
                            )
                        ).show(supportFragmentManager, "dialog")
                    }
                }
            }
            closeUserDict()
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
        mDatabaseJob = MainScope().launch(Dispatchers.IO) {
            if (!openUserDict(writable = false)) {
                mInFileLauncher = false
                return@launch
            }
            val errorMessage = runCatching {
                contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use {
                    val browser =
                        mStore?.cursor() ?: throw (IOException("database browser is null"))
                    generateSequence { browser.next() }.forEach { (key, value) ->
                        it.write("$key $value\n")
                    }
                } ?: throw IOException("Failed to open output stream")
            }.exceptionOrNull()?.let { e -> e.message ?: e.toString() } ?: ""
            closeUserDict()

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
        mDictName = intent.dataString!!
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
                            val item = adapter.getItem(position)!!
                            SKKLog.d("remove $item from $mDictName")
                            MainScope().launch(Dispatchers.IO) {
                                runCatching {
                                    if (openUserDict()) {
                                        mStore?.delete(item.key)
                                        withContext(Dispatchers.Main) {
                                            mEntryList.remove(item)
                                            mFoundList.remove(item)
                                            adapter.notifyDataSetChanged()
                                        }
                                    }
                                    closeUserDict()
                                }.onFailure { SKKLog.e("error removing ${item.key}", it) }
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
                mSearchJob = MainScope().launch(Dispatchers.Default) {
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
        super.onPause() // 画面は早めに消してしまう
        runBlocking(Dispatchers.IO) {
            closeUserDict() // ここで時間がかかる可能性がある
            startServiceCommand(SKKService.COMMAND_RELOAD_DICT)
        }
    }

    private fun recreateUserDict(extract: Boolean) {
        mAdapter.clear()

        MainScope().launch(Dispatchers.IO) {
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
                    mStore = openDB(
                        filesDir.absolutePath + "/" + mDictName,
                        getString(R.string.btree_name)
                    )
                    mStore?.commit()
                    mStore?.close()
                    mStore = null
                }.onFailure { e ->
                    val store = mStore
                    mStore = null
                    store?.close()
                    throw RuntimeException(e)
                }
                SKKLog.d("New user dictionary created")
                withContext(Dispatchers.Main) {
                    mAdapter.clear() // どうせ空なので updateListItems() 不要
                }
            }
        }
    }

    private fun onFailToOpenUserDict() {
        mStore = null
        mSearchView.queryHint = ""

        ConfirmationDialogFragment.newInstance(getString(R.string.error_tools_open_user_dict)).let {
            it.setListener(
                object : ConfirmationDialogFragment.Listener {
                    override fun onPositiveClick() {
                        recreateUserDict(extract = false)
                    }

                    override fun onNegativeClick() {
                        startServiceCommand(SKKService.COMMAND_RELOAD_DICT)
                        finish()
                    }
                })
            it.show(supportFragmentManager, "dialog")
        }
    }

    private suspend fun openUserDict(writable: Boolean = true): Boolean {
        if (mStore != null) {
            SKKLog.e("openUserDict error: already opened")
            withContext(Dispatchers.Main) { onFailToOpenUserDict() }
            return false
        }

        // onPause() か finish() で reload するまで service / engine から使用できなくする
        if (SKKService.isRunning()) withTimeoutOrNull(500.milliseconds) {
            withContext(Dispatchers.Main) {
                mSearchView.queryHint = "辞書をキーボード側で閉じています"
            }
            val job = launch {
                SKKService.sharedFlow.first { it == SKKService.EVENT_DICT_CLOSING }
            } // 先に開始しておく
            startServiceCommand(SKKService.COMMAND_CLOSE_DICT)
            job.join()
        } ?: run {
            SKKLog.e("openUserDict error: service not responding")
            withContext(Dispatchers.Main) { onFailToOpenUserDict() }
            return false
        }

        val dictPath = filesDir.absolutePath + "/" + mDictName
        val btreeName = getString(R.string.btree_name)

        runCatching {
            mStore = openDB(dictPath, btreeName, writable)
            withContext(Dispatchers.Main) { mSearchView.queryHint = "" }
            SKKLog.d("openUserDict finished")
            return true
        }.onFailure { SKKLog.e("openUserDict error opening mStore", it) }

        withContext(Dispatchers.Main) { onFailToOpenUserDict() }
        return false
    }

    private fun closeUserDict() {
        val store = mStore ?: return
        mStore = null

        val commitException = runCatching { store.commit() }.exceptionOrNull()
        val closeException = runCatching { store.close() }.exceptionOrNull()

        commitException?.let { SKKLog.e("closeUserDict commit error", it) }
        closeException?.let {
            SKKLog.e("closeUserDict close error", it)
            startServiceCommand(SKKService.COMMAND_RELOAD_DICT)
        } ?: SKKLog.d("closeUserDict finished")

        (commitException ?: closeException)?.let { throw it }
    }

    private fun updateListItems() {
        SKKLog.d("updateListItems")
        mSearchJob.cancel()
        mDatabaseJob.cancel()
        mDatabaseJob = MainScope().launch(Dispatchers.IO) {
            if (!openUserDict(writable = false)) return@launch
            withContext(Dispatchers.Main) {
                mSearchView.isEnabled = false
                mAdapter.clear()
            }
            runCatching {
                val browser = mStore?.cursor() ?: run {
                    SKKLog.e("updateListItems: browser=null")
                    withContext(Dispatchers.Main) { onFailToOpenUserDict() }
                    closeUserDict()
                    return@launch
                }

                val buffer = mutableListOf<SKKStoreTuple>()
                val bufferSize = 1000
                while (true) {
                    val result = browser.next() ?: break
                    buffer.add(result)
                    if (buffer.size < bufferSize) continue
                    withContext(Dispatchers.Main) {
                        mAdapter.addAll(buffer)
                        mStore.let { store ->
                            if (store == null) throw CancellationException()
                            mSearchView.queryHint =
                                "読み込み中 ${100 * mAdapter.count / store.size()}%"
                        }
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
                    closeUserDict()
                    throw e
                } else {
                    SKKLog.e("updateListItems", e)
                    withContext(Dispatchers.Main) { onFailToOpenUserDict() }
                }
            }
            closeUserDict()
            SKKLog.d("updateListItems: finished")
        }
    }

    private fun startServiceCommand(command: String) {
        if (SKKService.isRunning()) {
            val intent = Intent(this@SKKUserDictTool, SKKService::class.java)
            intent.putExtra(SKKService.KEY_COMMAND, command)
            startService(intent)
        }
    }

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

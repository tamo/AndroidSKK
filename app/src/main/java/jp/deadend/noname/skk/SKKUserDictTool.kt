package jp.deadend.noname.skk

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
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
import androidx.core.view.updatePadding
import jp.deadend.noname.dialog.ConfirmationDialogFragment
import jp.deadend.noname.dialog.SimpleMessageDialogFragment
import jp.deadend.noname.skk.databinding.ActivityUserDictToolBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.zip.GZIPInputStream
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.milliseconds

class SKKUserDictTool : AppCompatActivity() {
    private lateinit var mDictName: String
    private var mStore: SKKDictionaryStore? = null
    private var mEntryList = mutableListOf<SKKDictionaryTuple>()
    private var mFoundList = mutableListOf<SKKDictionaryTuple>()
    private lateinit var mAdapter: EntryAdapter
    private lateinit var mSearchView: SearchView
    private lateinit var mSearchAdapter: EntryAdapter
    private lateinit var mMenu: Menu
    private var mSearchJob: Job = Job()
    private var mDatabaseJob: Job = Job()
    private var mInFileLauncher = false
    private val mDelay = 500L

    private val importFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            mInFileLauncher = false
            return@registerForActivityResult
        }
        dLog("importing $uri")
        mSearchJob.cancel()
        mDatabaseJob.cancel()
        mDatabaseJob.invokeOnCompletion {
            mDatabaseJob = MainScope().launch(Dispatchers.IO) {
                withContext(Dispatchers.Main) {
                    mAdapter.clear()
                    if (!openUserDict()) {
                        mInFileLauncher = false
                        throw CancellationException()
                    }
                    mMenu.setGroupEnabled(0, false)
                }
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
                mDatabaseJob.invokeOnCompletion {
                    mInFileLauncher = false
                    MainScope().launch(Dispatchers.Main) {
                        mMenu.setGroupEnabled(0, true)
                        updateListItems()
                    }
                }
            }
            mDatabaseJob.start()
        }
    }

    private val exportFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) {
            mInFileLauncher = false
            return@registerForActivityResult
        }
        dLog("exporting $uri")
        if (mDatabaseJob.isActive) {
            Toast.makeText(
                applicationContext,
                "読み込みが終了してからエクスポートします",
                Toast.LENGTH_SHORT
            ).show()
        }
        mDatabaseJob.invokeOnCompletion {
            mDatabaseJob = MainScope().launch(Dispatchers.IO) {
                withContext(Dispatchers.Main) {
                    if (!openUserDict()) {
                        mInFileLauncher = false
                        throw CancellationException()
                    }
                }
                var errorMessage = ""
                try {
                    contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use {
                        val browser = mStore!!.browse()
                        if (browser == null) withContext(Dispatchers.Main) {
                            throw (IOException("database browser is null"))
                        } else {
                            while (true) {
                                val result = browser.getNext() ?: break
                                it.write("${result.key} ${result.value}\n")
                            }
                        }
                    }
                } catch (e: Exception) {
                    errorMessage = e.message ?: "(null)"
                }
                closeUserDict()

                mDatabaseJob.invokeOnCompletion {
                    mInFileLauncher = false
                    MainScope().launch(Dispatchers.Main) {
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
            mDatabaseJob.start()
        }
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        startServiceCommand(SKKService.COMMAND_RELOAD_DICT) // commit させる
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

        MainScope().launch(Dispatchers.Default) {
            delay(mDelay.milliseconds) // 無駄に一瞬 emptyView が出るのを防ぐための遅延
            withContext(Dispatchers.Main) {
                binding.userDictToolList.emptyView = binding.EmptyListItem
            }
        }
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
                            dLog("remove $item from $mDictName")
                            MainScope().launch(Dispatchers.IO) {
                                try {
                                    if (openUserDict()) {
                                        mStore?.remove(item.key)
                                        withContext(Dispatchers.Main) {
                                            mEntryList.remove(item)
                                            mFoundList.remove(item)
                                            adapter.notifyDataSetChanged()
                                        }
                                    }
                                    closeUserDict()
                                } catch (e: Exception) {
                                    Log.e(
                                        "SKK",
                                        "UserDictTool error removing ${item.key}: ${e.message}"
                                    )
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
                val regex: Regex? by lazy {
                    try {
                        Regex(newText)
                    } catch (_: Exception) {
                        null
                    }
                }
                mFoundList.clear()
                mSearchJob.invokeOnCompletion {
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
                    mSearchJob.start()
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

            R.id.menu_user_dict_tool_clear -> {
                val cfDialog = ConfirmationDialogFragment.newInstance(
                    getString(R.string.message_tools_confirm_clear)
                )
                cfDialog.setListener(
                    object : ConfirmationDialogFragment.Listener {
                        override fun onPositiveClick() {
                            recreateUserDict(extract = false)
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
        closeUserDict()
        mAdapter.clear()
        startServiceCommand(SKKService.COMMAND_RELOAD_DICT)

        super.onPause()
    }

    private fun recreateUserDict(extract: Boolean) {
        mAdapter.clear()

        MainScope().launch(Dispatchers.IO) {
            deleteFile("$mDictName.mv")

            if (extract) {
                try {
                    unzipFile(resources.assets.open("$mDictName.zip"), filesDir)
                    dLog("$mDictName.zip extracted")
                } catch (e: IOException) {
                    Log.e("SKK", "I/O error in extracting $mDictName.zip: $e")
                }
                withContext(Dispatchers.Main) {
                    updateListItems()
                }
            } else {
                try {
                    mStore = openDB(
                        filesDir.absolutePath + "/" + mDictName,
                        getString(R.string.btree_name)
                    )
                    mStore?.commit()
                    mStore?.close()
                    mStore = null
                } catch (e: Exception) {
                    val store = mStore
                    mStore = null
                    store?.close()
                    throw RuntimeException(e)
                }
                dLog("New user dictionary created")
                withContext(Dispatchers.Main) {
                    mAdapter.clear() // どうせ空なので updateListItems() 不要
                }
            }
        }
    }

    private fun onFailToOpenUserDict() {
        mStore = null

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

    private suspend fun openUserDict(): Boolean {
        if (mStore != null) {
            Log.e("SKK", "openUserDict error: already opened")
            withContext(Dispatchers.Main) { onFailToOpenUserDict() }
            return false
        }

        // onPause() か finish() で reload するまで service / engine から使用できなくする
        startServiceCommand(SKKService.COMMAND_CLOSE_USER_DICT)

        val dictPath = filesDir.absolutePath + "/" + mDictName
        val btreeName = getString(R.string.btree_name)

        try {
            mStore = openDB(dictPath, btreeName, writable = true)
            dLog("UserDictTool: opened")
            return true
        } catch (e: Exception) {
            Log.e("SKK", "openUserDict error opening mStore: ${e.message}")
        }

        withContext(Dispatchers.Main) { onFailToOpenUserDict() }
        return false
    }

    private fun closeUserDict() {
        mStore?.let { store ->
            mStore = null
            try {
                store.commit()
            } catch (e: Exception) {
                Log.e("SKK", "closeUserDict commit error: ${e.message}")
            } finally {
                try {
                    store.close()
                } catch (e: Exception) {
                    startServiceCommand(SKKService.COMMAND_RELOAD_DICT)
                    throw RuntimeException("closeUserDict close error: ${e.message}")
                }
            }
        }
        dLog("UserDictTool: closed")
    }

    private fun updateListItems() {
        dLog("updateListItems")
        mSearchJob.cancel()
        mDatabaseJob.cancel()
        mDatabaseJob.invokeOnCompletion {
            mDatabaseJob = MainScope().launch(Dispatchers.IO) {
                withContext(Dispatchers.Main) {
                    mSearchView.isEnabled = false
                    mAdapter.clear()
                    if (!openUserDict()) {
                        throw CancellationException()
                    }
                }
                try {
                    val browser = mStore?.browse()
                    if (browser == null) {
                        Log.e("SKK", "UserDictTool updateListItems: browser=null")
                        withContext(Dispatchers.Main) { onFailToOpenUserDict() }
                        closeUserDict()
                        return@launch
                    }

                    val buffer = mutableListOf<SKKDictionaryTuple>()
                    val bufferSize = 1000
                    while (true) {
                        val result = browser.getNext() ?: break
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
                } catch (e: CancellationException) {
                    dLog("UserDictTool updateListItems: canceled")
                    closeUserDict()
                    throw e
                } catch (e: Exception) {
                    Log.e("SKK", "UserDictTool updateListItems: ${e.message}")
                    withContext(Dispatchers.Main) { onFailToOpenUserDict() }
                }
                closeUserDict()
                dLog("UserDictTool updateListItems: finished")
            }
            mDatabaseJob.start()
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
        items: List<SKKDictionaryTuple>
    ) : ArrayAdapter<SKKDictionaryTuple>(context, 0, items) {
        private val mLayoutInflater = LayoutInflater.from(context)

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val tv = convertView
                ?: mLayoutInflater.inflate(android.R.layout.simple_list_item_1, parent, false)

            val item = getItem(position) ?: SKKDictionaryTuple("", "")
            (tv as TextView).text =
                context.getString(R.string.item_tools_entry, item.key, item.value)

            return tv
        }
    }
}

package jp.deadend.noname.skk

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SearchView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import java.io.IOException
import java.nio.charset.CharacterCodingException
import jdbm.RecordManager
import jdbm.RecordManagerFactory
import jdbm.btree.BTree
import jdbm.helper.StringComparator
import jdbm.helper.Tuple
import jp.deadend.noname.dialog.ConfirmationDialogFragment
import jp.deadend.noname.dialog.SimpleMessageDialogFragment
import jp.deadend.noname.skk.SKKService.Companion.DICT_ASCII_ZIP_FILE
import jp.deadend.noname.skk.databinding.ActivityUserDicToolBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.zip.GZIPInputStream
import kotlin.math.floor
import kotlin.math.sqrt

class SKKUserDicTool : AppCompatActivity() {
    private lateinit var mDicName: String
    private var mRecMan: RecordManager? = null
    private var mBtree: BTree<String, String>? = null
    private var mEntryList = mutableListOf<Tuple<String, String>>()
    private var mFoundList = mutableListOf<Tuple<String, String>>()
    private lateinit var mAdapter: EntryAdapter
    private lateinit var mSearchView: SearchView
    private lateinit var mSearchAdapter: EntryAdapter
    private var mSearchJob: Job = Job()
    private var mInFileLauncher = false

    private val importFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            mInFileLauncher = false
            return@registerForActivityResult
        }
        dlog("importing $uri")
        mAdapter.clear()
        if (openUserDict()) MainScope().launch(Dispatchers.IO) {
            try {
                val name = getFileNameFromUri(this@SKKUserDicTool, uri)!!
                val isGzip = name.endsWith(".gz")
                val isWordList = name.endsWith("combined.gz")

                withContext(Dispatchers.Main) { mSearchView.queryHint = "文字セット識別中" }
                val charset = if (!isWordList &&
                    contentResolver.openInputStream(uri)!!.use { inputStream ->
                        val processedInputStream =
                            if (isGzip) GZIPInputStream(inputStream) else inputStream
                        isTextDicInEucJp(processedInputStream)
                    }) "EUC-JP"
                else "UTF-8"

                withContext(Dispatchers.Main) { mSearchView.queryHint = "インポート中" }
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val processedInputStream = if (isGzip) GZIPInputStream(inputStream) else inputStream
                    loadFromTextDic(processedInputStream, charset, isWordList, mRecMan!!, mBtree!!, false) {
                        if (floor(sqrt(it.toFloat())) % 50 == 0f) MainScope().launch { // 味わい進捗
                            mSearchView.queryHint = "インポート中 ${it}行目"
                        }
                    }
                }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    if (e is CharacterCodingException) {
                        SimpleMessageDialogFragment.newInstance(
                            getString(R.string.error_text_dic_coding)
                        ).show(supportFragmentManager, "dialog")
                    } else {
                        SimpleMessageDialogFragment.newInstance(
                            getString(
                                R.string.error_file_load,
                                getFileNameFromUri(this@SKKUserDicTool, uri)
                            )
                        ).show(supportFragmentManager, "dialog")
                    }
                }
            }
            closeUserDict()

            withContext(Dispatchers.Main) {
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
        dlog("exporting $uri")
        if (openUserDict()) MainScope().launch(Dispatchers.IO) {
            var errorMessage = ""
            try {
                contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use {
                    val browser = mBtree!!.browse()
                    if (browser == null) withContext(Dispatchers.Main) {
                        throw(IOException("database browser is null"))
                    } else {
                        val tuple = Tuple<String, String>()
                        while (browser.getNext(tuple)) {
                            it.write("${tuple.key} ${tuple.value}\n")
                        }
                    }
                }
            } catch (e: Exception) {
                errorMessage = e.message ?: "(null)"
                mBtree = null
                mRecMan = null
            }
            closeUserDict()

            withContext(Dispatchers.Main) {
                updateListItems()
                SimpleMessageDialogFragment.newInstance(
                    if (errorMessage.isEmpty()) {
                        getString(
                            R.string.message_written_to_external_storage,
                            getFileNameFromUri(this@SKKUserDicTool, uri)
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
        mDicName = intent.dataString!!
        val binding = ActivityUserDicToolBinding.inflate(layoutInflater)
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
        setSupportActionBar(binding.userDictoolToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.userDictoolList.emptyView = binding.EmptyListItem
        binding.userDictoolList.onItemClickListener =
                AdapterView.OnItemClickListener { parent, _, position, _ ->
            if (!mSearchView.isEnabled) { // 読み込み中
                return@OnItemClickListener
            }
            val dialog = ConfirmationDialogFragment.newInstance(getString(R.string.message_confirm_remove))
            dialog.setListener(
                object : ConfirmationDialogFragment.Listener {
                    override fun onPositiveClick() {
                        val adapter = parent.adapter as EntryAdapter
                        val item = adapter.getItem(position)!!
                        dlog("remove $item from $mDicName")
                        try {
                            if (openUserDict()) {
                                mBtree?.remove(item.key)
                            }
                            closeUserDict()
                        } catch (e: IOException) {
                            throw RuntimeException(e)
                        }

                        mEntryList.remove(item)
                        mFoundList.remove(item)
                        adapter.notifyDataSetChanged()
                    }
                    override fun onNegativeClick() {}
                })
            dialog.show(supportFragmentManager, "dialog")
        }

        mSearchView = binding.userDictoolSearch
        mSearchView.setOnQueryTextListener(object: SearchView.OnQueryTextListener {
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
                    binding.userDictoolList.adapter = mAdapter
                    return true
                }
                val regex: Regex? by lazy {
                    try { Regex(newText) }
                    catch (e: Exception) { null }
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
                            binding.userDictoolList.adapter = mSearchAdapter
                        }
                    }
                    mSearchJob.start()
                }
                return true
            }
        })

        mAdapter = EntryAdapter(this, mEntryList)
        binding.userDictoolList.adapter = mAdapter
        mSearchAdapter = EntryAdapter(this, mFoundList)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_user_dic_tool, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_user_dic_tool_import -> {
                mInFileLauncher = true
                importFileLauncher.launch(arrayOf("*/*"))
                return true
            }
            R.id.menu_user_dic_tool_export -> {
                mInFileLauncher = true
                exportFileLauncher.launch("$mDicName.txt")
                return true
            }
            R.id.menu_user_dic_tool_clear -> {
                val cfDialog = ConfirmationDialogFragment.newInstance(
                        getString(R.string.message_confirm_clear)
                )
                cfDialog.setListener(
                    object : ConfirmationDialogFragment.Listener {
                        override fun onPositiveClick() { recreateUserDic(extract = false) }
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
            when (val commandChar = mDicName.first()) {
                '*', '+' -> {
                    mDicName = mDicName.drop(1)
                    val cfDialog = ConfirmationDialogFragment.newInstance(
                        getString(R.string.message_confirm_clear)
                    )
                    cfDialog.setListener(
                        object : ConfirmationDialogFragment.Listener {
                            override fun onPositiveClick() {
                                recreateUserDic(commandChar == '+')
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

        super.onPause()
    }

    private fun recreateUserDic(extract: Boolean) {
        mAdapter.clear()

        MainScope().launch(Dispatchers.IO) {
            deleteFile("$mDicName.db")
            deleteFile("$mDicName.lg")

            if (extract) {
                try {
                    unzipFile(resources.assets.open(DICT_ASCII_ZIP_FILE), filesDir)
                    dlog("ASCII dictionary extracted")
                } catch (e: IOException) {
                    Log.e("SKK", "I/O error in extracting ASCII dictionary: $e")
                }
                withContext(Dispatchers.Main) {
                    updateListItems()
                }
            } else {
                try {
                    mRecMan = RecordManagerFactory.createRecordManager(filesDir.absolutePath + "/" + mDicName)
                    mBtree = BTree(mRecMan, StringComparator())
                    mRecMan!!.setNamedObject(getString(R.string.btree_name), mBtree!!.recordId)
                    mRecMan!!.commit()
                    mRecMan!!.close()
                    mBtree = null
                    mRecMan = null
                } catch (e: IOException) {
                    throw RuntimeException(e)
                }
                dlog("New user dictionary created")
                withContext(Dispatchers.Main) {
                    mAdapter.clear() // どうせ空なので updateListItems() 不要
                }
            }
        }
    }

    private fun onFailToOpenUserDict() {
        startServiceCommand(SKKService.COMMAND_UNLOCK_USERDIC)
        mBtree = null
        mRecMan = null

        ConfirmationDialogFragment.newInstance(getString(R.string.error_open_user_dic)).let {
            it.setListener(
                object : ConfirmationDialogFragment.Listener {
                    override fun onPositiveClick() { recreateUserDic(extract = false) }
                    override fun onNegativeClick() { finish() }
                })
            it.show(supportFragmentManager, "dialog")
        }
    }

    private fun openUserDict(): Boolean {
        if (mBtree != null) {
            Log.e("SKK", "openUserDict error: already opened")
            return true
        }

        startServiceCommand(SKKService.COMMAND_LOCK_USERDIC)

        val recID: Long?
        try {
            mRecMan = RecordManagerFactory.createRecordManager(
                    filesDir.absolutePath + "/" + mDicName
            )
            recID = mRecMan!!.getNamedObject(getString(R.string.btree_name))
        } catch (e: IOException) {
            Log.e("SKK", "openUserDict error creating mRecMan: ${e.message}")
            onFailToOpenUserDict()
            return false
        }

        if (recID == 0L) {
            Log.e("SKK", "openUserDict error: recID==0")
            onFailToOpenUserDict()
            return false
        } else {
            try {
                mBtree = BTree<String, String>().load(mRecMan, recID)
            } catch (e: IOException) {
                Log.e("SKK", "openUserDict error loading mBtree: ${e.message}")
                onFailToOpenUserDict()
                return false
            }
        }
        dlog("UserDicTool: opened")
        return true
    }

    private fun closeUserDict() {
        mBtree = null
        try {
            mRecMan?.commit()
            mRecMan?.close()
            mRecMan = null
        } catch (e: IOException) {
            Log.e("SKK", "closeUserDict error closing mRecMan: ${e.message}")
            startServiceCommand(SKKService.COMMAND_UNLOCK_USERDIC)
            throw RuntimeException(e)
        }
        startServiceCommand(SKKService.COMMAND_UNLOCK_USERDIC)
        dlog("UserDicTool: closed")
    }

    private fun updateListItems() {
        dlog("updateListItems")
        if (!openUserDict()) {
            mAdapter.clear()
            mInFileLauncher = false
            return
        }

        val tuple = Tuple<String, String>()

        mSearchView.isEnabled = false
        mAdapter.clear()
        MainScope().launch(Dispatchers.IO) {
            try {
                val browser = mBtree!!.browse()
                if (browser == null) {
                    Log.e("SKK", "UserDicTool updateListItems: browser=null")
                    withContext(Dispatchers.Main) { onFailToOpenUserDict() }
                    mInFileLauncher = false
                    return@launch
                }

                val buffer = mutableListOf<Tuple<String, String>>()
                val bufferSize = 1000
                while (browser.getNext(tuple)) {
                    buffer.add(Tuple(tuple.key, tuple.value))
                    if (buffer.size < bufferSize) continue
                    withContext(Dispatchers.Main) {
                        mAdapter.addAll(buffer)
                        mSearchView.queryHint = "読み込み中 ${100 * mAdapter.count / mBtree!!.size()}%"
                    }
                    buffer.clear()
                }
                withContext(Dispatchers.Main) {
                    mAdapter.addAll(buffer)
                    mSearchView.isEnabled = true
                    mSearchView.queryHint = ""
                }
            } catch (e: IOException) {
                Log.e("SKK", "UserDicTOol updateListItems: ${e.message}")
                withContext(Dispatchers.Main) { onFailToOpenUserDict() }
            }
            closeUserDict()
            mInFileLauncher = false
        }
    }

    private fun startServiceCommand(command: String) {
        if (SKKService.isRunning()) {
            val intent = Intent(this@SKKUserDicTool, SKKService::class.java)
            intent.putExtra(SKKService.KEY_COMMAND, command)
            startService(intent)
        }
    }

    private class EntryAdapter(
            context: Context,
            items: List<Tuple<String, String>>
    ) : ArrayAdapter<Tuple<String, String>>(context, 0, items) {
        private val mLayoutInflater = LayoutInflater.from(context)

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val tv = convertView
                    ?: mLayoutInflater.inflate(android.R.layout.simple_list_item_1, parent, false)

            val item = getItem(position) ?: Tuple("", "")
            (tv as TextView).text = item.key + "  " + item.value

            return tv
        }
    }
}

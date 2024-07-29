package jp.deadend.noname.skk

import android.content.Context
import android.content.Intent
import android.os.Bundle
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
import java.io.IOException
import java.nio.charset.CharacterCodingException
import jdbm.RecordManager
import jdbm.RecordManagerFactory
import jdbm.btree.BTree
import jdbm.helper.StringComparator
import jdbm.helper.Tuple
import jp.deadend.noname.dialog.ConfirmationDialogFragment
import jp.deadend.noname.dialog.SimpleMessageDialogFragment
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
    private lateinit var mRecMan: RecordManager
    private lateinit var mBtree: BTree<String, String>
    private var isOpened = false
    private var mEntryList = mutableListOf<Tuple<String, String>>()
    private var mFoundList = mutableListOf<Tuple<String, String>>()
    private lateinit var mAdapter: EntryAdapter
    private lateinit var mSearchView: SearchView
    private lateinit var mSearchAdapter: EntryAdapter
    private var mSearchJob: Job = Job()

    private val importFileLauncher = registerForActivityResult(
                                        ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        mAdapter.clear()
        openUserDict()
        MainScope().launch(Dispatchers.IO) {
            try {
                val name = getFileNameFromUri(this@SKKUserDicTool, uri)
                val isGzip = name!!.endsWith(".gz")

                withContext(Dispatchers.Main) { mSearchView.queryHint = "文字セット識別中" }
                val charset = if (contentResolver.openInputStream(uri)!!.use { inputStream ->
                    val processedInputStream = if (isGzip) GZIPInputStream(inputStream) else inputStream
                    isTextDicInEucJp(processedInputStream)
                }) "EUC-JP" else "UTF-8"

                withContext(Dispatchers.Main) { mSearchView.queryHint = "インポート中" }
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val processedInputStream = if (isGzip) GZIPInputStream(inputStream) else inputStream
                    loadFromTextDic(processedInputStream, charset, mRecMan, mBtree, false) {
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
            withContext(Dispatchers.Main) {
                updateListItems()
            }
        }
    }

    private val exportFileLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            openUserDict()
            try {
                contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use {
                    val browser = mBtree.browse()
                    if (browser == null) {
                        onFailToOpenUserDict()
                    } else {
                        val tuple = Tuple<String, String>()
                        while (browser.getNext(tuple)) {
                            it.write("${tuple.key} ${tuple.value}\n")
                        }
                    }
                }
            } catch (e: Exception) {
                SimpleMessageDialogFragment.newInstance(
                    getString(R.string.error_write_to_external_storage)
                ).show(supportFragmentManager, "dialog")
            }

            SimpleMessageDialogFragment.newInstance(
                getString(R.string.message_written_to_external_storage, getFileNameFromUri(this, uri))
            ).show(supportFragmentManager, "dialog")
        }
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mDicName = intent.dataString!!
        val binding = ActivityUserDicToolBinding.inflate(layoutInflater)
        setContentView(binding.root)
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
                            mBtree.remove(item.key)
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
                val searchJob = MainScope().launch(Dispatchers.Default) {
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
                mSearchJob.invokeOnCompletion {
                    mSearchJob = searchJob
                    mSearchJob.start()
                }
                return true
            }
        })

        if (SKKService.isRunning()) {
            val intent = Intent(this@SKKUserDicTool, SKKService::class.java)
            intent.putExtra(SKKService.KEY_COMMAND, SKKService.COMMAND_COMMIT_USERDIC)
            startService(intent)
        }

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
                closeUserDict()
                importFileLauncher.launch(arrayOf("*/*"))
                return true
            }
            R.id.menu_user_dic_tool_export -> {
                closeUserDict()
                exportFileLauncher.launch(mDicName + ".txt")
                return true
            }
            R.id.menu_user_dic_tool_clear -> {
                closeUserDict()
                val cfDialog = ConfirmationDialogFragment.newInstance(
                        getString(R.string.message_confirm_clear)
                )
                cfDialog.setListener(
                    object : ConfirmationDialogFragment.Listener {
                        override fun onPositiveClick() { recreateUserDic() }
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

        if (!isOpened) {
            if (mDicName.first() == '*') {
                mDicName = mDicName.drop(1)
                val cfDialog = ConfirmationDialogFragment.newInstance(
                    getString(R.string.message_confirm_clear)
                )
                cfDialog.setListener(
                    object : ConfirmationDialogFragment.Listener {
                        override fun onPositiveClick() { recreateUserDic() }
                        override fun onNegativeClick() {
                            openUserDict()
                            if (isOpened) updateListItems()
                        }
                    })
                cfDialog.show(supportFragmentManager, "dialog")
            } else {
                openUserDict()
                if (isOpened) updateListItems()
            }
        }
    }

    public override fun onPause() {
        closeUserDict()

        super.onPause()
    }

    private fun recreateUserDic() {
        closeUserDict()

        deleteFile("$mDicName.db")
        deleteFile("$mDicName.lg")

        try {
            mRecMan = RecordManagerFactory.createRecordManager(filesDir.absolutePath + "/" + mDicName)
            mBtree = BTree(mRecMan, StringComparator())
            mRecMan.setNamedObject(getString(R.string.btree_name), mBtree.recordId)
            mRecMan.commit()
        } catch (e: IOException) {
            throw RuntimeException(e)
        }

        dlog("New user dictionary created")
        isOpened = true
        mAdapter.clear()
    }

    private fun onFailToOpenUserDict() {
        val dialog = ConfirmationDialogFragment.newInstance(getString(R.string.error_open_user_dic))
        dialog.setListener(
            object : ConfirmationDialogFragment.Listener {
                override fun onPositiveClick() { recreateUserDic() }
                override fun onNegativeClick() { finish() }
            })
        dialog.show(supportFragmentManager, "dialog")

    }

    private fun openUserDict() {
        val recID: Long?
        try {
            mRecMan = RecordManagerFactory.createRecordManager(
                    filesDir.absolutePath + "/" + mDicName
            )
            recID = mRecMan.getNamedObject(getString(R.string.btree_name))
        } catch (e: IOException) {
            onFailToOpenUserDict()
            return
        }

        if (recID == 0L) {
            onFailToOpenUserDict()
            return
        } else {
            try {
                mBtree = BTree<String, String>().load(mRecMan, recID)
            } catch (e: IOException) {
                onFailToOpenUserDict()
                return
            }

            isOpened = true
        }
    }

    private fun closeUserDict() {
        if (!isOpened) return
        try {
            isOpened = false
            mRecMan.commit()
            mRecMan.close()
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
        mAdapter.clear()
    }

    private fun updateListItems() {
        if (!isOpened) {
            mAdapter.clear()
            return
        }

        val tuple = Tuple<String, String>()

        mSearchView.isEnabled = false
        mAdapter.clear()
        MainScope().launch(Dispatchers.IO) {
            try {
                val browser = mBtree.browse()
                if (browser == null) {
                    withContext(Dispatchers.Main) {
                        onFailToOpenUserDict()
                    }
                    return@launch
                }

                val buffer = mutableListOf<Tuple<String, String>>()
                val bufferSize = 1000
                while (isOpened && browser.getNext(tuple)) {
                    buffer.add(Tuple(tuple.key, tuple.value))
                    if (buffer.size < bufferSize) continue
                    withContext(Dispatchers.Main) {
                        mAdapter.addAll(buffer)
                        mSearchView.queryHint = "読み込み中 ${100 * mAdapter.count / mBtree.size()}%"
                    }
                    buffer.clear()
                }
                withContext(Dispatchers.Main) {
                    if (isOpened) mAdapter.addAll(buffer)
                    mSearchView.isEnabled = true
                    mSearchView.queryHint = ""
                }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    onFailToOpenUserDict()
                }
            }
        }.start()
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

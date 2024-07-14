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

class SKKUserDicTool : AppCompatActivity() {
    private lateinit var mDicName: String
    private lateinit var mRecMan: RecordManager
    private lateinit var mBtree: BTree<String, String>
    private var isOpened = false
    private var mEntryList = mutableListOf<Tuple<String, String>>()
    private var mFoundList = mutableListOf<Tuple<String, String>>()
    private lateinit var mAdapter: EntryAdapter
    private lateinit var mSearchAdapter: EntryAdapter
    private var mSearchJob: Job = Job()

    private val importFileLauncher = registerForActivityResult(
                                        ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            openUserDict()
            try {
                val name = getFileNameFromUri(this, uri)
                val isGzip = name!!.endsWith(".gz")
                val charset = if (contentResolver.openInputStream(uri)!!.use { inputStream ->
                    val processedInputStream = if (isGzip) GZIPInputStream(inputStream) else inputStream
                    isTextDicInEucJp(processedInputStream)
                }) "EUC-JP" else "UTF-8"
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val processedInputStream = if (isGzip) GZIPInputStream(inputStream) else inputStream
                    loadFromTextDic(processedInputStream, charset, mRecMan, mBtree, false)
                }
            } catch (e: IOException) {
                if (e is CharacterCodingException) {
                    SimpleMessageDialogFragment.newInstance(
                        getString(R.string.error_text_dic_coding)
                    ).show(supportFragmentManager, "dialog")
                } else {
                    SimpleMessageDialogFragment.newInstance(
                        getString(R.string.error_file_load, getFileNameFromUri(this, uri))
                    ).show(supportFragmentManager, "dialog")
                }
            }
            updateListItems()
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
            val dialog = ConfirmationDialogFragment.newInstance(getString(R.string.message_confirm_remove))
            dialog.setListener(
                object : ConfirmationDialogFragment.Listener {
                    override fun onPositiveClick() {
                        try {
                            mBtree.remove(mEntryList[position].key)
                        } catch (e: IOException) {
                            throw RuntimeException(e)
                        }

                        mEntryList.removeAt(position)
                        (parent.adapter as EntryAdapter).notifyDataSetChanged()
                    }
                    override fun onNegativeClick() {}
                })
            dialog.show(supportFragmentManager, "dialog")
        }

        binding.userDictoolSearch.setOnQueryTextListener(object: SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                binding.userDictoolSearch.clearFocus()
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
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

        val intent = Intent(this@SKKUserDicTool, SKKService::class.java)
        intent.putExtra(SKKService.KEY_COMMAND, SKKService.COMMAND_COMMIT_USERDIC)
        startService(intent)

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
                importFileLauncher.launch(arrayOf("*/*"))
                return true
            }
            R.id.menu_user_dic_tool_export -> {
                exportFileLauncher.launch(mDicName + ".txt")
                return true
            }
            R.id.menu_user_dic_tool_clear -> {
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

        if (!isOpened) openUserDict()
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
        mEntryList.clear()
        mAdapter.notifyDataSetChanged()
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

        updateListItems()
    }

    private fun closeUserDict() {
        if (!isOpened) return
        try {
            mRecMan.commit()
            mRecMan.close()
            isOpened = false
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
    }

    private fun updateListItems() {
        if (!isOpened) {
            mEntryList.clear()
            mAdapter.notifyDataSetChanged()
            return
        }

        val tuple = Tuple<String, String>()

        mEntryList.clear()
        try {
            val browser = mBtree.browse()
            if (browser == null) {
                onFailToOpenUserDict()
                return
            }

            while (browser.getNext(tuple)) {
                mEntryList.add(Tuple(tuple.key, tuple.value))
            }
        } catch (e: IOException) {
            onFailToOpenUserDict()
            return
        }

        mAdapter.notifyDataSetChanged()
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

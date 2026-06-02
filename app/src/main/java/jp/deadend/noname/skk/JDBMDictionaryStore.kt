package jp.deadend.noname.skk

import jdbm.RecordManager
import jdbm.RecordManagerFactory
import jdbm.btree.BTree
import jdbm.helper.StringComparator
import jdbm.helper.Tuple
import jdbm.helper.TupleBrowser
import java.io.IOException
import java.util.Properties

class JDBMDictionaryStore(
    private val recMan: RecordManager,
    private val btree: BTree<String, String>
) : SKKDictionaryStore {

    override fun find(key: String): String? = btree.find(key)

    override fun insert(key: String, value: String) {
        btree.insert(key, value, true)
    }

    override fun remove(key: String) {
        btree.remove(key)
    }

    override fun browse(): SKKDictionaryBrowser? {
        val browser = btree.browse() ?: return null
        return JDBMBrowser(browser)
    }

    override fun browse(key: String): SKKDictionaryBrowser? {
        val browser = btree.browse(key) ?: return null
        return JDBMBrowser(browser)
    }

    override fun commit() {
        recMan.commit()
    }

    override fun size(): Long = btree.size().toLong()

    override fun close() {
        recMan.close()
    }

    private class JDBMBrowser(private val browser: TupleBrowser<String, String>) :
        SKKDictionaryBrowser {
        private val tuple = Tuple<String, String>()
        override fun getNext(): SKKDictionaryTuple? {
            return if (browser.getNext(this.tuple)) {
                SKKDictionaryTuple(this.tuple.key as String, this.tuple.value as String)
            } else {
                null
            }
        }
    }

    companion object {
        @Throws(IOException::class)
        fun open(filePath: String, btreeName: String): JDBMDictionaryStore {
            val props = Properties().apply {
                setProperty(jdbm.RecordManagerOptions.DISABLE_TRANSACTIONS, "true")
            }
            val recMan = RecordManagerFactory.createRecordManager(filePath, props)
            val recID = recMan.getNamedObject(btreeName)
            val btree = if (recID == 0L) {
                val bt = BTree<String, String>(recMan, StringComparator())
                recMan.setNamedObject(btreeName, bt.recordId)
                recMan.commit()
                bt
            } else {
                BTree<String, String>().load(recMan, recID)
            }
            return JDBMDictionaryStore(recMan, btree)
        }
    }
}

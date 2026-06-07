package jp.deadend.noname.skk

import jdbm.RecordManager
import jdbm.RecordManagerFactory
import jdbm.btree.BTree
import jdbm.helper.StringComparator
import jdbm.helper.Tuple
import jdbm.helper.TupleBrowser
import java.io.IOException
import java.util.Properties

class JDBMStore(
    private val recMan: RecordManager,
    private val btree: BTree<String, String>
) : SKKStore {

    override fun get(key: String): String? = btree.find(key)

    override fun set(key: String, value: String) = this.apply { btree.insert(key, value, true) }

    override fun delete(key: String) = this.apply { btree.remove(key) }

    override fun cursor(): SKKStoreCursor? {
        val browser = btree.browse() ?: return null
        return JDBMCursor(browser)
    }

    override fun cursor(key: String): SKKStoreCursor? {
        val browser = btree.browse(key) ?: return null
        return JDBMCursor(browser)
    }

    override fun commit(): SKKStore = this.apply { recMan.commit() }

    override fun size(): Long = btree.size().toLong()

    override fun close() = recMan.close()

    private class JDBMCursor(private val browser: TupleBrowser<String, String>) :
        SKKStoreCursor {
        private val tuple = Tuple<String, String>()
        override fun next(): SKKStoreTuple? = if (browser.getNext(this.tuple))
            SKKStoreTuple(this.tuple.key as String, this.tuple.value as String)
        else null
    }

    companion object {
        @Throws(IOException::class)
        fun open(filePath: String, btreeName: String): JDBMStore {
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
            return JDBMStore(recMan, btree)
        }
    }
}

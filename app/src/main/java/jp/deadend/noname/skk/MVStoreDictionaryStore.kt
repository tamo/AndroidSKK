package jp.deadend.noname.skk

import org.h2.mvstore.MVMap
import org.h2.mvstore.MVStore

class MVStoreDictionaryStore(
    private val store: MVStore,
    private val map: MVMap<String, String>
) : SKKDictionaryStore {

    override fun find(key: String): String? = map[key]

    override fun insert(key: String, value: String) {
        map[key] = value
    }

    override fun remove(key: String) {
        map.remove(key)
    }

    override fun browse(): SKKDictionaryBrowser {
        return MVStoreBrowser(map, map.keyIterator(null))
    }

    override fun browse(key: String): SKKDictionaryBrowser {
        return MVStoreBrowser(map, map.keyIterator(key))
    }

    override fun commit() {
        store.commit()
    }

    override fun size(): Long = map.sizeAsLong()

    override fun close() {
        store.close()
    }

    private class MVStoreBrowser(
        private val map: MVMap<String, String>,
        private val cursor: Iterator<String>
    ) : SKKDictionaryBrowser {
        override fun getNext(): SKKDictionaryTuple? {
            if (cursor.hasNext()) {
                val nextKey = cursor.next()
                val nextValue = map[nextKey] ?: ""
                return SKKDictionaryTuple(nextKey, nextValue)
            }
            return null
        }
    }

    companion object {
        fun open(path: String, name: String, writable: Boolean = true): MVStoreDictionaryStore {
            System.setProperty("h2.fileLockMethod", "NO")
            val builder = MVStore.Builder().fileName(path) //.cacheSize(32)
            if (!writable) builder.readOnly()
            val store = builder.open()
            val map = store.openMap<String, String>(name)
            return MVStoreDictionaryStore(store, map)
        }
    }
}

package jp.deadend.noname.skk

import org.h2.mvstore.MVMap
import org.h2.mvstore.MVStore

class MVStoreStore(
    private val store: MVStore,
    private val map: MVMap<String, String>
) : SKKStore {
    override fun get(index: Long): SKKStoreTuple? {
        val key = map.getKey(index) ?: return null
        return SKKStoreTuple(key, map[key] ?: "")
    }

    override fun get(key: String): String? = map[key]

    override fun set(key: String, value: String) = this.apply { map[key] = value }

    override fun delete(key: String) = this.apply { map.remove(key) }

    override fun cursor(): SKKStoreCursor =
        MVStoreCursor(map, map.keyIterator(null))

    override fun cursor(key: String): SKKStoreCursor =
        MVStoreCursor(map, map.keyIterator(key))

    override fun commit() = this.apply { store.commit() }

    override fun size(): Long = map.sizeAsLong()

    override fun close() = store.close()

    private class MVStoreCursor(
        private val map: MVMap<String, String>,
        private val cursor: Iterator<String>
    ) : SKKStoreCursor {
        override fun next(): SKKStoreTuple? = if (cursor.hasNext()) {
            val nextKey = cursor.next()
            val nextValue = map[nextKey] ?: ""
            SKKStoreTuple(nextKey, nextValue)
        } else null
    }

    companion object {
        fun open(path: String, name: String, writable: Boolean = true): MVStoreStore {
            System.setProperty("h2.fileLockMethod", "NO")
            val builder = MVStore.Builder().fileName(path) //.cacheSize(32)
            if (!writable) builder.readOnly()
            val store = builder.open()
            val map = store.openMap<String, String>(name)
            return MVStoreStore(store, map)
        }
    }
}

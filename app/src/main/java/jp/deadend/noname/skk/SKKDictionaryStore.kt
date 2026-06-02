package jp.deadend.noname.skk

data class SKKDictionaryTuple(val key: String, val value: String)

interface SKKDictionaryBrowser {
    fun getNext(): SKKDictionaryTuple?
}

interface SKKDictionaryStore : AutoCloseable {
    fun find(key: String): String?
    fun insert(key: String, value: String)
    fun remove(key: String)
    fun browse(): SKKDictionaryBrowser?
    fun browse(key: String): SKKDictionaryBrowser?
    fun commit()
    fun size(): Long
}

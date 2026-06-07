package jp.deadend.noname.skk

data class SKKStoreTuple(val key: String, val value: String)

interface SKKStoreCursor {
    fun next(): SKKStoreTuple?
}

interface SKKStore : AutoCloseable {
    fun get(key: String): String?
    fun set(key: String, value: String): SKKStore
    fun delete(key: String): SKKStore
    fun cursor(): SKKStoreCursor?
    fun cursor(key: String): SKKStoreCursor?
    fun commit(): SKKStore
    fun size(): Long
}

package com.example.manager

class EngineSnapshotGate<T> {

    private val pending = LinkedHashMap<String, T>()

    @Volatile
    var isReady: Boolean = false
        private set

    private var snapshot: T? = null

    
    @Synchronized
    fun enqueue(key: String, value: T): Boolean {
        if (isReady) return false
        pending[key] = value
        return true
    }

    @Synchronized
    fun cancel(key: String) {
        pending.remove(key)
    }

    @Synchronized
    fun cancelAll() {
        pending.clear()
    }

    
    @Synchronized
    fun complete(snapshot: T): Map<String, T> {
        if (isReady) return emptyMap()
        this.snapshot = snapshot
        isReady = true
        val queued = pending.toMap()
        pending.clear()
        return queued
    }

    @Synchronized
    fun currentSnapshot(): T? = snapshot
}

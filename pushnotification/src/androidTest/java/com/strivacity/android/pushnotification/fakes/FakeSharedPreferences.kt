package com.strivacity.android.pushnotification.fakes

import android.content.SharedPreferences

/** An in-memory [SharedPreferences] fake — plain interfaces, no Android runtime needed. */
internal class FakeSharedPreferences(
    private val store: MutableMap<String, Any?> = mutableMapOf(),
    var commitResult: Boolean = true,
    var failGetAll: Boolean = false,
) : SharedPreferences {
    /** Bypasses the storage's own encoding, to inject an entry it cannot parse. */
    fun putRaw(
        key: String,
        value: String,
    ) {
        store[key] = value
    }

    val keys: Set<String> get() = store.keys.toSet()

    override fun getAll(): MutableMap<String, *> {
        check(!failGetAll) { "Simulated read failure" }
        return store
    }

    override fun getString(
        key: String?,
        defValue: String?,
    ): String? = store[key] as? String ?: defValue

    override fun getStringSet(
        key: String?,
        defValues: MutableSet<String>?,
    ): MutableSet<String>? = throw UnsupportedOperationException()

    override fun getInt(
        key: String?,
        defValue: Int,
    ): Int = throw UnsupportedOperationException()

    override fun getLong(
        key: String?,
        defValue: Long,
    ): Long = throw UnsupportedOperationException()

    override fun getFloat(
        key: String?,
        defValue: Float,
    ): Float = throw UnsupportedOperationException()

    override fun getBoolean(
        key: String?,
        defValue: Boolean,
    ): Boolean = throw UnsupportedOperationException()

    override fun contains(key: String?): Boolean = store.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private inner class FakeEditor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(
            key: String?,
            value: String?,
        ): SharedPreferences.Editor = apply { pending[requireNotNull(key)] = value }

        override fun putStringSet(
            key: String?,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor = throw UnsupportedOperationException()

        override fun putInt(
            key: String?,
            value: Int,
        ): SharedPreferences.Editor = throw UnsupportedOperationException()

        override fun putLong(
            key: String?,
            value: Long,
        ): SharedPreferences.Editor = throw UnsupportedOperationException()

        override fun putFloat(
            key: String?,
            value: Float,
        ): SharedPreferences.Editor = throw UnsupportedOperationException()

        override fun putBoolean(
            key: String?,
            value: Boolean,
        ): SharedPreferences.Editor = throw UnsupportedOperationException()

        override fun remove(key: String?): SharedPreferences.Editor = apply { removals += requireNotNull(key) }

        override fun clear(): SharedPreferences.Editor = apply { clearAll = true }

        override fun commit(): Boolean {
            if (!commitResult) return false
            if (clearAll) store.clear()
            removals.forEach { store.remove(it) }
            store.putAll(pending)
            return true
        }

        override fun apply() {
            commit()
        }
    }
}

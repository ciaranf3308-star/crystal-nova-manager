package io.crystalnova.manager.pegasus

import io.crystalnova.manager.data.KeyValueStore

/** In-memory KeyValueStore for JVM unit tests. */
class FakePrefs : KeyValueStore {
    private val strings = mutableMapOf<String, String>()

    override fun getString(key: String): String? = strings[key]
    override fun putString(key: String, value: String?) {
        if (value == null) strings.remove(key) else strings[key] = value
    }
    override fun remove(key: String) {
        strings.remove(key)
    }
}

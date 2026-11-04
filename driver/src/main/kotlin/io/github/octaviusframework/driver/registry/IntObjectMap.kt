package io.github.octaviusframework.driver.registry

/** A map from `Int` keys without boxing them, by open addressing. The key `0` marks an empty slot and is refused. */
internal class IntObjectMap<V> {
    private var keys: IntArray
    private var _values: Array<Any?>
    private var _size = 0
    private var threshold: Int

    constructor(capacity: Int = 128) {
        // Rounding up collapses to zero for a capacity of one or less, which would leave no slots.
        keys = IntArray((Integer.highestOneBit(capacity - 1) shl 1).coerceAtLeast(2))
        _values = arrayOfNulls(keys.size)
        threshold = (keys.size * 0.75).toInt()
    }
    
    constructor(other: IntObjectMap<V>) {
        keys = other.keys.copyOf()
        _values = other._values.copyOf()
        _size = other._size
        threshold = other.threshold
    }

    /**
     * Stores [value] under [key], growing the table past its load factor.
     *
     * @throws IllegalArgumentException if [key] is `0`.
     */
    fun put(key: Int, value: V) {
        require(key != 0) { "Key 0 is reserved" }
        if (_size >= threshold) rehash()

        val mask = keys.size - 1
        var idx = hash(key) and mask
        while (true) {
            val k = keys[idx]
            if (k == 0) {
                keys[idx] = key
                this._values[idx] = value
                _size++
                return
            }
            if (k == key) {
                this._values[idx] = value
                return
            }
            idx = (idx + 1) and mask
        }
    }

    @Suppress("UNCHECKED_CAST")
    /** The value under [key], or `null`; `null` for `0` too. */
    operator fun get(key: Int): V? {
        if (key == 0) return null
        val mask = keys.size - 1
        var idx = hash(key) and mask
        while (true) {
            val k = keys[idx]
            if (k == 0) return null
            if (k == key) return _values[idx] as V
            idx = (idx + 1) and mask
        }
    }

    /** Whether [key] has a value. */
    fun containsKey(key: Int): Boolean {
        return get(key) != null
    }

    private fun hash(key: Int): Int {
        var h = key
        h = h xor (h ushr 16)
        h = h * -2048144789
        h = h xor (h ushr 13)
        h = h * -1028477387
        h = h xor (h ushr 16)
        return h
    }

    private fun rehash() {
        val oldKeys = keys
        val oldValues = _values
        val newCapacity = oldKeys.size * 2
        keys = IntArray(newCapacity)
        _values = arrayOfNulls(newCapacity)
        _size = 0
        threshold = (newCapacity * 0.75).toInt()

        for (i in oldKeys.indices) {
            val k = oldKeys[i]
            if (k != 0) {
                @Suppress("UNCHECKED_CAST")
                put(k, oldValues[i] as V)
            }
        }
    }
    
    val size: Int get() = _size
    
    val entries: List<Map.Entry<Int, V>>
        get() {
            val list = mutableListOf<Map.Entry<Int, V>>()
            for (i in keys.indices) {
                val k = keys[i]
                if (k != 0) {
                    @Suppress("UNCHECKED_CAST")
                    list.add(java.util.AbstractMap.SimpleEntry(k, _values[i] as V))
                }
            }
            return list
        }
        
    val values: List<V>
        get() {
            val list = mutableListOf<V>()
            for (i in keys.indices) {
                if (keys[i] != 0) {
                    @Suppress("UNCHECKED_CAST")
                    list.add(this._values[i] as V)
                }
            }
            return list
        }
        
    /** Whether the map holds nothing. */
    fun isEmpty() = _size == 0
    /** Whether the map holds anything. */
    fun isNotEmpty() = _size > 0
    
    /** [put], as `map[key] = value`. */
    operator fun set(key: Int, value: V) {
        put(key, value)
    }

    /** Runs [action] for every entry, in table order, allocating nothing. */
    fun forEach(action: (key: Int, value: V) -> Unit) {
        val ks = keys
        val vs = _values
        for (i in ks.indices) {
            val k = ks[i]
            if (k != 0) {
                @Suppress("UNCHECKED_CAST")
                action(k, vs[i] as V)
            }
        }
    }
}

package io.github.octaviusframework.driver.registry

/**
 * The type system as something to read: dictionaries, containers and OID resolution, and nothing that writes.
 *
 * This is what a converter is handed. A conversion has no business registering a codec, a converter or a type -
 * it is running inside an execution that pinned its catalog before the first byte went out, so a registration
 * made here could not affect the conversion it was made from, and would reach every other session on the
 * database instead. Registration lives on [TypeManager], which is reached from a session rather than from a
 * conversion; there is no way back to one from here.
 *
 * What it reads depends on where it came from: [TypeManager.pinnedTo] fixes it to one catalog for the length of
 * an execution, while the one [TypeManager] reads through follows whatever its database's holder publishes.
 */
class TypeLookup internal constructor(
    private val catalogSource: () -> TypeCatalog,
    private val searchPathProvider: () -> List<String>
) {
    /**
     * Everything the driver knows about this database: dictionaries, converters and registrations, as one value.
     */
    val catalog: TypeCatalog get() = catalogSource()

    /**
     * The dictionary mapping PostgreSQL type names to their OIDs and vice versa.
     */
    val dictionary: TypeDictionary get() = catalog.dictionary

    /**
     * The dictionary maintaining [TypeCodec][io.github.octaviusframework.driver.codec.TypeCodec] implementations.
     */
    val codecs: CodecDictionary get() = catalog.codecs

    /**
     * Factory for creating container types (like composites).
     */
    val containers = ContainerFactory(this)

    /**
     * Resolves an OID for a given type name, considering the current search path.
     */
    fun resolveOid(
        typeName: String,
        schema: String = "",
        isArray: Boolean = false
    ): Int {
        return catalog.dictionary.resolveOid(typeName, schema, isArray, searchPathProvider())
    }
}

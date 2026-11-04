package io.github.octaviusframework.driver.registry

/**
 * The type system to read, which is what a converter is handed; registering is on [TypeManager]. The one a converter
 * gets reads the catalog its execution pinned; the one a session holds follows the current catalog.
 */
class TypeLookup internal constructor(
    private val catalogSource: () -> TypeCatalog,
    private val searchPathProvider: () -> List<String>
) {
    /** Everything the driver knows about this database: dictionaries, converters and registrations. */
    val catalog: TypeCatalog get() = catalogSource()

    /** Type names to OIDs and back. */
    val dictionary: TypeDictionary get() = catalog.dictionary

    /** The codecs for this database's types. */
    val codecs: CodecDictionary get() = catalog.codecs

    /** Builds composites, ranges and multiranges of a type named or given by OID. */
    val containers = ContainerFactory(this)

    /**
     * The OID of a type, by name through the search path or in [schema].
     *
     * @throws io.github.octaviusframework.driver.exception.TypeException `TYPE_NOT_FOUND` if [typeName] is not in
     *   [schema] or on the search path.
     */
    fun resolveOid(
        typeName: String,
        schema: String = "",
        isArray: Boolean = false
    ): Int {
        return catalog.dictionary.resolveOid(typeName, schema, isArray, searchPathProvider())
    }
}

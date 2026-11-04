package io.github.octaviusframework.driver.registry

/**
 * The dictionaries a container or domain codec resolves nested values through: those of the catalog it was built
 * for, so a value nested in a column reads against the same catalog as the column. Made before the codecs, which
 * live inside the [CodecDictionary] it names.
 */
internal class CodecScope(
    /** What the OIDs in the data mean. */
    val types: TypeDictionary
) {
    /** What decodes them. Set once by [bind], before the catalog is published, so it is read without a lock. */
    lateinit var codecs: CodecDictionary
        private set

    /**
     * Names the dictionary this scope resolves through.
     *
     * @throws IllegalStateException if it has already been named.
     */
    fun bind(codecs: CodecDictionary) {
        check(!this::codecs.isInitialized) { "This CodecScope is already bound to a codec dictionary" }
        this.codecs = codecs
    }
}

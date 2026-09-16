package io.github.octaviusframework.driver.registry

/**
 * The dictionaries a container or domain codec resolves through as it walks a value.
 *
 * A codec for a composite, array, range, multirange, record or domain has to look further codecs up - one per
 * attribute, per element, per bound - and the pair it looks them up in is the pair of the catalog it was built
 * for. It cannot simply hold that catalog: it lives inside the very [CodecDictionary] it would be pointing at.
 * This is where that knot is tied, and it is the only object that can be made *before* the codecs and still
 * name the dictionary they end up in - which is the whole reason it exists rather than each codec carrying a
 * field of its own to fill in afterwards.
 *
 * What it buys is a decode that does not move underneath a running statement: a value nested inside a column
 * is resolved against the same dictionaries as the column itself, however long the result takes to read and
 * whatever is registered in the meantime.
 */
internal class CodecScope(
    /** What the OIDs in the data mean. Known when the dictionary is being built. */
    val types: TypeDictionary
) {
    /**
     * What decodes them: the dictionary this scope was made for.
     *
     * Set once, by [bind], the moment that dictionary has been constructed and before the catalog carrying it
     * is published - so it is safe to read without synchronisation, the publication being a `@Volatile` write
     * that happens after. Nothing can repoint it afterwards, which is what keeps a codec reached through one
     * catalog from resolving through another's.
     */
    lateinit var codecs: CodecDictionary
        private set

    /**
     * Names the dictionary this scope resolves through.
     *
     * @throws IllegalStateException if it has already been named. A scope belongs to one dictionary for its
     *   whole life; a second call would mean a codec had been handed a pair it was not built for.
     */
    fun bind(codecs: CodecDictionary) {
        check(!this::codecs.isInitialized) { "This CodecScope is already bound to a codec dictionary" }
        this.codecs = codecs
    }
}

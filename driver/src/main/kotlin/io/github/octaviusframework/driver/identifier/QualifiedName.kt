package io.github.octaviusframework.driver.identifier

/**
 * A schema-qualified name.
 *
 * @property schema Empty where the name goes through the search path.
 * @property isArray Whether it names the array type over [name].
 */
data class QualifiedName(
    val schema: String,
    val name: String,
    val isArray: Boolean = false
) {
    override fun toString(): String {
        val base = if (schema.isBlank()) name else "$schema.$name"
        return if (isArray) "$base[]" else base
    }

    /** This name as SQL, with each part quoted by [quoteAsPgIdentifier]: `"imperium"."senator"[]`. */
    fun quote(): String {
        val quotedBase = if (schema.isBlank()) {
            name.quoteAsPgIdentifier()
        } else {
            "${schema.quoteAsPgIdentifier()}.${name.quoteAsPgIdentifier()}"
        }
        return if (isArray) "$quotedBase[]" else quotedBase
    }

    /** The array type over this name: `senator` becomes `senator[]`. */
    fun asArray(): QualifiedName = copy(isArray = true)
}

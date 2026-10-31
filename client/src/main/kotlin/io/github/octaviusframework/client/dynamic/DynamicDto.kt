package io.github.octaviusframework.client.dynamic

/**
 * The `dynamic_dto` composite in Kotlin: a discriminator and a JSON payload.
 *
 * A registered class is read as itself, so this is needed only for writing - under
 * [EXPLICIT_ONLY][DynamicWriteStrategy.EXPLICIT_ONLY], or a value whose class is registered as a composite too -
 * and for reading the payload as stored rather than as a class.
 *
 * Only [DynamicTypes.toDynamicDto] makes one, checking the name against the registry; the server never checks it.
 *
 * @property typeName The discriminator, as registered and as the database stores it.
 * @property dataPayload The object's state as JSON text, not checked to be JSON until PostgreSQL gets it.
 * `Json.parseToJsonElement` on [DynamicTypes.json] makes a tree of it.
 */
@ConsistentCopyVisibility
data class DynamicDto internal constructor(
    val typeName: String,
    val dataPayload: String
)

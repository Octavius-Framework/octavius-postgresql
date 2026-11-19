package io.github.octaviusframework.client.dynamic

/**
 * The `dynamic_dto` composite in Kotlin: a discriminator and a JSON payload, read as stored rather than as a class.
 *
 * Only reading makes one, and one written back goes as it is, its name not checked against the registry.
 *
 * @property typeName The discriminator, as the database stores it.
 * @property dataPayload The object's state as JSON text, not checked to be JSON until PostgreSQL gets it.
 * `Json.parseToJsonElement` on [DynamicTypes.json] makes a tree of it.
 */
@ConsistentCopyVisibility
data class DynamicDto internal constructor(
    val typeName: String,
    val dataPayload: String
)

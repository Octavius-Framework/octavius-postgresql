package io.github.octaviusframework.annotation

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialInfo

/**
 * Names the composite attribute or map key a property maps to, where the convention does not.
 *
 * A property is matched to an attribute by converting its name from `camelCase` to `snake_case`, which covers
 * a schema that names things the way SQL usually does. This is the override for the one that does not:
 * `@PgName("gov")` on `governorName` maps it to `gov` rather than to `governor_name`. It applies wherever that
 * matching happens - a composite registered with `registerAutoComposite`, object-to-map conversion, and the keys
 * of a payload written through [octaviusSnakeCaseJson][io.github.octaviusframework.serializer.octaviusSnakeCaseJson].
 *
 * It lives in a multiplatform module for a reason: the classes it goes on are the application's own, and those
 * are often shared with another platform. An annotation compiled only for the JVM could not be written on a
 * class in common code, which would leave exactly the schema that needs the override unable to use it.
 *
 * @property name The attribute or key name to use instead of the converted property name.
 */
@OptIn(ExperimentalSerializationApi::class)
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class PgName(val name: String)

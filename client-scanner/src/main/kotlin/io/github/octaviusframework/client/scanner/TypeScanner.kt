package io.github.octaviusframework.client.scanner

import io.github.classgraph.ClassGraph
import io.github.octaviusframework.annotation.DynamicallyMappable
import io.github.octaviusframework.annotation.PgCompositeType
import io.github.octaviusframework.annotation.PgEnumType
import io.github.octaviusframework.client.OctaviusClient
import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import io.github.octaviusframework.driver.exception.OctaviusException
import io.github.octaviusframework.identifier.CaseConvention
import io.github.octaviusframework.identifier.CaseConverter
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation

private val logger = KotlinLogging.logger {}

/**
 * A class the scan registered, and the name it was registered under: the type name for an enum or composite, the
 * discriminator for a `dynamic_dto` class.
 */
data class RegisteredType(val kClass: KClass<*>, val name: String) {
    override fun toString(): String = "${kClass.simpleName} -> $name"
}

/**
 * What a scan found and registered. Worth logging or checking at startup: a mistyped package registers nothing
 * and raises nothing.
 *
 * @property enums Enums registered as PostgreSQL `ENUM` types.
 * @property composites Data classes registered as PostgreSQL `COMPOSITE` types.
 * @property dynamicTypes Classes registered as `dynamic_dto` discriminators.
 * @property unresolved The enums and composites whose type the database does not have. They are registered all
 * the same and appear in [enums] or [composites] too: either the name is wrong, or the type is created later and
 * maps once `reloadTypes()` has run.
 */
data class ScanReport(
    val enums: List<RegisteredType>,
    val composites: List<RegisteredType>,
    val dynamicTypes: List<RegisteredType>,
    val unresolved: List<RegisteredType> = emptyList()
) {
    /** How many classes were registered in total. */
    val total: Int get() = enums.size + composites.size + dynamicTypes.size

    /** Whether the scan registered nothing at all. */
    fun isEmpty(): Boolean = total == 0

    override fun toString(): String = buildString {
        append("ScanReport(${enums.size} enums, ${composites.size} composites, ${dynamicTypes.size} dynamic types")
        if (unresolved.isNotEmpty()) append(", ${unresolved.size} against types the database does not have")
        append(")")
    }
}

/**
 * Finds the classes in [packages] and their subpackages annotated with [PgEnumType], [PgCompositeType] or
 * [DynamicallyMappable], and registers each with this client, as `typeManager.registerEnum`,
 * `registerAutoComposite` and `dynamicTypes.register` would.
 *
 * ```kotlin
 * val db = OctaviusClient.fromDataSource(dataSource)
 * db.dynamicTypes.install()
 * val found = db.registerAnnotatedTypes("com.roma.domain", "com.roma.dto")
 * logger.info { "Octavius registered $found" }
 * ```
 *
 * A name the database has no type for is registered all the same and reported in [ScanReport.unresolved].
 * Registration belongs to the database, so this runs once, at startup.
 *
 * @throws InvalidOperationException `INVALID_ARGUMENT` where no package was named, or where an annotation sits
 * on a class it cannot describe.
 */
fun OctaviusClient.registerAnnotatedTypes(vararg packages: String): ScanReport =
    registerAnnotatedTypes(packages.toList())

/**
 * As the `vararg` overload, looking for the classes in [classLoader]: for an application whose classes are not on
 * the loader that loaded this one, such as an OSGi container or a plugin host. `null` uses the default.
 */
fun OctaviusClient.registerAnnotatedTypes(
    packages: List<String>,
    classLoader: ClassLoader? = null
): ScanReport {
    if (packages.isEmpty()) {
        throw InvalidOperationException(
            InvalidOperationExceptionReason.INVALID_ARGUMENT,
            details = "Name at least one package to scan. Scanning the whole classpath is slow enough that " +
                "refusing is kinder than doing it."
        )
    }

    val found = findAnnotated(packages, classLoader)

    // Enums and composites register through the type manager, in one session; dynamic types go through the
    // client, after it.
    val enums = mutableListOf<RegisteredType>()
    val composites = mutableListOf<RegisteredType>()
    val unresolved = mutableListOf<RegisteredType>()

    execute {
        for (kClass in found.enums) {
            val annotation = kClass.findAnnotation<PgEnumType>()!!
            typeManager.registerEnum(
                kClass,
                annotation.name,
                annotation.schema,
                annotation.pgConvention,
                annotation.kotlinConvention
            )
            enums += RegisteredType(kClass, annotation.name.ifEmpty { derivedNameOf(kClass) })
        }
        for (kClass in found.composites) {
            val annotation = kClass.findAnnotation<PgCompositeType>()!!
            typeManager.registerAutoComposite(kClass, annotation.name, annotation.schema)
            composites += RegisteredType(kClass, annotation.name.ifEmpty { derivedNameOf(kClass) })
        }

        // Reported, not refused: a type created later is picked up by a reload, and the converters survive it.
        for (registered in enums + composites) {
            try {
                typeManager.resolveOid(registered.name, schemaOf(registered.kClass))
            } catch (_: OctaviusException) {
                unresolved += registered
            }
        }
    }

    val dynamicTypes = found.dynamic.map { kClass ->
        dynamicTypes.register(kClass)
        RegisteredType(kClass, kClass.findAnnotation<DynamicallyMappable>()!!.typeName)
    }

    val report = ScanReport(enums, composites, dynamicTypes, unresolved)
    if (report.isEmpty()) {
        logger.warn {
            "Scanned ${packages.joinToString(", ")} and found nothing annotated. Check the package names: " +
                "a scan that matches no class registers nothing and raises nothing."
        }
    } else {
        logger.info { "Registered $report from ${packages.joinToString(", ")}" }
        logger.debug { "enums=${enums}, composites=${composites}, dynamic=${dynamicTypes}" }
    }
    if (unresolved.isNotEmpty()) {
        logger.warn {
            "Registered against types the database does not have: ${unresolved.joinToString(", ")}. Either a " +
                "name is wrong, or the types are created after this pool was built - in which case " +
                "reloadTypes() once they exist. ScanReport.unresolved carries the same list."
        }
    }
    return report
}

/** The schema the class's annotation named, or empty for "resolve through the search path". */
private fun schemaOf(kClass: KClass<*>): String =
    kClass.findAnnotation<PgEnumType>()?.schema
        ?: kClass.findAnnotation<PgCompositeType>()?.schema
        ?: ""

/** The three lists a scan produces, before anything is registered. */
private class Annotated(
    val enums: List<KClass<*>>,
    val composites: List<KClass<*>>,
    val dynamic: List<KClass<*>>
)

private fun findAnnotated(packages: List<String>, classLoader: ClassLoader?): Annotated {
    val graph = ClassGraph()
        .enableAnnotationInfo()
        .acceptPackages(*packages.toTypedArray())
        .let { if (classLoader != null) it.overrideClassLoaders(classLoader) else it }

    return graph.scan().use { result ->
        fun classesWith(annotation: Class<*>): List<KClass<*>> =
            result.getClassesWithAnnotation(annotation.name).map { it.loadClass().kotlin }

        Annotated(
            enums = classesWith(PgEnumType::class.java),
            composites = classesWith(PgCompositeType::class.java),
            dynamic = classesWith(DynamicallyMappable::class.java)
        )
    }
}

/**
 * What the driver derives for a class registered without a name, so the report says what was really used. Uses
 * the driver's own converter, so the two cannot drift apart.
 */
private fun derivedNameOf(kClass: KClass<*>): String =
    CaseConverter.convert(kClass.simpleName!!, CaseConvention.PASCAL_CASE, CaseConvention.SNAKE_CASE_LOWER)

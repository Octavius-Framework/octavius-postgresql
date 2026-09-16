package io.github.octaviusframework.driver.converter.parameter.composite

import io.github.octaviusframework.driver.container.PgComposite
import io.github.octaviusframework.driver.util.reflection.ReflectionCache
import io.github.octaviusframework.driver.converter.parameter.mapper.ParameterConverter
import io.github.octaviusframework.driver.converter.parameter.mapper.SerializationContext
import io.github.octaviusframework.driver.identifier.QualifiedName
import io.github.octaviusframework.driver.type.PgType
import io.github.octaviusframework.driver.type.isKnownOid
import kotlin.reflect.KClass

internal object ReflectionCompositeParameterConverter : ParameterConverter<Any> {

    override val supportedClass: KClass<Any> = Any::class

    override fun canConvert(sourceClass: KClass<*>, expectedOid: Int, context: SerializationContext): Boolean {
        if (!sourceClass.isData) return false
        val registration = context.types.catalog.registeredComposites[sourceClass]
        return registration != null
    }

    override fun convert(source: Any, expectedOid: Int, context: SerializationContext): Any {
        val registration = context.types.catalog.registeredComposites[source::class] ?: error("Missing registration for composite")

        val type = if (expectedOid.isKnownOid) {
            context.types.dictionary.getPgType(expectedOid) as PgType.Composite
        } else {
            val qName = registration
            context.types.dictionary.getPgType(context.types.resolveOid(qName.name, qName.schema)) as PgType.Composite
        }

        @Suppress("UNCHECKED_CAST")
        val metadata = ReflectionCache.getOrCreateDataObjectMetadata(
            source::class as KClass<Any>
        )

        val propertiesByMapKey = metadata.constructorProperties.associateBy { it.keyName }

        val fields = type.attributes.map { (attrName, attributeOid) ->
            val meta = propertiesByMapKey[attrName]

            var value = if (meta != null) {
                meta.property.get(source)
            } else null

            if (value != null) {
                value = context.convert(value, attributeOid, attrName)
            }

            value
        }.toTypedArray()

        return PgComposite(type, fields)
    }

    override fun getDefaultTypeName(sourceClass: KClass<*>, context: SerializationContext): QualifiedName? {
        return context.types.catalog.registeredComposites[sourceClass]
    }
}


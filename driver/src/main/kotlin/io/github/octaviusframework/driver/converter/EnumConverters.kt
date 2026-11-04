package io.github.octaviusframework.driver.converter



import io.github.octaviusframework.driver.converter.parameter.mapper.ParameterConverter
import io.github.octaviusframework.driver.converter.parameter.mapper.SerializationContext
import io.github.octaviusframework.driver.converter.result.mapper.DeserializationContext
import io.github.octaviusframework.driver.converter.result.mapper.ResultConverter
import io.github.octaviusframework.driver.identifier.QualifiedName
import io.github.octaviusframework.driver.type.PgType
import io.github.octaviusframework.identifier.CaseConvention
import io.github.octaviusframework.identifier.CaseConverter
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * Writes a constant of [enumClass] as its label, the name converted from [kotlinConvention] to [pgConvention],
 * declared as [qualifiedName].
 */
internal class EnumParameterConverter<T : Enum<T>>(
    private val enumClass: KClass<T>,
    private val qualifiedName: QualifiedName,
    private val pgConvention: CaseConvention,
    private val kotlinConvention: CaseConvention
) : ParameterConverter<T> {

    private val enumToPg = enumClass.java.enumConstants.associateWith {
        CaseConverter.convert(it.name, kotlinConvention, pgConvention)
    }

    override val supportedClass: KClass<T> = enumClass

    override fun convert(source: T, expectedOid: Int, context: SerializationContext): Any {
        return enumToPg[source]!!
    }

    override fun getDefaultTypeName(sourceClass: KClass<*>, context: SerializationContext): QualifiedName {
        return qualifiedName
    }
}

/**
 * Reads a label of the enum type [qualifiedName] - in any schema where it names none - as a constant of
 * [enumClass]. A `text` column is left alone; asked for `Any`, it claims only what
 * [enumClassFor][io.github.octaviusframework.driver.registry.TypeCatalog.enumClassFor] reads as this enum.
 */
internal class EnumResultConverter<T : Enum<T>>(
    private val enumClass: KClass<T>,
    private val qualifiedName: QualifiedName,
    private val pgConvention: CaseConvention,
    private val kotlinConvention: CaseConvention
) : ResultConverter<String, T> {

    private val pgToEnum = enumClass.java.enumConstants.associateBy {
        CaseConverter.convert(it.name, kotlinConvention, pgConvention)
    }

    override val supportedSourceClass = String::class

    override fun canConvert(sourceClass: KClass<*>, expectedType: KType, sourceType: PgType, context: DeserializationContext): Boolean {
        if (sourceType !is PgType.Enum || sourceType.name != qualifiedName.name) return false
        if (qualifiedName.schema.isNotEmpty() && sourceType.schema != qualifiedName.schema) return false

        return when (expectedType.classifier) {
            enumClass -> true
            Any::class -> context.types.catalog.enumClassFor(sourceType) == enumClass
            else -> false
        }
    }

    override fun convert(source: String, expectedType: KType, sourceType: PgType, context: DeserializationContext): T {
        return pgToEnum[source]
            ?: throw IllegalArgumentException("Unknown enum value: $source for enum ${enumClass.simpleName}")
    }
}

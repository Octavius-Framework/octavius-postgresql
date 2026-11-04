package io.github.octaviusframework.driver.registry

import io.github.octaviusframework.driver.converter.parameter.array.CollectionArrayParameterConverter
import io.github.octaviusframework.driver.converter.parameter.array.PrimitiveArrayParameterConverter
import io.github.octaviusframework.driver.converter.parameter.composite.ReflectionCompositeParameterConverter
import io.github.octaviusframework.driver.converter.parameter.range.MultiRangeParameterConverter
import io.github.octaviusframework.driver.converter.parameter.range.RangeParameterConverter
import io.github.octaviusframework.driver.converter.parameter.standard.JsonElementParameterConverter
import io.github.octaviusframework.driver.converter.result.array.CollectionArrayConverter
import io.github.octaviusframework.driver.converter.result.array.ObjectArrayConverter
import io.github.octaviusframework.driver.converter.result.array.PrimitiveArrayConverter
import io.github.octaviusframework.driver.converter.result.composite.MapCompositeConverter
import io.github.octaviusframework.driver.converter.result.composite.ReflectionCompositeConverter
import io.github.octaviusframework.driver.converter.result.range.MultiRangeResultConverter
import io.github.octaviusframework.driver.converter.result.range.RangeResultConverter
import io.github.octaviusframework.driver.converter.result.record.MapRecordConverter
import io.github.octaviusframework.driver.converter.result.row.MapRowConverter
import io.github.octaviusframework.driver.converter.result.row.ReflectionRowConverter
import io.github.octaviusframework.driver.converter.result.standard.JsonElementConverter

/**
 * The catalog before a database has been read: the codecs the driver ships, their types, and the built-in converters.
 * The types describe the query that reads the real ones.
 */
internal fun builtinCatalog(): TypeCatalog {
    val codecs = CodecDictionary.createWithBuiltins()
    var catalog = TypeCatalog(
        dictionary = TypeDictionary.ofBuiltinCodecs(codecs),
        codecs = codecs,
        resultConverters = emptyMap(),
        anyResultConverters = emptyList(),
        parameterConverters = emptyList(),
        registeredComposites = emptyMap(),
        compositeClassByName = emptyMap(),
        registeredEnums = emptyMap(),
        enumClassByName = emptyMap(),
        attachments = emptyMap()
    )

    for (converter in listOf(
        PrimitiveArrayConverter,
        ObjectArrayConverter,
        CollectionArrayConverter,
        MapCompositeConverter,
        ReflectionCompositeConverter,
        ReflectionRowConverter,
        MapRowConverter,
        MapRecordConverter,
        JsonElementConverter,
        RangeResultConverter,
        MultiRangeResultConverter
    )) {
        catalog = catalog.withResultConverter(converter)
    }

    for (converter in listOf(
        PrimitiveArrayParameterConverter,
        CollectionArrayParameterConverter,
        ReflectionCompositeParameterConverter,
        JsonElementParameterConverter,
        RangeParameterConverter,
        MultiRangeParameterConverter
    )) {
        catalog = catalog.withParameterConverter(converter)
    }

    return catalog
}

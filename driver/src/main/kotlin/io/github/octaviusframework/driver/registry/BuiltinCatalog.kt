package io.github.octaviusframework.driver.registry

import io.github.octaviusframework.driver.converter.parameter.array.CollectionArrayParameterConverter
import io.github.octaviusframework.driver.converter.parameter.array.PrimitiveArrayParameterConverter
import io.github.octaviusframework.driver.converter.parameter.composite.ReflectionCompositeParameterConverter
import io.github.octaviusframework.driver.converter.parameter.range.MultiRangeParameterConverter
import io.github.octaviusframework.driver.converter.parameter.range.RangeParameterConverter
import io.github.octaviusframework.driver.converter.parameter.standard.JsonElementParameterConverter
import io.github.octaviusframework.driver.converter.result.array.CollectionArrayConverter
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
 * The catalog the driver starts out with, before a database has said anything: the codecs it ships, the types
 * those codecs name, and the converters that map them.
 *
 * The dictionary is derived from the codecs rather than read, because the query that reads the catalog is itself
 * a result whose columns have to be described, and until it returns these are the only types there are to
 * describe them with. Every entry is replaced the moment the real catalog arrives.
 *
 * This is the whole of what "built in" means, in one list, kept away from [CatalogHolder] - which has no
 * business knowing that a converter exists.
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
        registeredEnums = emptyMap()
    )

    for (converter in listOf(
        PrimitiveArrayConverter,
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

package com.example.demo

import com.example.demo.domain.Address
import com.example.demo.domain.UserProfile
import com.example.demo.domain.UserRole
import io.github.octaviusframework.driver.converter.parameter.mapper.ParameterConverter
import io.github.octaviusframework.driver.converter.parameter.mapper.SerializationContext
import io.github.octaviusframework.driver.converter.result.mapper.DeserializationContext
import io.github.octaviusframework.driver.converter.result.mapper.ResultConverter
import io.github.octaviusframework.driver.identifier.QualifiedName
import io.github.octaviusframework.driver.spring.OctaviusTemplate
import io.github.octaviusframework.driver.type.PgType
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * Writes a `Map` out as `jsonb`, so `UserProfile.settings` can be a plain `Map` in the class the
 * controller returns rather than a `JsonElement`.
 */
class MapParameterConverter(private val objectMapper: ObjectMapper) : ParameterConverter<Map<*, *>> {
    override val supportedClass = Map::class

    override fun convert(source: Map<*, *>, expectedOid: Int, context: SerializationContext): Any {
        return objectMapper.writeValueAsString(source)
    }

    override fun getDefaultTypeName(sourceClass: KClass<*>, context: SerializationContext): QualifiedName =
        QualifiedName("pg_catalog", "jsonb")
}

/** The same mapping back: a `json` or `jsonb` value read into a property declared as a `Map`. */
class MapResultConverter(private val objectMapper: ObjectMapper) : ResultConverter<String, Map<*, *>> {
    override val supportedSourceClass = String::class

    override fun canConvert(
        sourceClass: KClass<*>,
        expectedType: KType,
        sourceType: PgType,
        context: DeserializationContext
    ): Boolean {
        return (expectedType.classifier == Map::class) && (sourceType.name == "json" || sourceType.name == "jsonb")
    }

    override fun convert(
        source: String,
        expectedType: KType,
        sourceType: PgType,
        context: DeserializationContext
    ): Map<*, *> {
        return objectMapper.readValue(source, Map::class.java)
    }
}

/**
 * Everything this example needs in the database, and the registrations that let the classes in
 * `domain` stand for it.
 *
 * It is a bean of its own rather than a lambda inside the runner so that the smoke test can install
 * the same schema the application does, without depending on whether a `CommandLineRunner` fires
 * under `@SpringBootTest`. [install] is idempotent - the DDL drops what it creates and the
 * registrations are keyed by name - so calling it twice costs a round trip and changes nothing.
 *
 * > **It drops what it creates.** `users` and the three types go before they are made, in whatever
 * > database `spring.datasource.url` names - which defaults to `postgres`. Point it somewhere you do
 * > not mind losing.
 */
@Component
class DemoSchema(private val octaviusTemplate: OctaviusTemplate) {

    // A local mapper for jsonb only, leaving Spring's own untouched
    private val dbObjectMapper: ObjectMapper = jacksonObjectMapper()

    fun install() {
        octaviusTemplate.execute {
            // Map <-> jsonb, which is ours to say: the driver maps jsonb to JsonElement, not to a Map
            typeManager.registerParameterConverter(MapParameterConverter(dbObjectMapper))
            typeManager.registerResultConverter(MapResultConverter(dbObjectMapper))

            // These name their types rather than resolve them, which is why they can precede the DDL
            typeManager.registerEnum<UserRole>()

            // Reflection over the primary constructor: PascalCase class and camelCase properties are
            // read as snake_case, so Address.buildingNumber finds building_number
            typeManager.registerAutoComposite<Address>()
            typeManager.registerAutoComposite<UserProfile>()

            createNativeQuery(
                """
                DROP TABLE IF EXISTS users;
                DROP TYPE IF EXISTS address CASCADE;
                DROP TYPE IF EXISTS user_profile CASCADE;
                DROP TYPE IF EXISTS user_role CASCADE;

                CREATE TYPE user_role AS ENUM ('ADMIN', 'USER');

                CREATE TYPE address AS (
                    city text,
                    street text,
                    building_number int
                );

                -- jsonb for the map property, per MapParameterConverter above
                CREATE TYPE user_profile AS (
                    age int,
                    nickname text,
                    settings jsonb
                );

                CREATE TABLE users (
                    id uuid primary key default gen_random_uuid(),
                    name varchar not null unique,
                    role user_role not null,
                    primary_address address not null,
                    shipping_addresses address[] not null,
                    profile user_profile not null
                );
            """
            ).execute()

            // The DDL above created types under OIDs the catalog has never seen, so it is read again
            reloadTypes()
        }
    }
}

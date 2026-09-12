package com.example.demo

import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.core.convert.converter.Converter
import kotlin.uuid.Uuid

@SpringBootApplication
class DemoApplication {

    /**
     * Brings the database up to what the controller expects before the first request.
     *
     * The work is [DemoSchema]'s - see it for what gets created, what gets dropped, and why the type
     * registrations can run before the DDL that creates the types.
     */
    @Bean
    fun initDatabase(schema: DemoSchema): CommandLineRunner = CommandLineRunner { schema.install() }

    /**
     * Teaches Spring MVC the class a `uuid` column arrives as.
     *
     * The driver reads `uuid` into `kotlin.uuid.Uuid`, so that is what the classes in `domain` carry
     * and what `GET /users/{id}` declares. Spring converts a path variable into `java.util.UUID` out
     * of the box and knows nothing about Kotlin's, so without this the request never reaches the
     * method - it fails binding with `MethodArgumentConversionNotSupportedException`. Jackson needed
     * the same thing said to it, which is what the serializers in `Models.kt` are for.
     */
    @Bean
    fun stringToUuidConverter(): Converter<String, Uuid> = Converter { source -> Uuid.parse(source) }
}

fun main(args: Array<String>) {
    runApplication<DemoApplication>(*args)
}

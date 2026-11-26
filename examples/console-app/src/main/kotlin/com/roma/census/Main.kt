package com.roma.census

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.octaviusframework.client.OctaviusClient
import io.github.octaviusframework.client.scanner.registerAnnotatedTypes
import io.github.octaviusframework.migrations.MigratorConfig
import io.github.octaviusframework.migrations.OctaviusMigrator
import javax.sql.DataSource
import kotlin.system.exitProcess

private val USAGE = """
    Usage: census <command> [options]

      enroll --nomen N --tribe T --ordo O --city C --district D --assessment HS [--son N]...
      list [--tribe T] [--ordo O] [--city C] [--limit N]
      show <id>
      transfer <id> --tribe T
      demote <id> --ordo O --reason R

    Orders: ${Ordo.entries.joinToString()}
    Database: CENSUS_DATABASE_URL, CENSUS_DATABASE_USER, CENSUS_DATABASE_PASSWORD
""".trimIndent()

fun main(args: Array<String>) {
    // kotlin-logging, which Octavius logs through, otherwise prints a line about itself to stdout
    System.setProperty("kotlin-logging.logStartupMessage", "false")

    val command = try {
        Command.parse(args.toList())
    } catch (e: UsageException) {
        System.err.println(e.message)
        System.err.println(USAGE)
        exitProcess(2)
    }

    val config = HikariConfig().apply {
        jdbcUrl = env("CENSUS_DATABASE_URL", "jdbc:octavius://localhost:5432/census")
        username = env("CENSUS_DATABASE_USER", "postgres")
        password = env("CENSUS_DATABASE_PASSWORD", "1234")
        maximumPoolSize = 2
    }

    val status = HikariDataSource(config).use { dataSource ->
        OctaviusClient.fromDataSource(dataSource).use { db ->
            prepare(dataSource, db)
            try {
                command.run(db)
                0
            } catch (e: Refused) {
                System.err.println(e.message)
                1
            }
        }
    }
    exitProcess(status)
}

/** Brings the schema up to date, then maps the classes in this package onto the types it now has. */
private fun prepare(dataSource: DataSource, db: OctaviusClient) {
    val migrated = OctaviusMigrator(
        dataSource,
        MigratorConfig(codePackages = listOf("com.roma.census.migrations"))
    ).migrate()

    // The catalogue was read on the first connection, before the migrations created their types
    if (!migrated.isEmpty()) db.execute { reloadTypes() }

    val found = db.registerAnnotatedTypes("com.roma.census")
    check(found.unresolved.isEmpty()) { "No type in the database for ${found.unresolved}" }
}

private fun env(name: String, default: String): String = System.getenv(name) ?: default

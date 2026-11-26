package com.roma.census

import io.github.octaviusframework.client.OctaviusClient

/** A command the rolls refuse: no such citizen, or a change that is not one. Exits with status 1. */
class Refused(message: String) : RuntimeException(message)

/** One invocation of `census`. */
sealed interface Command {
    fun run(db: OctaviusClient)

    companion object {
        fun parse(args: List<String>): Command {
            val arguments = Arguments(args.drop(1))
            return when (args.firstOrNull()) {
                "enroll" -> Enroll.parse(arguments)
                "list" -> ListCitizens.parse(arguments)
                "show" -> Show.parse(arguments)
                "transfer" -> Transfer.parse(arguments)
                "demote" -> Demote.parse(arguments)
                null -> throw UsageException("Name a command")
                else -> throw UsageException("No command ${args.first()}")
            }
        }
    }
}

package com.roma.census

/** A command line that does not say what to do. Printed with the usage. */
class UsageException(message: String) : RuntimeException(message)

/** What follows the command's name: positional values, and `--name value` options that may repeat. */
class Arguments(args: List<String>) {
    private val positional = mutableListOf<String>()
    private val options = mutableMapOf<String, MutableList<String>>()

    init {
        val rest = args.iterator()
        while (rest.hasNext()) {
            val arg = rest.next()
            if (arg.startsWith("--")) {
                if (!rest.hasNext()) throw UsageException("$arg needs a value")
                options.getOrPut(arg.removePrefix("--")) { mutableListOf() } += rest.next()
            } else {
                positional += arg
            }
        }
    }

    fun id(): Int = positional.singleOrNull()?.toIntOrNull()
        ?: throw UsageException("Name one citizen by number")

    fun optional(name: String): String? = options[name]?.last()

    fun required(name: String): String = optional(name) ?: throw UsageException("--$name is required")

    fun all(name: String): List<String> = options[name].orEmpty()

    fun long(name: String): Long? = optional(name)?.let {
        it.toLongOrNull() ?: throw UsageException("--$name takes a number")
    }

    fun ordo(name: String): Ordo? = optional(name)?.let { value ->
        Ordo.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: throw UsageException("--$name is one of ${Ordo.entries.joinToString()}")
    }
}

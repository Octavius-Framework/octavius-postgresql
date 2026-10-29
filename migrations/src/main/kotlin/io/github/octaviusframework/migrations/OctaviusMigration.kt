package io.github.octaviusframework.migrations

import io.github.octaviusframework.driver.session.OctaviusSessionOperations

/**
 * A migration written in Kotlin rather than in `.sql`.
 *
 * **Its version and description come from the class name**, exactly as a file's do: `V2__Add_indexes` is
 * version 2, `V2_1__Add_indexes` is version 2.1 - a class name cannot hold a `.`, so `_` separates the parts
 * there - and `R__Rebuild_views` is repeatable. Nothing on this interface says which migration it is, and
 * that is the point: the scan reads the name and never constructs the class, so a migration that ran months
 * ago runs none of your code at startup.
 *
 * The class needs a constructor that takes no arguments, and is built once, immediately before it runs, then
 * dropped. It is not a bean and holding one would buy nothing.
 *
 * ```kotlin
 * class V3__Backfill_provinces : OctaviusMigration {
 *     override fun migrate(session: OctaviusSessionOperations) {
 *         session.createNativeQuery("UPDATE provinces SET tribute = 0 WHERE tribute IS NULL").executeUpdate()
 *     }
 * }
 * ```
 */
interface OctaviusMigration {

    /**
     * Runs the migration.
     *
     * Under [transactional] - the default - a transaction is already open and the row recording this
     * migration goes into it too, so throwing from here leaves the database exactly as it was. Without it,
     * every statement stands on its own and what has already run has already run.
     *
     * @param session The session the run holds, in a transaction or not according to [transactional].
     */
    fun migrate(session: OctaviusSessionOperations)

    /**
     * Whether to wrap this migration in a transaction. Read off the instance immediately before it runs.
     *
     * Turn it off for what PostgreSQL refuses inside a transaction block - `CREATE INDEX CONCURRENTLY`,
     * `VACUUM`, `ALTER SYSTEM`. The cost is that a failure halfway leaves half of it applied, and the run
     * refuses to go on until somebody has looked.
     */
    val transactional: Boolean get() = true

    /**
     * A number that changes when this migration changes, or `null` - the default - for "do not check".
     *
     * There is no honest checksum to derive for a class. Hashing the bytecode reports a change when a blank
     * line moves or the compiler is upgraded, and hashing the class name detects only a rename, which the
     * version already catches. So nothing is stored unless you put something here, and validation skips what
     * it has no checksum for.
     *
     * On a repeatable migration `null` means it runs on **every** run, there being no way to tell that it
     * changed - which is no great loss, a repeatable migration having to be idempotent regardless.
     */
    val checksum: Long? get() = null
}

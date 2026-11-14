package io.github.octaviusframework.migrations.history

import io.github.octaviusframework.migrations.MigrationException
import io.github.octaviusframework.migrations.MigrationExceptionReason
import io.github.octaviusframework.migrations.MigrationTestDatabase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MigrationLockTest {

    private fun lock(table: String = "test_history", timeout: kotlin.time.Duration = 5.seconds) =
        MigrationLock(MigrationTestDatabase.SCHEMA, table, timeout)

    companion object {
        @JvmStatic
        @AfterAll
        fun cleanUp() = MigrationTestDatabase.drop()
    }

    /** How many backends hold this database's advisory locks right now. */
    private fun advisoryLocksHeld(): Long =
        MigrationTestDatabase.session().use { session ->
            session.createNativeQuery("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory'")
                .fetchFieldStrict()
        }

    @Test
    fun `the block runs under the lock and the lock is given back`() {
        val before = advisoryLocksHeld()

        val held = MigrationTestDatabase.session().use { session ->
            lock().withLock(session) { advisoryLocksHeld() }
        }

        assertEquals(before + 1, held, "the lock should be held while the block runs")
        assertEquals(before, advisoryLocksHeld(), "and given back after it")
    }

    @Test
    fun `the lock is given back even when the block throws`() {
        val before = advisoryLocksHeld()

        assertThrows<IllegalStateException> {
            MigrationTestDatabase.session().use { session ->
                lock().withLock(session) { error("the migration failed") }
            }
        }

        assertEquals(before, advisoryLocksHeld(), "a failed run must not leave the lock behind")
    }

    @Test
    fun `a second migrator waits for the first, and then gets in`() {
        val firstHasIt = CountDownLatch(1)
        val held = 500L

        val holder = thread {
            MigrationTestDatabase.session().use { session ->
                lock().withLock(session) {
                    firstHasIt.countDown()
                    Thread.sleep(held)
                }
            }
        }
        assertTrue(firstHasIt.await(10, TimeUnit.SECONDS), "the first migrator never took the lock")

        // How long the second one spent getting in is the whole assertion. Without it this test passes
        // against a lock that does nothing at all: a migrator that never really waited also gets through.
        var waitedMs = -1L
        val waiter = thread {
            MigrationTestDatabase.session().use { session ->
                val startedAt = System.nanoTime()
                lock().withLock(session) { waitedMs = (System.nanoTime() - startedAt) / 1_000_000 }
            }
        }

        holder.join()
        waiter.join()

        assertTrue(waitedMs >= 0, "the second migrator never got in")
        assertTrue(
            waitedMs >= held / 2,
            "the second migrator got in after ${waitedMs}ms, so it did not wait for the first one"
        )
    }

    @Test
    fun `waiting has an end`() {
        val firstHasIt = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)

        val holder = thread {
            MigrationTestDatabase.session().use { session ->
                lock().withLock(session) {
                    firstHasIt.countDown()
                    releaseFirst.await(10, TimeUnit.SECONDS)
                }
            }
        }
        assertTrue(firstHasIt.await(10, TimeUnit.SECONDS))

        try {
            val e = assertThrows<MigrationException> {
                MigrationTestDatabase.session().use { session ->
                    lock(timeout = 200.milliseconds).withLock(session) { error("never reached") }
                }
            }
            assertEquals(MigrationExceptionReason.LOCK_NOT_ACQUIRED, e.reason)
        } finally {
            releaseFirst.countDown()
            holder.join()
        }
    }

    @Test
    fun `two history tables are two locks`() {
        // A single hard-coded key would make two applications sharing a database queue behind each other for
        // no reason. The key comes from the history table's name so that they do not.
        val firstHasIt = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)

        val holder = thread {
            MigrationTestDatabase.session().use { session ->
                lock(table = "history_of_one").withLock(session) {
                    firstHasIt.countDown()
                    releaseFirst.await(10, TimeUnit.SECONDS)
                }
            }
        }
        assertTrue(firstHasIt.await(10, TimeUnit.SECONDS))

        try {
            MigrationTestDatabase.session().use { session ->
                // A short timeout, so that this fails rather than hangs if the keys ever collapse into one.
                lock(table = "history_of_another", timeout = 500.milliseconds).withLock(session) { }
            }
        } finally {
            releaseFirst.countDown()
            holder.join()
        }
    }

    @Test
    fun `lock_timeout is put back the way it was found`() {
        MigrationTestDatabase.session().use { session ->
            session.createNativeQuery("SET lock_timeout = '7s'").execute()

            lock(timeout = 200.milliseconds).withLock(session) { }

            val after = session.createNativeQuery("SHOW lock_timeout").fetchFieldStrict<String>()
            assertEquals("7s", after, "the session's own setting should have survived the run")
        }
    }
}

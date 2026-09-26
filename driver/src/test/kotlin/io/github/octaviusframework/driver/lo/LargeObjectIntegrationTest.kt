package io.github.octaviusframework.driver.lo

import io.github.octaviusframework.driver.session.OctaviusSession
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class LargeObjectIntegrationTest : AbstractIntegrationTest() {

    private lateinit var session: OctaviusSession

    @BeforeEach
    fun setup() {
        session = openSession()
    }

    @AfterEach
    fun teardown() {
        try {
            session.close()
        } catch (e: Exception) {}
    }

    @Test
    fun `test large object basic operations`() {
        session.transaction.required {
            val loManager = session.largeObjects
            
            // Create LO
            val oid = loManager.create()
            // PostgreSQL OIDs can be negative in Kotlin/Java due to signed 32-bit integers if they are > 2.14B, 
            // but we can at least assert it's not 0, which is an invalid OID
            assertNotEquals(0, oid, "OID should not be 0")

            // Write to LO
            val obj = loManager.open(oid, LargeObjectMode.READ_WRITE)
            val data = "Senatus Populusque Romanus".toByteArray(Charsets.UTF_8)
            obj.write(data, 0, data.size)

            // Seek to beginning
            obj.seek(0, SeekWhence.SET)

            // Read from LO
            val buffer = ByteArray(50)
            val bytesRead = obj.read(buffer, 0, buffer.size)
            assertEquals(data.size, bytesRead)
            
            val readString = String(buffer, 0, bytesRead, Charsets.UTF_8)
            assertEquals("Senatus Populusque Romanus", readString)

            // Tell position
            val position = obj.tell()
            assertEquals(data.size.toLong(), position)

            // Close
            obj.close()
            
            // Delete LO
            loManager.unlink(oid)
        }
    }

    @Test
    fun `test large object streams`() {
        session.transaction.required {
            val loManager = session.largeObjects
            val oid = loManager.create()

            val obj = loManager.open(oid, LargeObjectMode.READ_WRITE)
            val outputStream = obj.outputStream()
            val text = "Res Gestae Divi Augusti, streamed a tablet at a time"
            outputStream.write(text.toByteArray(Charsets.UTF_8))
            
            obj.seek(0, SeekWhence.SET)

            val inputStream = obj.inputStream()
            val readData = inputStream.readBytes()
            assertEquals(text, String(readData, Charsets.UTF_8))

            obj.close()
            loManager.unlink(oid)
        }
    }

    @Test
    fun `test read length only`() {
        session.transaction.required {
            val loManager = session.largeObjects
            val oid = loManager.create()

            val obj = loManager.open(oid, LargeObjectMode.READ_WRITE)
            val data = "Carthago delenda est".toByteArray(Charsets.UTF_8)
            obj.write(data)

            obj.seek(0, SeekWhence.SET)
            
            val readBytes = obj.read(data.size)
            assertEquals(String(data, Charsets.UTF_8), String(readBytes, Charsets.UTF_8))

            obj.close()
            loManager.unlink(oid)
        }
    }
}

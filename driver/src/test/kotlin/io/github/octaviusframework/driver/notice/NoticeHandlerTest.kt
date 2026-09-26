package io.github.octaviusframework.driver.notice

import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

object TestNoticeHandler : NoticeHandler {
    var lastNotice: PgNotice? = null
    override fun handleNotice(notice: PgNotice) {
        lastNotice = notice
    }
}

class NoticeHandlerTest : AbstractIntegrationTest() {

    @Test
    fun testNoticeHandlerReceivesNotice() = runBlocking {
        TestNoticeHandler.lastNotice = null
        
        val session = openSession { noticeHandler = "io.github.octaviusframework.driver.notice.TestNoticeHandler" }
        
        // Generate a notice
        session.createNativeQuery("DO $$ BEGIN RAISE NOTICE 'the augurs report a flight of crows'; END; $$;").execute()
        
        val notice = TestNoticeHandler.lastNotice
        assertNotNull(notice)
        assertEquals("the augurs report a flight of crows", notice.message)
        assertEquals("NOTICE", notice.severity)

        // A shared handler tells connections apart by this, so it has to be the backend that raised it
        val backendPid: Int = session.createNativeQuery("SELECT pg_backend_pid()").fetchFieldStrict()
        assertEquals(backendPid, notice.processId)

        session.close()
    }

    /**
     * A `NoticeResponse` carries the same fields an `ErrorResponse` does, and `RAISE ... USING` is what
     * fills in the ones naming an object.
     */
    @Test
    fun testNoticeExposesEveryFieldTheServerSends() = runBlocking {
        TestNoticeHandler.lastNotice = null

        val session = openSession { noticeHandler = "io.github.octaviusframework.driver.notice.TestNoticeHandler" }

        session.createNativeQuery(
            """DO $$ BEGIN
                 RAISE NOTICE 'tribute short' USING
                   DETAIL = 'Gallia paid half', HINT = 'send a quaestor', ERRCODE = '22000',
                   COLUMN = 'denarii', CONSTRAINT = 'tribute_paid', DATATYPE = 'numeric',
                   TABLE = 'tributes', SCHEMA = 'aerarium';
               END; $$;"""
        ).execute()

        val notice = TestNoticeHandler.lastNotice
        assertNotNull(notice)

        assertEquals("tribute short", notice.message)
        assertEquals("22000", notice.code)
        assertEquals("Gallia paid half", notice.detail)
        assertEquals("send a quaestor", notice.hint)
        assertEquals("aerarium", notice.schema)
        assertEquals("tributes", notice.table)
        assertEquals("denarii", notice.column)
        assertEquals("numeric", notice.datatype)
        assertEquals("tribute_paid", notice.constraint)

        // Sent with every notice, wherever it came from.
        assertNotNull(notice.file)
        assertNotNull(notice.line)
        assertNotNull(notice.routine)
        assertNotNull(notice.where)

        // severity is read from the non-localized field, so it is English on a localized server too
        assertEquals("NOTICE", notice.severity)

        session.close()
    }
}

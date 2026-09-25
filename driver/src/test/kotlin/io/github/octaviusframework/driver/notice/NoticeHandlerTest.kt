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
        session.createNativeQuery("DO $$ BEGIN RAISE NOTICE 'test notice from test'; END; $$;").execute()
        
        val notice = TestNoticeHandler.lastNotice
        assertNotNull(notice)
        assertEquals("test notice from test", notice.message)
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
                 RAISE NOTICE 'rich notice' USING
                   DETAIL = 'the detail', HINT = 'the hint', ERRCODE = '22000',
                   COLUMN = 'col_x', CONSTRAINT = 'con_x', DATATYPE = 'dt_x',
                   TABLE = 'tab_x', SCHEMA = 'sch_x';
               END; $$;"""
        ).execute()

        val notice = TestNoticeHandler.lastNotice
        assertNotNull(notice)

        assertEquals("rich notice", notice.message)
        assertEquals("22000", notice.code)
        assertEquals("the detail", notice.detail)
        assertEquals("the hint", notice.hint)
        assertEquals("sch_x", notice.schema)
        assertEquals("tab_x", notice.table)
        assertEquals("col_x", notice.column)
        assertEquals("dt_x", notice.datatype)
        assertEquals("con_x", notice.constraint)

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

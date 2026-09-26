package io.github.octaviusframework.driver.query

import io.github.octaviusframework.driver.session.OctaviusSession
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class RoutineQueryIntegrationTest : AbstractIntegrationTest() {

    private lateinit var session: OctaviusSession

    override val schema = """
        -- A function: the men a legion musters
        CREATE FUNCTION muster(cohorts INT, auxilia INT) RETURNS INT AS $$
        BEGIN
            RETURN cohorts + auxilia;
        END;
        $$ LANGUAGE plpgsql;

        -- A procedure with an INOUT parameter: the treasury after a levy
        CREATE PROCEDURE levy(INOUT treasury INT, amount INT) AS $$
        BEGIN
            treasury := treasury + amount;
        END;
        $$ LANGUAGE plpgsql;

        -- A table-returning function: a legion's cohorts, numbered
        CREATE FUNCTION number_cohorts(n INT) RETURNS TABLE(cohort INT) AS $$
        BEGIN
            RETURN QUERY SELECT generate_series(1, n);
        END;
        $$ LANGUAGE plpgsql;

        -- A void-returning function
        CREATE FUNCTION sacrifice() RETURNS VOID AS $$
        BEGIN
        END;
        $$ LANGUAGE plpgsql;

        -- A procedure without OUT parameters
        CREATE PROCEDURE adjourn_senate() AS $$
        BEGIN
        END;
        $$ LANGUAGE plpgsql;
    """.trimIndent()

    @BeforeAll
    fun setup() {
        session = openSession()
    }

    @AfterAll
    fun teardown() {
        session.close()
    }

    @Test
    fun testCallFunction() {
        val result = session.createNativeQuery("SELECT muster($1, $2)").fetchFieldStrict<Int>(10, 20)
        assertEquals(30, result)
    }

    @Test
    fun testCallFunctionWithNamedParameters() {
        val result = session.createNamedQuery("SELECT muster(@cohorts, @auxilia)")
            .fetchFieldStrict<Int>("cohorts" to 5, "auxilia" to 15)
        assertEquals(20, result)
    }
    
    @Test
    fun testCallTableFunction() {
        val rows = session.createNativeQuery("SELECT * FROM number_cohorts($1)").fetchFields<Int>(5)
        assertEquals(listOf(1, 2, 3, 4, 5), rows)
    }

    @Test
    fun testCallProcedure() {
        // Procedure with INOUT requires CALL and returns a row in PostgreSQL
        val resultRow = session.createNativeQuery("CALL levy($1, $2)").fetchRowStrict(100, 50)
        assertEquals(150, resultRow.get<Int>(0))
    }

    @Test
    fun testCallVoidFunction() {
        val result = session.createNativeQuery("SELECT sacrifice()").fetchField<Unit>()
        assertEquals(Unit, result)
    }

    @Test
    fun testCallProcedureWithoutOut() {
        session.createNativeQuery("CALL adjourn_senate()").execute()
        val updateCount = session.createNativeQuery("CALL adjourn_senate()").update()
        assertEquals(0L, updateCount)
    }
}

package io.github.octaviusframework.driver.complex

import io.github.octaviusframework.identifier.CaseConvention
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import kotlinx.datetime.LocalDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CrazyRecordIntegrationTest : AbstractIntegrationTest() {

    enum class TestStatus { Active, Inactive, Pending, NotStarted }
    enum class TestPriority { Low, Medium, High, Critical }
    enum class TestCategory { BugFix, Feature, Enhancement, Documentation }
    
    data class TestMetadata(
        val createdAt: LocalDateTime,
        val updatedAt: LocalDateTime,
        val version: Int,
        val tags: List<String>
    )

    data class TestPerson(
        val name: String?,
        val age: Int?,
        val email: String?,
        val active: Boolean?,
        val roles: List<String>?
    )

    data class TestTask(
        val id: Int,
        val title: String,
        val description: String,
        val status: TestStatus,
        val priority: TestPriority,
        val category: TestCategory,
        val assignee: TestPerson,
        val metadata: TestMetadata,
        val subtasks: List<String>,
        val estimatedHours: BigDecimal
    )

    data class TestProject(
        val name: String,
        val description: String,
        val status: TestStatus,
        val teamMembers: List<TestPerson>,
        val tasks: List<TestTask>,
        val metadata: TestMetadata,
        val budget: BigDecimal
    )

    override val schema = """
        CREATE TYPE test_status AS ENUM ('active', 'inactive', 'pending', 'not_started');
        CREATE TYPE test_priority AS ENUM ('low', 'medium', 'high', 'critical');
        CREATE TYPE test_category AS ENUM ('bug_fix', 'feature', 'enhancement', 'documentation');

        CREATE TYPE test_metadata AS (
            created_at TIMESTAMP,
            updated_at TIMESTAMP,
            version INT,
            tags TEXT[]
        );
        CREATE TYPE test_person AS (
            name TEXT,
            age INT,
            email TEXT,
            active BOOLEAN,
            roles TEXT[]
        );
        CREATE TYPE test_task AS (
            id INT,
            title TEXT,
            description TEXT,
            status test_status,
            priority test_priority,
            category test_category,
            assignee test_person,
            metadata test_metadata,
            subtasks TEXT[],
            estimated_hours NUMERIC
        );
        CREATE TYPE test_project AS (
            name TEXT,
            description TEXT,
            status test_status,
            team_members test_person[],
            tasks test_task[],
            metadata test_metadata,
            budget NUMERIC
        );
    """.trimIndent()

    @Test
    fun `should read absolutely insane record with composites, enums, and nested records`() {
        val session = openSession()
        try {
            session.reloadTypes()
            session.typeManager.registerEnum<TestStatus>("test_status", pgConvention = CaseConvention.SNAKE_CASE_LOWER)
            session.typeManager.registerEnum<TestPriority>("test_priority", pgConvention = CaseConvention.SNAKE_CASE_LOWER)
            session.typeManager.registerEnum<TestCategory>("test_category", pgConvention = CaseConvention.SNAKE_CASE_LOWER)
            
            session.typeManager.registerAutoComposite<TestMetadata>("test_metadata")
            session.typeManager.registerAutoComposite<TestPerson>("test_person")
            session.typeManager.registerAutoComposite<TestTask>("test_task")
            session.typeManager.registerAutoComposite<TestProject>("test_project")

            val sql = """
                SELECT ROW(
                    'simple_string', 'insanity'::text,
                    'enum_val', 'active'::test_status,
                    'comp_val', ROW('2024-01-01 10:00:00'::timestamp, '2024-01-02 10:00:00'::timestamp, 1, ARRAY['tag1', 'tag2'])::test_metadata,
                    'nested', ROW(
                        'enum_array', ARRAY['active'::test_status, 'pending'::test_status]::test_status[]
                    ),
                    'insane_array', ARRAY[
                        ROW('id', 1, 'status', 'pending'::test_status),
                        ROW('id', 2, 'status', 'active'::test_status)
                    ]::record[]
                ) as rec
            """.trimIndent()

            val result = session.createNamedQuery(sql)
                .fetchRowStrict()

            @Suppress("UNCHECKED_CAST")
            val rec = result.get<Map<String, Any?>>("rec")

            assertEquals("insanity", rec["simple_string"])
            assertEquals(TestStatus.Active, rec["enum_val"])

            val compVal = rec["comp_val"] as TestMetadata
            assertEquals(LocalDateTime.parse("2024-01-01T10:00:00"), compVal.createdAt)
            assertEquals(LocalDateTime.parse("2024-01-02T10:00:00"), compVal.updatedAt)
            assertEquals(1, compVal.version)
            assertEquals(listOf("tag1", "tag2"), compVal.tags)

            @Suppress("UNCHECKED_CAST")
            val nested = rec["nested"] as Map<String, Any?>
            
            @Suppress("UNCHECKED_CAST")
            val enumArray = nested["enum_array"] as List<TestStatus>
            assertEquals(listOf(TestStatus.Active, TestStatus.Pending), enumArray)

            @Suppress("UNCHECKED_CAST")
            val insaneArray = rec["insane_array"] as List<Map<String, Any?>>
            assertEquals(2, insaneArray.size)
            assertEquals(1, insaneArray[0]["id"])
            assertEquals(TestStatus.Pending, insaneArray[0]["status"])
            assertEquals(2, insaneArray[1]["id"])
            assertEquals(TestStatus.Active, insaneArray[1]["status"])
            
        } finally {
            session.close()
        }
    }
}

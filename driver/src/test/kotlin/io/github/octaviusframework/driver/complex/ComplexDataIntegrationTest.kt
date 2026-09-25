package io.github.octaviusframework.driver.complex

import io.github.octaviusframework.identifier.CaseConvention
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import kotlinx.datetime.LocalDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ComplexDataIntegrationTest : AbstractIntegrationTest() {

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
            created_at timestamp,
            updated_at timestamp,
            version integer,
            tags text[]
        );

        CREATE TYPE test_person AS (
            name text,
            age integer,
            email text,
            active boolean,
            roles text[]
        );

        CREATE TYPE test_task AS (
            id integer,
            title text,
            description text,
            status test_status,
            priority test_priority,
            category test_category,
            assignee test_person,
            metadata test_metadata,
            subtasks text[],
            estimated_hours numeric
        );

        CREATE TYPE test_project AS (
            name text,
            description text,
            status test_status,
            team_members test_person[],
            tasks test_task[],
            metadata test_metadata,
            budget numeric
        );

        CREATE TABLE complex_test_data (
            id SERIAL PRIMARY KEY,
            simple_text text,
            simple_number integer,
            simple_bool boolean,
            project_data test_project,
            person_array test_person[]
        );
    """.trimIndent()

    @Test
    fun `should insert and then retrieve an entire complex object`() {
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

            val newProject = createSampleProject()

            val sql = """
                INSERT INTO complex_test_data (
                    simple_text, simple_number, simple_bool, project_data, person_array
                ) VALUES (
                    @text, @number, @bool, @project, @persons
                ) RETURNING id
            """.trimIndent()

            val insertRow = session.createNamedQuery(sql)
                .fetchRowStrict(
                    "text" to "New Project Entry",
                    "number" to 101,
                    "bool" to true,
                    "project" to newProject,
                    "persons" to newProject.teamMembers
                )
            
            val newId = insertRow.get<Int>("id")

            val retrievedRow = session.createNamedQuery("SELECT project_data, person_array FROM complex_test_data WHERE id = @id")
                .fetchRowStrict("id" to newId)

            val retrievedProject = retrievedRow.get<TestProject>("project_data")
            val retrievedPersons = retrievedRow.get<List<TestPerson>>("person_array")

            assertEquals(newProject, retrievedProject)
            assertEquals(newProject.teamMembers, retrievedPersons)

        } finally {
            session.close()
        }
    }

    @Test
    fun `should update a complex array field in an existing row`() {
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

            val newProject = createSampleProject()
            
            // Insert initial row
            val insertSql = """
                INSERT INTO complex_test_data (
                    simple_text, simple_number, simple_bool, project_data, person_array
                ) VALUES (
                    @text, @number, @bool, @project, @persons
                ) RETURNING id
            """.trimIndent()
            
            val newId = session.createNamedQuery(insertSql)
                .fetchRowStrict(
                    "text" to "Initial",
                    "number" to 0,
                    "bool" to false,
                    "project" to newProject,
                    "persons" to newProject.teamMembers
                ).get<Int>("id")


            val newTeam = listOf(
                TestPerson("Charlie Day", 45, "charlie@paddys.pub", true, listOf("wildcard")),
                TestPerson("Frank Reynolds", 70, "frank@warthog.com", true, listOf("financier", "mastermind"))
            )

            val updateSql = "UPDATE complex_test_data SET person_array = @newTeam WHERE id = @id"
            val updatedRows = session.createNamedQuery(updateSql).update("newTeam" to newTeam, "id" to newId)
            assertEquals(1, updatedRows)

            val retrievedRow = session.createNamedQuery("SELECT person_array FROM complex_test_data WHERE id = @id")
                .fetchRowStrict("id" to newId)
            val retrievedTeam = retrievedRow.get<List<TestPerson>>("person_array")

            assertEquals(newTeam, retrievedTeam)

        } finally {
            session.close()
        }
    }

    private fun createSampleProject(): TestProject {
        return TestProject(
            name = "Project \"Phoenix\"",
            description = "A project to test data serialization/deserialization.",
            status = TestStatus.Pending,
            teamMembers = listOf(
                TestPerson("Dr. Alan Grant", 55, "alan.grant@jurassic.park", true, listOf("paleontologist")),
                TestPerson("Dr. Ellie Sattler", 48, "ellie.sattler@jurassic.park", true, listOf("paleobotanist"))
            ),
            tasks = listOf(
                TestTask(
                    id = 1001,
                    title = "Secure the 'Raptor' enclosure",
                    description = "High priority task, involves complex logic.",
                    status = TestStatus.Pending,
                    priority = TestPriority.Critical,
                    category = TestCategory.BugFix,
                    assignee = TestPerson("Robert Muldoon", 45, "muldoon@jurassic.park", true, listOf("game_warden")),
                    metadata = TestMetadata(
                        createdAt = LocalDateTime(2023, 10, 26, 9, 0),
                        updatedAt = LocalDateTime(2023, 10, 26, 11, 30),
                        version = 2,
                        tags = listOf("security", "critical-path")
                    ),
                    subtasks = listOf("Check fence integrity", "Verify power grid"),
                    estimatedHours = BigDecimal("8.0")
                )
            ),
            metadata = TestMetadata(
                createdAt = LocalDateTime(2023, 10, 1, 0, 0),
                updatedAt = LocalDateTime(2023, 10, 26, 12, 0),
                version = 5,
                tags = listOf("gen-2", "classified")
            ),
            budget = BigDecimal("5000000.00")
        )
    }
}

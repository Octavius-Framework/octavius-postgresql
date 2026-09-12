package com.example.demo

import com.example.demo.domain.Address
import com.example.demo.domain.User
import com.example.demo.domain.UserProfile
import com.example.demo.domain.UserRole
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.RestTestClient
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * What this example claims, asserted over HTTP against a real PostgreSQL 18.
 *
 * The example is the only consumer of `driver-spring-integration` in the repository written the way
 * an application writes it, and nothing it demonstrates is visible to a compiler: the template and
 * the transaction manager arrive from auto-configuration, the composites and the enum are registered
 * at runtime against OIDs that did not exist when the context started, and both deliberate failures
 * are decided by the server and translated on the way out. Compiling the example proves none of it,
 * so these four requests do - one per contract.
 *
 * The schema is installed per test rather than once: [DemoSchema.install] drops what it creates, so
 * every test gets an empty table and none of them has to pick a name around the others. An
 * application calls it once, at startup, which is what the runner does.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DemoApplicationIntegrationTest {

    @field:LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var schema: DemoSchema

    private lateinit var client: RestTestClient

    @BeforeEach
    fun setUp() {
        schema.install()
        client = RestTestClient.bindToServer().baseUrl("http://localhost:$port").build()
    }

    private fun user(name: String) = User(
        name = name,
        role = UserRole.ADMIN,
        primaryAddress = Address(city = "Roma", street = "Via Sacra", buildingNumber = 7),
        shippingAddresses = listOf(
            Address(city = "Ostia", street = "Decumanus", buildingNumber = 12),
            Address(city = "Capua", street = "Via Appia", buildingNumber = 3)
        ),
        profile = UserProfile(age = 44, nickname = "consul", settings = mapOf("theme" to "dark"))
    )

    private fun post(path: String, user: User) =
        client.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(user).exchange()

    @Test
    fun `a user round trips through the composite, the array of composites, the enum and the map`() {
        val sent = user("cicero")

        val created = post("/users", sent)
            .expectStatus().isOk()
            .expectBody(User::class.java)
            .returnResult().responseBody

        assertNotNull(created)
        assertNotNull(created.id, "the id comes from the column default, so the row was really written")
        assertEquals(sent.role, created.role)
        assertEquals(sent.primaryAddress, created.primaryAddress)
        assertEquals(sent.shippingAddresses, created.shippingAddresses)
        assertEquals(sent.profile, created.profile)

        // The same row through the other mapping the controller shows: row.get<T>() per column
        // rather than fetchObjectStrict<T>() over the whole row
        val byId = client.get().uri("/users/${created.id}")
            .exchange()
            .expectStatus().isOk()
            .expectBody(User::class.java)
            .returnResult().responseBody

        assertEquals(created, byId)
    }

    @Test
    fun `a duplicate name arrives as a translated constraint violation`() {
        val sent = user("cato")

        post("/users", sent).expectStatus().isOk()

        // ConstraintViolationException(UNIQUE_CONSTRAINT_VIOLATION) -> OctaviusDataAccessException ->
        // GlobalExceptionHandler. A 500 here would mean the reason did not survive the crossing.
        post("/users", sent).expectStatus().isEqualTo(HttpStatus.CONFLICT)
    }

    @Test
    fun `a write inside a read only transaction is refused by the server`() {
        // @Transactional(readOnly = true) has to reach the server as a real read-only transaction:
        // PostgreSQL is what refuses the insert, and the refusal comes back as
        // TransactionStateException(READ_ONLY_TRANSACTION)
        post("/users/demo-readonly", user("brutus")).expectStatus().isForbidden()
    }

    @Test
    fun `a failure after a write rolls the transaction back`() {
        val sent = user("catilina")

        post("/users/demo-rollback", sent).expectStatus().is5xxServerError()

        val all = client.get().uri("/users")
            .exchange()
            .expectStatus().isOk()
            .expectBody(object : ParameterizedTypeReference<List<User>>() {})
            .returnResult().responseBody

        assertNotNull(all)
        assertFalse(
            all.any { it.name == sent.name },
            "the insert ran and the method then threw, so the transaction manager must have rolled it back"
        )
    }
}

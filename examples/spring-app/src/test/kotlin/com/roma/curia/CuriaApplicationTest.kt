package com.roma.curia

import com.roma.curia.domain.Dossier
import com.roma.curia.domain.Estate
import com.roma.curia.domain.Magistracy
import com.roma.curia.domain.Senator
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
 * so these requests do - one per contract.
 *
 * The schema is installed per test rather than once: [CuriaSchema.install] drops what it creates, so
 * every test gets an empty table and none of them has to pick a cognomen around the others. An
 * application calls it once, at startup, which is what the runner does.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CuriaApplicationTest {

    @field:LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var schema: CuriaSchema

    private lateinit var client: RestTestClient

    @BeforeEach
    fun setUp() {
        schema.install()
        client = RestTestClient.bindToServer().baseUrl("http://localhost:$port").build()
    }

    private fun senator(cognomen: String) = Senator(
        cognomen = cognomen,
        magistracy = Magistracy.CONSUL,
        familySeat = Estate(province = "Italia", nearestTown = "Arpinum", iugera = 200),
        countryEstates = listOf(
            Estate(province = "Italia", nearestTown = "Tusculum", iugera = 120),
            Estate(province = "Campania", nearestTown = "Pompeii", iugera = 80)
        ),
        dossier = Dossier(age = 44, agnomen = "Pater Patriae", honours = mapOf("supplicatio" to "Catilina"))
    )

    private fun post(path: String, senator: Senator) =
        client.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(senator).exchange()

    @Test
    fun `a senator round trips through the composite, the array of composites, the enum and the map`() {
        val sent = senator("Cicero")

        val created = post("/senators", sent)
            .expectStatus().isOk()
            .expectBody(Senator::class.java)
            .returnResult().responseBody

        assertNotNull(created)
        assertNotNull(created.id, "the id comes from the column default, so the row was really written")
        assertEquals(sent.magistracy, created.magistracy)
        assertEquals(sent.familySeat, created.familySeat)
        assertEquals(sent.countryEstates, created.countryEstates)
        assertEquals(sent.dossier, created.dossier)

        // The same row through the other mapping the controller shows: row.get<T>() per column
        // rather than fetchObjectStrict<T>() over the whole row
        val byId = client.get().uri("/senators/${created.id}")
            .exchange()
            .expectStatus().isOk()
            .expectBody(Senator::class.java)
            .returnResult().responseBody

        assertEquals(created, byId)
    }

    @Test
    fun `a senator with no country estates goes in, the empty list sent with its array type stated`() {
        // An empty list has no element to take a type from; without withPgType this is a MappingException
        // and a 500
        val sent = senator("Clodius").copy(countryEstates = emptyList())

        val created = post("/senators", sent)
            .expectStatus().isOk()
            .expectBody(Senator::class.java)
            .returnResult().responseBody

        assertNotNull(created)
        assertEquals(emptyList(), created.countryEstates)
    }

    @Test
    fun `a duplicate cognomen arrives as the driver's constraint violation`() {
        val sent = senator("Cato")

        post("/senators", sent).expectStatus().isOk()

        // ConstraintViolationException(UNIQUE_CONSTRAINT_VIOLATION) -> GlobalExceptionHandler, matched by
        // its own type. A 500 here would mean the reason did not survive the crossing.
        post("/senators", sent).expectStatus().isEqualTo(HttpStatus.CONFLICT)
    }

    @Test
    fun `a write inside a read only transaction is refused by the server`() {
        // @Transactional(readOnly = true) has to reach the server as a real read-only transaction:
        // PostgreSQL is what refuses the insert, and the refusal comes back as
        // TransactionStateException(READ_ONLY_TRANSACTION)
        post("/senators/demo-readonly", senator("Brutus")).expectStatus().isForbidden()
    }

    @Test
    fun `a failure after a write rolls the transaction back`() {
        val sent = senator("Catilina")

        post("/senators/demo-rollback", sent).expectStatus().is5xxServerError()

        val all = client.get().uri("/senators")
            .exchange()
            .expectStatus().isOk()
            .expectBody(object : ParameterizedTypeReference<List<Senator>>() {})
            .returnResult().responseBody

        assertNotNull(all)
        assertFalse(
            all.any { it.cognomen == sent.cognomen },
            "the insert ran and the method then threw, so the transaction manager must have rolled it back"
        )
    }
}

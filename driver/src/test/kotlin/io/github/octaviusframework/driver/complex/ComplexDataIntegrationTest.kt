package io.github.octaviusframework.driver.complex

import io.github.octaviusframework.identifier.CaseConvention
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import kotlinx.datetime.LocalDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * A campaign as one value: composites inside composites, arrays of them, enums at every level, and among the
 * attributes the nullable, the dated and the exact. It goes through a table and comes back whole, and it is met
 * again inside an anonymous record, where nothing but the catalog says what each field is.
 */
class ComplexDataIntegrationTest : AbstractIntegrationTest() {

    enum class CampaignStatus { Planned, OnMarch, Besieging, Concluded }
    enum class Urgency { Routine, Pressing, Grave, Desperate }
    enum class Arm { Infantry, Cavalry, SiegeEngineers, Auxilia }

    data class Chronicle(
        val recordedAt: LocalDateTime,
        val amendedAt: LocalDateTime,
        val revision: Int,
        val seals: List<String>
    )

    /** Every attribute nullable, so a `NULL` inside a composite inside an array has somewhere to land. */
    data class Officer(
        val name: String?,
        val age: Int?,
        val tribe: String?,
        val onDuty: Boolean?,
        val honours: List<String>?
    )

    data class StandingOrder(
        val id: Int,
        val title: String,
        val description: String,
        val status: CampaignStatus,
        val urgency: Urgency,
        val arm: Arm,
        val assignee: Officer,
        val chronicle: Chronicle,
        val steps: List<String>,
        val estimatedDays: BigDecimal
    )

    data class Campaign(
        val name: String,
        val description: String,
        val status: CampaignStatus,
        val officers: List<Officer>,
        val orders: List<StandingOrder>,
        val chronicle: Chronicle,
        val treasury: BigDecimal
    )

    override val schema = """
        CREATE TYPE campaign_status AS ENUM ('planned', 'on_march', 'besieging', 'concluded');
        CREATE TYPE urgency AS ENUM ('routine', 'pressing', 'grave', 'desperate');
        CREATE TYPE arm AS ENUM ('infantry', 'cavalry', 'siege_engineers', 'auxilia');

        CREATE TYPE chronicle AS (recorded_at timestamp, amended_at timestamp, revision integer, seals text[]);
        CREATE TYPE officer AS (name text, age integer, tribe text, on_duty boolean, honours text[]);
        CREATE TYPE standing_order AS (
            id             integer,
            title          text,
            description    text,
            status         campaign_status,
            urgency        urgency,
            arm            arm,
            assignee       officer,
            chronicle      chronicle,
            steps          text[],
            estimated_days numeric
        );
        CREATE TYPE campaign AS (
            name        text,
            description text,
            status      campaign_status,
            officers    officer[],
            orders      standing_order[],
            chronicle   chronicle,
            treasury    numeric
        );

        CREATE TABLE campaigns (
            id         SERIAL PRIMARY KEY,
            legion     text,
            cohorts    integer,
            victorious boolean,
            campaign   campaign,
            officers   officer[]
        );
    """.trimIndent()

    @BeforeAll
    fun register() {
        openSession().use { session ->
            with(session.typeManager) {
                registerEnum<CampaignStatus>("campaign_status", pgConvention = CaseConvention.SNAKE_CASE_LOWER)
                registerEnum<Urgency>("urgency", pgConvention = CaseConvention.SNAKE_CASE_LOWER)
                registerEnum<Arm>("arm", pgConvention = CaseConvention.SNAKE_CASE_LOWER)
                registerAutoComposite<Chronicle>("chronicle")
                registerAutoComposite<Officer>("officer")
                registerAutoComposite<StandingOrder>("standing_order")
                registerAutoComposite<Campaign>("campaign")
            }
        }
    }

    private val insert = """
        INSERT INTO campaigns (legion, cohorts, victorious, campaign, officers)
        VALUES (@legion, @cohorts, @victorious, @campaign, @officers)
        RETURNING id
    """.trimIndent()

    @Test
    fun `a campaign goes into a row and comes back whole`() {
        openSession().use { session ->
            val campaign = britannia()
            val id = session.createNamedQuery(insert).fetchFieldStrict<Int>(
                "legion" to "II Augusta",
                "cohorts" to 10,
                "victorious" to true,
                "campaign" to campaign,
                "officers" to campaign.officers
            )

            val row = session.createNamedQuery("SELECT campaign, officers FROM campaigns WHERE id = @id")
                .fetchRowStrict("id" to id)

            assertEquals(campaign, row.get<Campaign>("campaign"))
            assertEquals(campaign.officers, row.get<List<Officer>>("officers"))
        }
    }

    @Test
    fun `an array of composites in a row is replaced whole`() {
        openSession().use { session ->
            val campaign = britannia()
            val id = session.createNamedQuery(insert).fetchFieldStrict<Int>(
                "legion" to "XIV Gemina",
                "cohorts" to 0,
                "victorious" to false,
                "campaign" to campaign,
                "officers" to campaign.officers
            )

            val relief = listOf(
                Officer("Gnaeus Hosidius Geta", 30, "Arnensis", true, listOf("ornamenta triumphalia")),
                Officer("Titus Flavius Sabinus", 35, null, true, null)
            )
            val updated = session.createNamedQuery("UPDATE campaigns SET officers = @officers WHERE id = @id")
                .update("officers" to relief, "id" to id)
            assertEquals(1L, updated)

            val back = session.createNamedQuery("SELECT officers FROM campaigns WHERE id = @id")
                .fetchFieldStrict<List<Officer>>("id" to id)
            assertEquals(relief, back)
        }
    }

    @Test
    fun `an anonymous record reads as a map, each registered type resolved where it sits`() {
        openSession().use { session ->
            val record = session.createNativeQuery(
                """
                SELECT ROW(
                    'legion', 'II Augusta'::text,
                    'status', 'on_march'::campaign_status,
                    'chronicle', ROW('0043-05-01 09:00'::timestamp, '0043-08-15 12:00'::timestamp, 1,
                                     ARRAY['senatus', 'populus'])::chronicle,
                    'nested', ROW(
                        'statuses', ARRAY['on_march'::campaign_status, 'besieging'::campaign_status]::campaign_status[]
                    ),
                    'orders', ARRAY[
                        ROW('id', 1, 'status', 'besieging'::campaign_status),
                        ROW('id', 2, 'status', 'on_march'::campaign_status)
                    ]::record[]
                )
                """.trimIndent()
            ).fetchFieldStrict<Map<String, Any?>>()

            assertEquals("II Augusta", record["legion"])
            assertEquals(CampaignStatus.OnMarch, record["status"])
            assertEquals(
                Chronicle(LocalDateTime(43, 5, 1, 9, 0), LocalDateTime(43, 8, 15, 12, 0), 1, listOf("senatus", "populus")),
                record["chronicle"]
            )

            @Suppress("UNCHECKED_CAST")
            val nested = record["nested"] as Map<String, Any?>
            assertEquals(listOf(CampaignStatus.OnMarch, CampaignStatus.Besieging), nested["statuses"])

            assertEquals(
                listOf(
                    mapOf("id" to 1, "status" to CampaignStatus.Besieging),
                    mapOf("id" to 2, "status" to CampaignStatus.OnMarch)
                ),
                record["orders"]
            )
        }
    }

    /** The invasion of AD 43, quotes of both kinds included, since both are special inside a composite's text. */
    private fun britannia() = Campaign(
        name = "Expeditio \"Britannica\"",
        description = "Claudius's invasion, four legions across the Oceanus.",
        status = CampaignStatus.OnMarch,
        officers = listOf(
            Officer("Aulus Plautius", 50, "Aniensis", true, listOf("ovatio")),
            Officer("Titus Flavius Vespasianus", 33, "Quirina", true, listOf("ornamenta triumphalia", "duplex sacerdotium"))
        ),
        orders = listOf(
            StandingOrder(
                id = 1001,
                title = "Force the crossing of the 'Medway'",
                description = "Two days at the river, the Batavians swimming it in armour.",
                status = CampaignStatus.Besieging,
                urgency = Urgency.Desperate,
                arm = Arm.Auxilia,
                assignee = Officer("Gnaeus Hosidius Geta", 30, null, true, listOf("ornamenta triumphalia")),
                chronicle = Chronicle(
                    recordedAt = LocalDateTime(43, 6, 1, 6, 0),
                    amendedAt = LocalDateTime(43, 6, 3, 18, 30),
                    revision = 2,
                    seals = listOf("legatus", "praefectus castrorum")
                ),
                steps = listOf("Swim the auxilia across", "Hold the far bank", "Bring the legions over"),
                estimatedDays = BigDecimal("2.0")
            )
        ),
        chronicle = Chronicle(
            recordedAt = LocalDateTime(43, 5, 1, 9, 0),
            amendedAt = LocalDateTime(43, 8, 15, 12, 0),
            revision = 5,
            seals = listOf("SPQR", "Ti. Claudius Caesar Augustus")
        ),
        treasury = BigDecimal("5000000.00")
    )
}

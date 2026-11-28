package com.roma.census

import io.github.octaviusframework.client.OctaviusClient
import io.github.octaviusframework.client.transaction.TransactionPlan

/** The id and name of a row just inserted. */
data class Enrolled(val id: Int, val nomen: String)

/**
 * Enters a father and his sons as one transaction plan. The sons' rows need the father's id before the
 * insert that generates it has run, and the handle of that insert stands for it.
 */
data class Enroll(
    val nomen: String,
    val tribe: String,
    val ordo: Ordo,
    val residence: Residence,
    val assessment: Long,
    val sons: List<String>
) : Command {

    override fun run(db: OctaviusClient) {
        val plan = TransactionPlan()

        val row = mapOf(
            "nomen" to nomen,
            "tribe" to tribe,
            "ordo" to ordo,
            "residence" to residence,
            "assessment" to assessment
        )
        val father = with(plan) {
            insertInto("citizens").values(row).returning("id").asStep().fetchFieldStrict<Int>(row)
        }

        // A son in his father's power owned nothing of his own: he goes on the rolls under the father's
        // tribe, order and house, assessed at nothing. One statement for all of them, however many.
        val children = if (sons.isEmpty()) null else with(plan) {
            rawQuery(
                """
                INSERT INTO citizens (nomen, tribe, ordo, residence, assessment, pater_id)
                SELECT s.nomen, f.tribe, f.ordo, f.residence, 0, f.id
                FROM citizens f, UNNEST(@sons) WITH ORDINALITY AS s(nomen, n)
                WHERE f.id = @father_id
                ORDER BY s.n
                RETURNING id, nomen
                """
            ).asStep().fetchObjects<Enrolled>("sons" to sons, "father_id" to father.value())
        }

        val result = db.executeTransactionPlan(plan)

        println("Enrolled #${result[father]} $nomen")
        children?.let { result[it] }.orEmpty().sortedBy { it.id }.forEach { println("  son #${it.id} ${it.nomen}") }
    }

    companion object {
        fun parse(arguments: Arguments) = Enroll(
            nomen = arguments.required("nomen"),
            tribe = arguments.required("tribe"),
            ordo = arguments.ordo("ordo") ?: throw UsageException("--ordo is required"),
            residence = Residence(arguments.required("city"), arguments.required("district")),
            assessment = arguments.long("assessment") ?: throw UsageException("--assessment is required"),
            sons = arguments.all("son")
        )
    }
}

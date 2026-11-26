package com.roma.curia.controller

import com.roma.curia.domain.Senator
import io.github.octaviusframework.driver.spring.OctaviusTemplate
import io.github.octaviusframework.driver.type.withPgType
import io.github.octaviusframework.driver.util.reflection.toDataMap
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import kotlin.uuid.Uuid

@RestController
class SenatorController(private val octaviusTemplate: OctaviusTemplate) {


    @PostMapping("/senators")
    @Transactional
    fun createSenator(@RequestBody senator: Senator): Senator {
        return octaviusTemplate.execute {
            createNamedQuery(
                """
                INSERT INTO senators (cognomen, magistracy, family_seat, country_estates, dossier)
                VALUES (@cognomen, @magistracy, @family_seat, @country_estates, @dossier)
                RETURNING *
            """
            ).fetchObjectStrict<Senator>(insertParams(senator))
        }
    }

    @GetMapping("/senators")
    @Transactional(readOnly = true)
    fun getSenators(): List<Senator> {
        return octaviusTemplate.execute {
            createNamedQuery("SELECT * FROM senators")
                .fetchObjects<Senator>()
        }
    }

    @GetMapping("/senators/{id}")
    fun getSenator(@PathVariable id: Uuid): Senator {
        return octaviusTemplate.execute {
            val row = createNamedQuery("SELECT * FROM senators WHERE id = @id")
                .fetchRowStrict("id" to id)

            Senator(
                id = row.get("id"),
                cognomen = row.get("cognomen"),
                magistracy = row.get("magistracy"),
                familySeat = row.get("family_seat"),
                countryEstates = row.get("country_estates"),
                dossier = row.get("dossier")
            )
        }
    }

    @PostMapping("/senators/demo-rollback")
    @Transactional
    fun demoRollback(@RequestBody senator: Senator): Senator {
        octaviusTemplate.execute {
            createNamedQuery(
                """
                INSERT INTO senators (cognomen, magistracy, family_seat, country_estates, dossier)
                VALUES (@cognomen, @magistracy, @family_seat, @country_estates, @dossier)
            """
            ).executeUpdate(insertParams(senator))
        }

        throw RuntimeException("Intentional exception to trigger transaction rollback!")
    }

    @PostMapping("/senators/demo-readonly")
    @Transactional(readOnly = true)
    fun demoReadOnly(@RequestBody senator: Senator): Senator {
        return octaviusTemplate.execute {
            createNamedQuery(
                """
                INSERT INTO senators (cognomen, magistracy, family_seat, country_estates, dossier)
                VALUES (@cognomen, @magistracy, @family_seat, @country_estates, @dossier)
                RETURNING *
            """
            ).fetchRowStrict(insertParams(senator))

            senator
        }
    }

    /**
     * What each insert above binds: the senator's properties under their column names, without the `id` the
     * table generates. `country_estates` is replaced with the same list stating its type, because an empty one
     * has no element to take it from and would fail with `MappingException` instead of going in as `estate[]`.
     */
    private fun insertParams(senator: Senator): Map<String, Any?> =
        senator.toDataMap("id") + ("country_estates" to senator.countryEstates.withPgType("estate", isArray = true))
}

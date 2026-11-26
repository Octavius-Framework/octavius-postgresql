package com.roma.curia.controller

import com.roma.curia.domain.Senator
import io.github.octaviusframework.driver.spring.OctaviusTemplate
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
            ).fetchObjectStrict<Senator>(
                "cognomen" to senator.cognomen,
                "magistracy" to senator.magistracy,
                "family_seat" to senator.familySeat,
                "country_estates" to senator.countryEstates,
                "dossier" to senator.dossier
            )
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
            ).executeUpdate(
                "cognomen" to senator.cognomen,
                "magistracy" to senator.magistracy,
                "family_seat" to senator.familySeat,
                "country_estates" to senator.countryEstates,
                "dossier" to senator.dossier
            )
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
            ).fetchRowStrict(
                "cognomen" to senator.cognomen,
                "magistracy" to senator.magistracy,
                "family_seat" to senator.familySeat,
                "country_estates" to senator.countryEstates,
                "dossier" to senator.dossier
            )

            senator
        }
    }
}

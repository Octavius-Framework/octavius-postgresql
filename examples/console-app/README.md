# Census

A command-line application on `client`, `client-scanner` and `migrations`, with no framework around them. It keeps
the rolls of a Roman census: citizens with their tribe, order and residence, and the censors' notae against them.

## Running it

It needs a PostgreSQL 18 database to work in. `census` on `localhost:5432` as `postgres`/`1234` is the default,
and `CENSUS_DATABASE_URL`, `CENSUS_DATABASE_USER` and `CENSUS_DATABASE_PASSWORD` change it.

```shell
./gradlew installDist
build/install/census/bin/census enroll --nomen "Marcus Tullius Cicero" --tribe Cornelia --ordo Senatorius \
    --city Roma --district Carinae --assessment 1000000 --son "Marcus Tullius Cicero Minor"
build/install/census/bin/census list --ordo Senatorius
build/install/census/bin/census demote 1 --ordo Plebeius --reason "spoke against the triumvirs"
build/install/census/bin/census show 1
```

What a command prints goes to stdout, and the libraries' log lines go to stderr.

## Where to look

| File                                             | What it shows                                                                      |
|--------------------------------------------------|------------------------------------------------------------------------------------|
| `Main.kt`                                        | The pool, the client, and startup: migrate, reload the type catalogue, scan        |
| `migrations/V1__Dynamic_dto.kt`                  | A migration written in Kotlin, creating the `dynamic_dto` type                     |
| `resources/db/migration/V2__create_citizens.sql` | A migration in SQL, with the enum, the composite and the tables                    |
| `Citizen.kt`                                     | An enum and a composite registered by annotation, and a row read into a data class |
| `Nota.kt`                                        | Two classes stored in one `dynamic_dto` column, read back as a sealed interface    |
| `Enroll.kt`                                      | A transaction plan whose second step uses the id the first one generates           |
| `ListCitizens.kt`                                | Filters as `QueryFragment`s, a `WHERE` and `LIMIT` that drop out, and a count      |
| `Transfer.kt`, `Demote.kt`                       | A transaction block that locks a row, decides on what it read, and writes          |
| `Show.kt`                                        | Reading the notae as the sealed interface                                          |

`CensusTest` runs the installed distribution against a database it creates for each test.

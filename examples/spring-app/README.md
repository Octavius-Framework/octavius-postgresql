# Curia

A Spring Boot application on `driver-spring-integration`: `OctaviusTemplate`, `@Transactional` carried by the
driver's transaction manager, and the driver's own exceptions turned into HTTP statuses. It keeps the senators of
the curia, with an enum, a composite as a column and as an array, and a composite carrying a `jsonb` map.

## Running it

It needs a PostgreSQL 18 database. `application.yml` points at `postgres` on `localhost:5432` as
`postgres`/`1234`, and `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD`
change it.

> **It drops what it creates.** At startup `CuriaSchema` drops and recreates `senators` and the types
> `magistracy`, `estate` and `dossier` in that database. Point it somewhere you do not mind losing them.

```shell
./gradlew bootRun

curl -X POST localhost:8080/senators -H "Content-Type: application/json" -d '{
  "cognomen": "Cicero",
  "magistracy": "CONSUL",
  "familySeat": { "province": "Italia", "nearestTown": "Arpinum", "iugera": 200 },
  "countryEstates": [ { "province": "Italia", "nearestTown": "Tusculum", "iugera": 120 } ],
  "dossier": { "age": 44, "agnomen": "Pater Patriae", "honours": { "supplicatio": "Catilina" } }
}'
curl localhost:8080/senators
```

## Endpoints

| Request                        | What happens                                                                         |
|--------------------------------|--------------------------------------------------------------------------------------|
| `POST /senators`               | Inserts the senator and returns the row read back; a cognomen already taken is a 409 |
| `GET /senators`                | Every senator, in a read-only transaction                                            |
| `GET /senators/{id}`           | One senator, assembled column by column with `row.get`                               |
| `POST /senators/demo-rollback` | Inserts, then throws: the transaction rolls back and the answer is a 500             |
| `POST /senators/demo-readonly` | Inserts inside `@Transactional(readOnly = true)`: the server refuses, a 403          |

## Where to look

| File                                   | What it shows                                                                                          |
|----------------------------------------|--------------------------------------------------------------------------------------------------------|
| `CuriaSchema.kt`                       | Registering the enum, the composites and a `Map` ↔ `jsonb` converter, then DDL                         |
| `domain/Models.kt`                     | The classes standing for the row and its types                                                         |
| `controller/SenatorController.kt`      | `OctaviusTemplate` under `@Transactional`, rows read whole and by column, an empty array's type stated |
| `controller/GlobalExceptionHandler.kt` | The driver's exceptions, matched by type and reason, as HTTP statuses                                  |
| `CuriaApplication.kt`                  | The runner installing the schema, and a converter for `kotlin.uuid.Uuid` paths                         |

`CuriaApplicationTest` boots the application against a real server and asserts each of these over HTTP.

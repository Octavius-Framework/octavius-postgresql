package io.github.octaviusframework.driver.spring

import io.github.octaviusframework.testsupport.TestDatabase

/**
 * `spring.datasource.url` pointing at [TestDatabase], as a constant an annotation can take.
 *
 * [TestDatabase.URL] reads its port from `TEST_DATABASE_PORT` when the tests run, so it is no constant. Here
 * Spring reads the same variable itself, through a placeholder with the same default.
 */
const val TEST_DATASOURCE_URL =
    "spring.datasource.url=jdbc:octavius://${TestDatabase.HOST}:\${TEST_DATABASE_PORT:${TestDatabase.DEFAULT_PORT}}/${TestDatabase.DATABASE}"

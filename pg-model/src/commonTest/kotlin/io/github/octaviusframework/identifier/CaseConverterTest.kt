package io.github.octaviusframework.identifier

import io.github.octaviusframework.identifier.CaseConvention.CAMEL_CASE
import io.github.octaviusframework.identifier.CaseConvention.PASCAL_CASE
import io.github.octaviusframework.identifier.CaseConvention.SNAKE_CASE_LOWER
import io.github.octaviusframework.identifier.CaseConvention.SNAKE_CASE_UPPER
import kotlin.test.Test
import kotlin.test.assertEquals

class CaseConverterTest {

    @Test
    fun `snake_case to camelCase`() {
        assertEquals("userId", CaseConverter.convert("user_id", SNAKE_CASE_LOWER, CAMEL_CASE))
        assertEquals("userAddressId", CaseConverter.convert("user_address_id", SNAKE_CASE_LOWER, CAMEL_CASE))
    }

    @Test
    fun `camelCase to snake_case`() {
        assertEquals("user_id", CaseConverter.convert("userId", CAMEL_CASE, SNAKE_CASE_LOWER))
        assertEquals("user_address_id", CaseConverter.convert("userAddressId", CAMEL_CASE, SNAKE_CASE_LOWER))
    }

    @Test
    fun `PascalCase to snake_case`() {
        assertEquals("user_id", CaseConverter.convert("UserId", PASCAL_CASE, SNAKE_CASE_LOWER))
        assertEquals("legion_status", CaseConverter.convert("LegionStatus", PASCAL_CASE, SNAKE_CASE_LOWER))
    }

    @Test
    fun `an acronym stays one word`() {
        assertEquals("xml_parser", CaseConverter.convert("XMLParser", PASCAL_CASE, SNAKE_CASE_LOWER))
        assertEquals("http_client", CaseConverter.convert("HTTPClient", PASCAL_CASE, SNAKE_CASE_LOWER))
        assertEquals("my_http_client", CaseConverter.convert("MyHTTPClient", PASCAL_CASE, SNAKE_CASE_LOWER))
    }

    @Test
    fun `a digit stays in the word it follows`() {
        assertEquals("user1_id", CaseConverter.convert("user1Id", CAMEL_CASE, SNAKE_CASE_LOWER))
        assertEquals("v1_api", CaseConverter.convert("V1Api", PASCAL_CASE, SNAKE_CASE_LOWER))
        assertEquals("b2b_customer", CaseConverter.convert("b2bCustomer", CAMEL_CASE, SNAKE_CASE_LOWER))
        assertEquals("b2bCustomer", CaseConverter.convert("b2b_customer", SNAKE_CASE_LOWER, CAMEL_CASE))
    }

    @Test
    fun `empty separators are dropped`() {
        assertEquals("userId", CaseConverter.convert("user__id", SNAKE_CASE_LOWER, CAMEL_CASE))
    }

    @Test
    fun `the enum conventions round-trip`() {
        // What EnumWithCaseConventionSerializer relies on: PascalCase out to the label and back again.
        val pascal = "OnMarch"
        val label = CaseConverter.convert(pascal, PASCAL_CASE, SNAKE_CASE_UPPER)
        assertEquals("ON_MARCH", label)
        assertEquals(pascal, CaseConverter.convert(label, SNAKE_CASE_UPPER, PASCAL_CASE))
    }

    @Test
    fun `matching conventions and empty strings are returned unchanged`() {
        assertEquals("user_id", CaseConverter.convert("user_id", SNAKE_CASE_LOWER, SNAKE_CASE_LOWER))
        assertEquals("", CaseConverter.convert("", CAMEL_CASE, SNAKE_CASE_LOWER))
    }
}

package lesson09

import lesson09.dsl.check01 as newCheck01
import lesson09.dsl.check02 as newCheck02
import lesson09.dsl.check03 as newCheck03
import lesson09.dsl.check04 as newCheck04
import lesson09.dsl.check05 as newCheck05
import lesson09.dsl.check06 as newCheck06
import lesson09.legacy.check01 as oldCheck01
import lesson09.legacy.check02 as oldCheck02
import lesson09.legacy.check03 as oldCheck03
import lesson09.legacy.check04 as oldCheck04
import lesson09.legacy.check05 as oldCheck05
import lesson09.legacy.check06 as oldCheck06
import lesson09.result.Discrepancy
import lesson09.result.ValidationResult
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue


class CheckComparisonTest {

    private fun response(
        status: Int = 200,
        headers: Map<String, List<String>> = emptyMap(),
        body: String? = null,
    ) = HttpResponse(status, headers, body)

    private fun d(path: String, expected: String, actual: String) =
        Discrepancy(path, expected, actual)

    private fun assertAgreeSuccess(old: Boolean, new: ValidationResult) {
        assertTrue(old, "legacy should pass")
        assertTrue(new.isSuccess, "DSL should pass")
        assertEquals(emptyList(), new.discrepancies)
    }

    private fun assertAgreeFailure(old: Boolean, new: ValidationResult, expectedPaths: List<String>) {
        assertFalse(old, "legacy should fail")
        assertFalse(new.isSuccess, "DSL should fail")
        assertEquals(expectedPaths, new.discrepancies.map { it.path })
    }

    // check01: status 204 + body null or ""

    @Test
    fun check01_success() {
        val r1 = response(204, body = null)
        val r2 = response(204, body = "")
        assertAgreeSuccess(oldCheck01(r1), newCheck01(r1))
        assertAgreeSuccess(oldCheck01(r2), newCheck01(r2))
    }

    @Test
    fun check01_statusBroken() {
        val r = response(200, body = null)
        assertAgreeFailure(oldCheck01(r), newCheck01(r), listOf("status"))
        assertEquals(listOf(d("status", "204", "200")), newCheck01(r).discrepancies)
    }

    @Test
    fun check01_bodyBroken() {
        val r = response(204, body = " ")
        assertAgreeFailure(oldCheck01(r), newCheck01(r), listOf("body"))
        assertEquals(listOf(d("body", "absent or empty", " ")), newCheck01(r).discrepancies)
    }

    // check02: status 200 + Content-Type startsWith application/json + body not empty

    @Test
    fun check02_success() {
        val r = response(
            200,
            mapOf("Content-Type" to listOf("application/json; charset=utf-8")),
            "not necessarily valid json",
        )
        assertAgreeSuccess(oldCheck02(r), newCheck02(r))
    }

    @Test
    fun check02_statusBroken() {
        val r = response(
            201,
            mapOf("Content-Type" to listOf("application/json")),
            "x",
        )
        assertAgreeFailure(oldCheck02(r), newCheck02(r), listOf("status"))
    }

    @Test
    fun check02_headerBroken() {
        val r = response(200, mapOf("Content-Type" to listOf("text/plain")), "x")
        assertAgreeFailure(oldCheck02(r), newCheck02(r), listOf("headers.Content-Type"))
        assertEquals(
            listOf(d("headers.Content-Type", "prefix application/json", "[text/plain]")),
            newCheck02(r).discrepancies,
        )
    }

    @Test
    fun check02_bodyBroken() {
        val rEmpty = response(200, mapOf("Content-Type" to listOf("application/json")), "")
        assertAgreeFailure(oldCheck02(rEmpty), newCheck02(rEmpty), listOf("body"))
        assertEquals(listOf(d("body", "not empty", "")), newCheck02(rEmpty).discrepancies)

        val rNull = response(200, mapOf("Content-Type" to listOf("application/json")), null)
        assertAgreeFailure(oldCheck02(rNull), newCheck02(rNull), listOf("body"))
        assertEquals(listOf(d("body", "not empty", "<missing>")), newCheck02(rNull).discrepancies)
    }

    @Test
    fun check02_missingHeader() {
        val r = response(200, body = "x")
        assertAgreeFailure(oldCheck02(r), newCheck02(r), listOf("headers.Content-Type"))
        assertEquals(
            listOf(d("headers.Content-Type", "prefix application/json", "<missing>")),
            newCheck02(r).discrepancies,
        )
    }

    // check03: status 200 + Accept has exact "application/json" and "text/html"

    @Test
    fun check03_success() {
        val r = response(
            200,
            linkedMapOf(
                "Accept" to listOf("application/json"),
                "accept" to listOf("text/html"),
            ),
        )
        assertAgreeSuccess(oldCheck03(r), newCheck03(r))
    }

    @Test
    fun check03_statusBroken() {
        val r = response(
            404,
            mapOf("Accept" to listOf("application/json", "text/html")),
        )
        assertAgreeFailure(oldCheck03(r), newCheck03(r), listOf("status"))
    }

    @Test
    fun check03_jsonAcceptMissing() {
        val r = response(200, mapOf("Accept" to listOf("text/html")))
        assertAgreeFailure(oldCheck03(r), newCheck03(r), listOf("headers.Accept"))

        assertEquals(1, newCheck03(r).discrepancies.size)
        assertEquals("headers.Accept", newCheck03(r).discrepancies.single().path)
    }

    @Test
    fun check03_htmlAcceptMissing() {
        val r = response(200, mapOf("Accept" to listOf("application/json")))
        assertAgreeFailure(oldCheck03(r), newCheck03(r), listOf("headers.Accept"))
        assertEquals(1, newCheck03(r).discrepancies.size)
    }

    @Test
    fun check03_bothMissing() {
        val r = response(200, mapOf("Accept" to listOf("text/plain")))
        assertAgreeFailure(oldCheck03(r), newCheck03(r), listOf("headers.Accept", "headers.Accept"))
        assertEquals(2, newCheck03(r).discrepancies.size)
    }

    @Test
    fun check03_combinedValueDoesNotCount() {
        val r = response(200, mapOf("Accept" to listOf("application/json,text/html")))
        assertAgreeFailure(oldCheck03(r), newCheck03(r), listOf("headers.Accept", "headers.Accept"))
    }

    // check04: status 200 + valid JSON + id present (incl. null) + name == "Ivanov"

    @Test
    fun check04_success() {
        val r1 = response(200, body = """{"id":null,"name":"Ivanov"}""")
        val r2 = response(200, body = """{"id":42,"name":"Ivanov","extra":true}""")
        assertAgreeSuccess(oldCheck04(r1), newCheck04(r1))
        assertAgreeSuccess(oldCheck04(r2), newCheck04(r2))
    }

    @Test
    fun check04_statusBroken() {
        val r = response(201, body = """{"id":1,"name":"Ivanov"}""")
        assertAgreeFailure(oldCheck04(r), newCheck04(r), listOf("status"))
    }

    @Test
    fun check04_idMissing() {
        val r = response(200, body = """{"name":"Ivanov"}""")
        assertAgreeFailure(oldCheck04(r), newCheck04(r), listOf("body.json.id"))
        assertEquals(listOf(d("body.json.id", "present", "<missing>")), newCheck04(r).discrepancies)
    }

    @Test
    fun check04_nameWrong() {
        val r = response(200, body = """{"id":1,"name":"Petrov"}""")
        assertAgreeFailure(oldCheck04(r), newCheck04(r), listOf("body.json.name"))
        assertEquals(
            listOf(d("body.json.name", "equal \"Ivanov\"", "\"Petrov\"")),
            newCheck04(r).discrepancies,
        )
    }

    @Test
    fun check04_invalidJson() {
        val r = response(200, body = "not json")
        assertAgreeFailure(oldCheck04(r), newCheck04(r), listOf("body.json"))
        assertEquals("body.json", newCheck04(r).discrepancies.single().path)
        assertEquals("valid JSON object", newCheck04(r).discrepancies.single().expected)
    }

    // check05: status 200 + valid JSON + id == 42L + active == true

    @Test
    fun check05_success() {
        val r = response(200, body = """{"id":42,"active":true}""")
        assertAgreeSuccess(oldCheck05(r), newCheck05(r))
    }

    @Test
    fun check05_statusBroken() {
        val r = response(500, body = """{"id":42,"active":true}""")
        assertAgreeFailure(oldCheck05(r), newCheck05(r), listOf("status"))
    }

    @Test
    fun check05_idWrongType() {
        val r = response(200, body = """{"id":"42","active":true}""")
        assertAgreeFailure(oldCheck05(r), newCheck05(r), listOf("body.json.id"))
        assertEquals(listOf(d("body.json.id", "equal 42", "\"42\"")), newCheck05(r).discrepancies)
    }

    @Test
    fun check05_activeWrong() {
        val r = response(200, body = """{"id":42,"active":false}""")
        assertAgreeFailure(oldCheck05(r), newCheck05(r), listOf("body.json.active"))
        assertEquals(listOf(d("body.json.active", "equal true", "false")), newCheck05(r).discrepancies)
    }

    @Test
    fun check05_invalidJson() {
        val r = response(200, body = null)
        assertAgreeFailure(oldCheck05(r), newCheck05(r), listOf("body.json"))
    }

    // check06: status 422 + Content-Type startsWith application/json + error=="invalid" + retry==false

    @Test
    fun check06_success() {
        val r = response(
            422,
            mapOf("Content-Type" to listOf("application/json")),
            """{"error":"invalid","retry":false}""",
        )
        assertAgreeSuccess(oldCheck06(r), newCheck06(r))
    }

    @Test
    fun check06_statusBroken() {
        val r = response(
            200,
            mapOf("Content-Type" to listOf("application/json")),
            """{"error":"invalid","retry":false}""",
        )
        assertAgreeFailure(oldCheck06(r), newCheck06(r), listOf("status"))
    }

    @Test
    fun check06_headerBroken() {
        val r = response(
            422,
            mapOf("Content-Type" to listOf("text/plain")),
            """{"error":"invalid","retry":false}""",
        )
        assertAgreeFailure(oldCheck06(r), newCheck06(r), listOf("headers.Content-Type"))
    }

    @Test
    fun check06_errorWrong() {
        val r = response(
            422,
            mapOf("Content-Type" to listOf("application/json")),
            """{"error":"other","retry":false}""",
        )
        assertAgreeFailure(oldCheck06(r), newCheck06(r), listOf("body.json.error"))
        assertEquals(
            listOf(d("body.json.error", "equal \"invalid\"", "\"other\"")),
            newCheck06(r).discrepancies,
        )
    }

    @Test
    fun check06_retryWrong() {
        val r = response(
            422,
            mapOf("Content-Type" to listOf("application/json")),
            """{"error":"invalid","retry":true}""",
        )
        assertAgreeFailure(oldCheck06(r), newCheck06(r), listOf("body.json.retry"))
        assertEquals(
            listOf(d("body.json.retry", "equal false", "true")),
            newCheck06(r).discrepancies,
        )
    }

    @Test
    fun check06_invalidJson() {
        val r = response(
            422,
            mapOf("Content-Type" to listOf("application/json")),
            "broken",
        )
        assertAgreeFailure(oldCheck06(r), newCheck06(r), listOf("body.json"))
    }

    @Test
    fun check06_multipleViolationsKeepOrder() {
        val r = response(
            200,
            mapOf("Content-Type" to listOf("text/html")),
            """{"error":"x","retry":true}""",
        )
        val new = newCheck06(r)
        assertFalse(oldCheck06(r))
        assertFalse(new.isSuccess)
        // order of checks in MyCheck: status, header, body.json.error, body.json.retry
        assertEquals(
            listOf("status", "headers.Content-Type", "body.json.error", "body.json.retry"),
            new.discrepancies.map { it.path },
        )
    }
}

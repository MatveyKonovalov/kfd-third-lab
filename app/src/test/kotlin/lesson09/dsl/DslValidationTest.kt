package lesson09

import lesson09.dsl.ResponseScope
import lesson09.dsl.validate
import lesson09.result.Discrepancy
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val JSON = "application/json"

private fun response(
    status: Int = 200,
    headers: Map<String, List<String>> = emptyMap(),
    body: String? = null,
) = HttpResponse(status, headers, body)

private fun d(path: String, expected: String, actual: String) = Discrepancy(path, expected, actual)

class DslValidationTest {

    // task example

    @Test
    fun taskExampleSucceedsWhenAllConditionsHold() {
        val response = response(200, mapOf("Content-Type" to listOf(JSON)), """{"id":42}""")
        val result = validate(response) {
            status { equalTo(200) }
            header("Content-Type") { startsWith("application/json") }
            body { json { field("id") { present() } } }
        }
        assertTrue(result.isSuccess)
        assertEquals(emptyList(), result.discrepancies)
    }

    @Test
    fun taskExampleReportsThreeDiscrepanciesInOrder() {
        val response = response(500, mapOf("Content-Type" to listOf("text/plain")), "{}")
        val result = validate(response) {
            status { equalTo(200) }
            header("Content-Type") { startsWith("application/json") }
            body { json { field("id") { present() } } }
        }
        assertEquals(
            listOf(
                d("status", "200", "500"),
                d("headers.Content-Type", "prefix application/json", "[text/plain]"),
                d("body.json.id", "present", "<missing>"),
            ),
            result.discrepancies,
        )
        assertEquals(
            """
            status: expected 200, actual 500
            headers.Content-Type: expected prefix application/json, actual [text/plain]
            body.json.id: expected present, actual <missing>
            """.trimIndent(),
            result.render(),
        )
    }

    // status

    @Test
    fun statusMismatchGivesOneDiscrepancy() {
        val result = validate(response(404)) { status { equalTo(200) } }
        assertFalse(result.isSuccess)
        assertEquals(listOf(d("status", "200", "404")), result.discrepancies)
    }

    // body
    @Test
    fun absentOrEmptyBodyRules() {
        fun run(source: String?) = validate(response(body = source)) { body { absentOrEmpty() } }

        assertTrue(run(null).isSuccess)
        assertTrue(run("").isSuccess)
        // whitespace is NOT empty
        assertEquals(listOf(d("body", "absent or empty", " ")), run(" ").discrepancies)
        assertEquals(listOf(d("body", "absent or empty", "x")), run("x").discrepancies)
    }

    @Test
    fun notEmptyBodyRules() {
        fun run(source: String?) = validate(response(body = source)) { body { notEmpty() } }

        assertEquals(listOf(d("body", "not empty", "<missing>")), run(null).discrepancies)
        assertEquals(1, run("").discrepancies.size)
        assertEquals("body", run("").discrepancies.single().path)
        assertTrue(run(" ").isSuccess)
        assertTrue(run("x").isSuccess)
    }

    @Test
    fun notEmptyDoesNotParseJson() {
        assertTrue(validate(response(body = "definitely not json")) { body { notEmpty() } }.isSuccess)
    }

    // headers

    @Test
    fun missingHeaderAndEmptyValueListHaveDifferentActuals() {
        val missing = validate(response()) { header("X-Test") { startsWith("a") } }
        val empty = validate(response(headers = mapOf("X-Test" to emptyList()))) {
            header("X-Test") { startsWith("a") }
        }
        assertEquals(listOf(d("headers.X-Test", "prefix a", "<missing>")), missing.discrepancies)
        assertEquals(listOf(d("headers.X-Test", "prefix a", "[]")), empty.discrepancies)
        assertNotEquals(missing.discrepancies.single().actual, empty.discrepancies.single().actual)
    }

    @Test
    fun emptyStringInsideNonEmptyListIsAValue() {
        val headers = mapOf("X-Test" to listOf("", "b"))
        assertTrue(validate(response(headers = headers)) { header("X-Test") { equalTo("") } }.isSuccess)
        assertTrue(validate(response(headers = headers)) { header("X-Test") { equalTo("b") } }.isSuccess)
        val failed = validate(response(headers = headers)) { header("X-Test") { startsWith("a") } }
        assertEquals(1, failed.discrepancies.size)
        assertEquals("headers.X-Test", failed.discrepancies.single().path)
    }

    @Test
    fun headerNameIsCaseInsensitive() {
        val headers = mapOf("content-type" to listOf(JSON))
        val result = validate(response(headers = headers)) {
            header("Content-Type") { startsWith("application/json") }
            header("CONTENT-TYPE") { equalTo(JSON) }
        }
        assertTrue(result.isSuccess)
    }

    @Test
    fun keysDifferingOnlyInCaseAreMerged() {
        val headers = linkedMapOf(
            "Accept" to listOf(JSON),
            "accept" to listOf("text/html"),
        )
        val ok = validate(response(headers = headers)) {
            header("Accept") { equalTo(JSON) }
            header("Accept") { equalTo("text/html") }
        }
        assertTrue(ok.isSuccess)

        val failed = validate(response(headers = headers)) { header("accept") { equalTo("image/png") } }
        assertEquals(
            listOf(d("headers.accept", "equal image/png", "[application/json, text/html]")),
            failed.discrepancies,
        )
    }

    @Test
    fun headerValuesAreCaseSensitiveAndNotTrimmedOrSplit() {
        fun run(value: String) = validate(response(headers = mapOf("X" to listOf(value)))) {
            header("X") { equalTo(JSON) }
        }
        assertTrue(run(JSON).isSuccess)
        assertFalse(run("Application/JSON").isSuccess)
        assertFalse(run(" application/json").isSuccess)
        assertFalse(run("application/json ").isSuccess)

        val commaSeparated = validate(response(headers = mapOf("Accept" to listOf("application/json,text/html")))) {
            header("Accept") { equalTo(JSON) }
            header("Accept") { equalTo("text/html") }
        }
        assertEquals(2, commaSeparated.discrepancies.size)
    }

    @Test
    fun startsWithUsesPlainStringStartsWith() {
        val result = validate(response(headers = mapOf("Content-Type" to listOf("application/jsonp")))) {
            header("Content-Type") { startsWith("application/json") }
        }
        assertTrue(result.isSuccess)
        val upper = validate(response(headers = mapOf("Content-Type" to listOf("APPLICATION/JSON")))) {
            header("Content-Type") { startsWith("application/json") }
        }
        assertFalse(upper.isSuccess)
    }

    @Test
    fun differentOperationsMayPassOnDifferentValues() {
        val headers = mapOf("Accept" to listOf(JSON, "text/html"))
        val result = validate(response(headers = headers)) {
            header("Accept") {
                equalTo(JSON)
                equalTo("text/html")
                startsWith("text/")
            }
        }
        assertTrue(result.isSuccess)
    }

    // custom predicate

    private fun containsJson(value: String): Boolean = value.contains("json")

    @Test
    fun predicateWorksInThreeForms() {
        val asVariable: (String) -> Boolean = { value -> value.contains("json") }
        val responses = listOf(
            response(headers = mapOf("X" to listOf("text/plain", JSON))),
            response(headers = mapOf("X" to listOf("text/plain"))),
            response(headers = mapOf("X" to emptyList())),
            response(),
        )
        for (r in responses) {
            val literal = validate(r) { header("X") { satisfies("contains json") { value -> value.contains("json") } } }
            val variable = validate(r) { header("X") { satisfies("contains json", asVariable) } }
            val reference = validate(r) { header("X") { satisfies("contains json", ::containsJson) } }
            assertEquals(literal, variable)
            assertEquals(literal, reference)
        }
        assertTrue(validate(responses[0]) { header("X") { satisfies("contains json", ::containsJson) } }.isSuccess)
        assertEquals(
            listOf(d("headers.X", "contains json", "[text/plain]")),
            validate(responses[1]) { header("X") { satisfies("contains json", ::containsJson) } }.discrepancies,
        )
    }

    @Test
    fun predicateIsNotCalledWhenHeaderOrValuesAreAbsent() {
        var calls = 0
        val predicate: (String) -> Boolean = { calls++; true }

        val missing = validate(response()) { header("X") { satisfies("any", predicate) } }
        val empty =
            validate(response(headers = mapOf("X" to emptyList()))) { header("X") { satisfies("any", predicate) } }

        assertEquals(0, calls)
        assertEquals(listOf(d("headers.X", "any", "<missing>")), missing.discrepancies)
        assertEquals(listOf(d("headers.X", "any", "[]")), empty.discrepancies)
    }

    @Test
    fun onePassingValueIsEnoughForPredicate() {
        val result = validate(response(headers = mapOf("X-Trace" to listOf("", "  ", "abc")))) {
            header("X-Trace") { satisfies("non-blank value") { it.isNotBlank() } }
        }
        assertTrue(result.isSuccess)
        val blankOnly = validate(response(headers = mapOf("X-Trace" to listOf("", "  ")))) {
            header("X-Trace") { satisfies("non-blank value") { it.isNotBlank() } }
        }
        assertEquals(1, blankOnly.discrepancies.size)
    }

    // JSON

    private fun jsonBody(source: String?, block: lesson09.dsl.JsonObjectScope.() -> Unit) =
        validate(response(body = source)) { body { json(block) } }

    @Test
    fun presentVersusMissingVersusJsonNull() {
        assertEquals(
            listOf(d("body.json.id", "present", "<missing>")),
            jsonBody("{}") { field("id") { present() } }.discrepancies,
        )
        assertTrue(jsonBody("""{"id":null}""") { field("id") { present() } }.isSuccess)
    }

    @Test
    fun equalityChecksTypeAndValue() {
        assertTrue(jsonBody("""{"id":42}""") { field("id") { equalTo(42L) } }.isSuccess)
        assertEquals(
            listOf(d("body.json.id", "equal 42", "\"42\"")),
            jsonBody("""{"id":"42"}""") { field("id") { equalTo(42L) } }.discrepancies,
        )
        assertEquals(1, jsonBody("""{"id":43}""") { field("id") { equalTo(42L) } }.discrepancies.size)
        assertEquals(1, jsonBody("""{"id":42}""") { field("id") { equalTo("42") } }.discrepancies.size)
        assertEquals(1, jsonBody("""{"a":"true"}""") { field("a") { equalTo(true) } }.discrepancies.size)
        assertEquals(1, jsonBody("""{"a":1}""") { field("a") { equalTo(true) } }.discrepancies.size)
        assertTrue(jsonBody("""{"a":false}""") { field("a") { equalTo(false) } }.isSuccess)
        assertTrue(jsonBody("""{"n":"Ivanov"}""") { field("n") { equalTo("Ivanov") } }.isSuccess)
    }

    @Test
    fun jsonNullAndMissingFieldFailEqualityWithDifferentActuals() {
        val nullValue = jsonBody("""{"n":null}""") { field("n") { equalTo("x") } }
        val missing = jsonBody("{}") { field("n") { equalTo("x") } }
        assertEquals(1, nullValue.discrepancies.size)
        assertEquals(1, missing.discrepancies.size)
        assertEquals("<missing>", missing.discrepancies.single().actual)
        assertNotEquals(missing.discrepancies.single().actual, nullValue.discrepancies.single().actual)
    }

    @Test
    fun everyFailedOperationOnAMissingFieldGivesItsOwnDiscrepancy() {
        val result = jsonBody("{}") {
            field("id") {
                present()
                equalTo(42L)
            }
        }
        assertEquals(2, result.discrepancies.size)
        assertTrue(result.discrepancies.all { it.path == "body.json.id" && it.actual == "<missing>" })
    }

    @Test
    fun extraFieldsAndHeadersDoNotInterfere() {
        val r = response(
            200,
            mapOf("Content-Type" to listOf(JSON), "X-Extra" to listOf("1")),
            """{"id":1,"extra":"yes","flag":true}""",
        )
        val result = validate(r) {
            status { equalTo(200) }
            header("Content-Type") { startsWith("application/json") }
            body { json { field("id") { equalTo(1L) } } }
        }
        assertTrue(result.isSuccess)
    }

    @Test
    fun lastDuplicateKeyWins() {
        assertTrue(jsonBody("""{"id":1,"id":42}""") { field("id") { equalTo(42L) } }.isSuccess)
    }

    @Test
    fun invalidOrNullJsonGivesExactlyOneBodyJsonDiscrepancyAndSkipsFieldChecks() {
        val sources = listOf(null, "", "{", "[1]", """{"a":{"b":1}}""", """{"a":1} tail""", "not json")
        for (source in sources) {
            val result = jsonBody(source) {
                field("id") { present(); equalTo(42L) }
                field("name") { equalTo("Ivanov") }
            }
            assertEquals(1, result.discrepancies.size, "source=$source")
            val only = result.discrepancies.single()
            assertEquals("body.json", only.path, "source=$source")
            assertEquals("valid JSON object", only.expected, "source=$source")
            assertTrue(only.actual.isNotBlank(), "source=$source")
        }
        assertEquals("<missing>", jsonBody(null) { }.discrepancies.single().actual)
    }

    @Test
    fun jsonFailureDoesNotStopOtherChecks() {
        val r = response(500, mapOf("Content-Type" to listOf("text/plain")), "{broken")
        val result = validate(r) {
            status { equalTo(200) }
            header("Content-Type") { startsWith("application/json") }
            body { json { field("id") { present() } } }
        }
        assertEquals(listOf("status", "headers.Content-Type", "body.json"), result.discrepancies.map { it.path })
    }

    // order, duplicates

    @Test
    fun discrepanciesFollowWritingOrderIncludingNestedBlocks() {
        val r = response(500, emptyMap(), """{"a":1,"b":2}""")
        val result = validate(r) {
            body {
                notEmpty()
                json {
                    field("b") { equalTo(0L) }
                    field("a") { equalTo(0L) }
                }
                absentOrEmpty()
            }
            header("H") { equalTo("v") }
            status { equalTo(200) }
        }
        assertEquals(
            listOf("body.json.b", "body.json.a", "body", "headers.H", "status"),
            result.discrepancies.map { it.path },
        )
    }

    @Test
    fun repeatedIdenticalChecksAreNotDeduplicated() {
        val result = validate(response(500)) {
            status { equalTo(200) }
            status { equalTo(200) }
            status { equalTo(200) }
        }
        assertEquals(3, result.discrepancies.size)
        assertEquals(1, result.discrepancies.toSet().size)
    }

    @Test
    fun outerReceiverIsReachableThroughExplicitLabel() {
        val result = validate(response(500, body = "x")) {
            body {
                this@validate.status { equalTo(200) }
                notEmpty()
            }
        }
        assertEquals(listOf("status"), result.discrepancies.map { it.path })
    }

    // independence of runs, input immutability

    @Test
    fun repeatedRunsAreIndependent() {
        val rules: ResponseScope.() -> Unit = {
            status { equalTo(200) }
            body { json { field("id") { equalTo(42L) } } }
        }
        val good = response(200, body = """{"id":42}""")
        val bad = response(500, body = """{"id":1}""")

        val first = validate(good, rules)
        val second = validate(bad, rules)
        val secondSnapshot = second.discrepancies.toList()
        val third = validate(good, rules)

        assertTrue(first.isSuccess)
        assertEquals(
            listOf(d("status", "200", "500"), d("body.json.id", "equal 42", "1")),
            second.discrepancies,
        )
        assertTrue(third.isSuccess)

        assertTrue(first.discrepancies.isEmpty())
        assertEquals(secondSnapshot, second.discrepancies)
    }

    @Test
    fun inputResponseIsNotModified() {
        val headers = linkedMapOf(
            "Accept" to mutableListOf(JSON, "text/html"),
            "accept" to mutableListOf("x"),
        )
        val response = HttpResponse(200, headers, """{"id":1}""")
        val headersBefore = headers.mapValues { it.value.toList() }
        val keysBefore = headers.keys.toList()

        validate(response) {
            status { equalTo(404) }
            header("Accept") { equalTo("nope") }
            body { json { field("id") { equalTo(2L) } } }
        }

        assertEquals(headersBefore, headers.mapValues { it.value.toList() })
        assertEquals(keysBefore, headers.keys.toList())
        assertEquals(200, response.status)
        assertEquals("""{"id":1}""", response.body)
    }
}

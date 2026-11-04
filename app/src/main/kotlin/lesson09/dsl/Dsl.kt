package lesson09.dsl

import lesson09.HttpResponse
import lesson09.result.Discrepancy
import lesson09.support.json.JsonParseResult
import lesson09.support.json.parseJsonObject

@DslMarker
annotation class HttpDsl

const val MISSING = "<missing>"

@HttpDsl
class ResponseScope(private val response: HttpResponse, private val errors: MutableList<Discrepancy>) {
    fun status(block: StatusScope.() -> Unit) {
        StatusScope(response.status, errors).block()
    }

    fun header(name: String, block: HeaderScope.() -> Unit) {
        HeaderScope(name, response.headers, errors).block()
    }

    fun body(block: BodyScope.() -> Unit) {
        BodyScope(response.body, errors).block()
    }
}

@HttpDsl
class StatusScope(private val status: Int, private val errors: MutableList<Discrepancy>) {
    fun equalTo(expected: Int) {
        if (status != expected) {
            errors.add(Discrepancy("status", "equal $expected", status.toString()))
        }
    }
}

@HttpDsl
class HeaderScope(
    name: String,
    headers: Map<String, List<String>>,
    private val errors: MutableList<Discrepancy>
) {
    private val path = "headers.$name"
    private val values: List<String>? = run {
        val matching = headers.filterKeys { key -> key.equals(name, ignoreCase = true) }
        if (matching.isEmpty()) null else matching.values.flatten()
    }

    private fun actualValues() = when {
        values == null -> MISSING
        else -> values.toString()
    }

    private fun fail(expected: String) {
        errors.add(Discrepancy(path, expected, actualValues()))
    }

    private fun checkCondition(errorMessage: String, condition: (String) -> Boolean) {
        if (values == null || values.none(condition)) fail(errorMessage)
    }

    fun equalTo(expected: String) {
        checkCondition("equal $expected") { it == expected }
    }

    fun startsWith(prefix: String) {
        checkCondition("prefix $prefix") { it.startsWith(prefix) }
    }

    fun satisfies(description: String, predicate: (String) -> Boolean) {
        checkCondition(description, condition = predicate)
    }
}

@HttpDsl
class BodyScope(private val body: String?, private val errors: MutableList<Discrepancy>) {
    fun absentOrEmpty() {
        if (!body.isNullOrBlank()) {
            errors.add(Discrepancy("body", "absent or empty", body))
        }
    }

    fun notEmpty() {
        if (body.isNullOrBlank()) {
            errors.add(Discrepancy("body", "not empty", body ?: MISSING))
        }
    }

    fun json(block: JsonObjectScope.() -> Unit) {
        if (body == null) {
            errors.add(Discrepancy("body.json", "valid JSON object", MISSING))
            return
        }
        val parsed = parseJsonObject(body)

        when (parsed) {
            is JsonParseResult.Failure -> errors.add(
                Discrepancy("body.json", "valid JSON object", MISSING)
            )

            is JsonParseResult.Success -> JsonObjectScope(parsed.fields, errors).block()
        }
    }
}

@HttpDsl
class JsonObjectScope(private val fields: Map<String, Any?>, private val errors: MutableList<Discrepancy>) {

    fun field(name: String, block: JsonFieldScope.() -> Unit) {
        JsonFieldScope(name, fields, errors).block()
    }
}

@HttpDsl
class JsonFieldScope(
    name: String,
    fields: Map<String, Any?>,
    private val errors: MutableList<Discrepancy>
) {
    private val path = "body.json.$name"
    private val present = fields.containsKey(name)
    private val value: Any? = fields[name]

    private fun actual(): String = if (!present) MISSING else when (value) {
        null -> "null"
        is String -> "\"$value\""
        else -> value.toString()
    }

    fun present() {
        if (!present) errors.add(Discrepancy(path, "present", MISSING))
    }

    private fun checkEqualTo(expected: Any, message: String) {
        if (!present || value != expected) {
            errors.add(Discrepancy(path, message, actual()))
        }
    }

    fun equalTo(expected: String) = checkEqualTo(expected, "equal \"$expected\"")
    fun equalTo(expected: Long) = checkEqualTo(expected, "equal $expected")
    fun equalTo(expected: Boolean) = checkEqualTo(expected, "equal $expected")
}
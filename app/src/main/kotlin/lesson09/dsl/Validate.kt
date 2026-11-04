package lesson09.dsl

import lesson09.HttpResponse
import lesson09.result.Discrepancy
import lesson09.result.ValidationResult

fun validate(response: HttpResponse, block: ResponseScope.() -> Unit): ValidationResult{
    val errors = mutableListOf<Discrepancy>()
    ResponseScope(response, errors).block()
    return ValidationResult(errors.toList())
}
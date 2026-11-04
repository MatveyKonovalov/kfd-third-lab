package lesson09.result

data class Discrepancy(
    val path: String,
    val expected: String,
    val actual: String,
)

data class ValidationResult(val discrepancies: List<Discrepancy>) {
    val isSuccess: Boolean get() = discrepancies.isEmpty()

    fun render(): String = discrepancies.joinToString("\n") {
        "${it.path}: expected ${it.expected}, actual ${it.actual}"
    }
}
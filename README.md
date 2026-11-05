# Свой DSL — проверка HTTP-ответов

Лабораторная работа по теме «Лямбды, inline, лямбды с получателем и свой DSL» (Академия МИФИ).

DSL позволяет описывать проверки статуса, заголовков и тела HTTP-ответа. Результат содержит список всех найденных расхождений (путь, ожидание, факт), а не только `true`/`false`.

## Запуск тестов

Требуется JDK 25 (`JAVA_HOME`). Gradle Wrapper уже в репозитории.

```bash
# Linux / macOS
./gradlew :app:test

# Windows (PowerShell)
.\gradlew.bat :app:test
```

Полная сборка:

```bash
./gradlew build
```

## Пример использования

```kotlin
val result = validate(response) {
    status {
        equalTo(200)
    }
    header("Content-Type") {
        startsWith("application/json")
    }
    body {
        json {
            field("id") {
                present()
            }
        }
    }
}
```

Если статус = 200, заголовок `Content-Type` начинается с `application/json`, а тело — `{"id":42}`, список расхождений пуст (`result.isSuccess == true`).

### Пример расхождений

Ответ: статус 500, `Content-Type: text/plain`, тело `{}`.

```
status: expected 200, actual 500
headers.Content-Type: expected prefix application/json, actual [text/plain]
body.json.id: expected present, actual <missing>
```

Ошибка статуса не останавливает проверку заголовка и тела. Блок `json` выполняется, даже если `Content-Type` другой.

Текстовое представление:

```kotlin
result.render()
```

## Новые проверки check01–check06

Переписаны через DSL в `lesson09.dsl.MyCheck`. Исходные функции в `lesson09.legacy` не изменялись и служат эталоном успешности.

| Функция | Условия |
|---------|---------|
| check01 | status 204; body = null или `""` |
| check02 | status 200; Content-Type startsWith `application/json`; body непустое (JSON не разбирается) |
| check03 | status 200; в Accept есть точные `application/json` и `text/html` |
| check04 | status 200; валидный JSON; поле `id` присутствует (в т.ч. null); `name == "Ivanov"` |
| check05 | status 200; валидный JSON; `id == 42L`; `active == true` |
| check06 | status 422; Content-Type startsWith `application/json`; `error == "invalid"`; `retry == false` |

## Устройство решения

### Результат проверки

```kotlin
data class Discrepancy(
    val path: String,      // status | headers.Content-Type | body | body.json | body.json.id
    val expected: String,
    val actual: String,    // значение или <missing>
)

data class ValidationResult(val discrepancies: List<Discrepancy>) {
    val isSuccess: Boolean get() = discrepancies.isEmpty()
    fun render(): String = ...
}
```

- Пустой список → успех.
- Одно неудачное условие → одно расхождение.
- Порядок = порядок записи операций (включая вложенные блоки).
- Повторный вызов одной и той же проверки даёт отдельную запись; дубликаты не удаляются.
- На каждый вызов `validate` создаётся новый накопитель. Возвращённый результат неизменяем и не зависит от последующих запусков.
- Входной `HttpResponse` и его коллекции не модифицируются.

### Блоки DSL и распределение операций

| Получатель | Доступные операции |
|------------|--------------------|
| `ResponseScope` | `status { }`, `header(name) { }`, `body { }` |
| `StatusScope` | `equalTo(Int)` |
| `HeaderScope` | `equalTo(String)`, `startsWith(String)`, `satisfies(description, predicate)` |
| `BodyScope` | `absentOrEmpty()`, `notEmpty()`, `json { }` |
| `JsonObjectScope` | `field(name) { }` |
| `JsonFieldScope` | `present()`, `equalTo(String)`, `equalTo(Long)`, `equalTo(Boolean)` |

Все scope-классы помечены одной аннотацией `@HttpDsl` с `@DslMarker`. Неявный вызов внешнего получателя из внутреннего запрещён компилятором. Явный доступ через метку остаётся возможным:

```kotlin
validate(response) {
    body {
        this@validate.status { equalTo(200) }
    }
}
```

Точка входа:

```kotlin
fun validate(response: HttpResponse, block: ResponseScope.() -> Unit): ValidationResult
```

### Правила проверки данных

**Статус и тело**
- `absentOrEmpty` проходит для `null` и `""`; строка из пробелов — не пустая.
- `notEmpty` проходит для любой строки длины > 0 (включая пробелы); для `null`/`""` — расхождение.
- Разбор JSON выполняется только при вызове блока `json`.

**Заголовки**
- Имена сравниваются без учёта регистра; значения ключей, отличающихся только регистром, объединяются.
- Значения сравниваются с учётом регистра, без trim и без разбиения по запятой.
- Операция проходит, если ей удовлетворяет хотя бы одно значение.
- Отсутствующий заголовок → actual `<missing>`; пустой список значений → `[]`.

**Пользовательский предикат**

```kotlin
header("X-Trace") {
    satisfies("non-blank value") { value -> value.isNotBlank() }
}
```

Достаточно одного значения, для которого предикат вернул `true`. Если заголовка или значений нет, предикат не вызывается.

Три способа передать одну и ту же проверку (результаты совпадают):

```kotlin
// 1. литерал лямбды
satisfies("...") { it.contains("json") }

// 2. переменная функционального типа
val pred: (String) -> Boolean = { it.contains("json") }
satisfies("...", pred)

// 3. ссылка на обычную функцию
fun containsJson(v: String) = v.contains("json")
satisfies("...", ::containsJson)
```

**JSON**
- Используется готовый `parseJsonObject` из `lesson09.support.json`.
- При `body == null` или ошибке разбора — одно расхождение `body.json`, проверки полей не выполняются.
- `present()` проходит для ключа со значением JSON `null`; не проходит для отсутствующего ключа.
- `equalTo` учитывает и тип, и значение.

### Независимость запусков

```kotlin
val rules: ResponseScope.() -> Unit = {
    status { equalTo(200) }
    body { json { field("id") { equalTo(42L) } } }
}

val first  = validate(good, rules)   // success
val second = validate(bad,  rules)   // discrepancies
val third  = validate(good, rules)   // success

// first и second не изменяются после third
```

Каждый вызов `validate` создаёт новый `MutableList<Discrepancy>` и новый `ResponseScope`. Лямбда настройки выполняется заново, но накопитель и результат принадлежат только текущему запуску.

## Структура проекта

```
app/src/main/kotlin/lesson09/
  HttpResponse.kt          — модель ответа (не изменялась)
  legacy/Checks.kt         — исходные check01–check06 (не изменялись)
  support/json/Json.kt     — готовый JSON-парсер
  dsl/
    Dsl.kt                 — scope-классы и операции
    Validate.kt            — точка входа validate(...)
    MyCheck.kt             — новые check01–check06
  result/HttpResult.kt     — Discrepancy и ValidationResult

app/src/test/kotlin/lesson09/
  LegacySmokeTest.kt       — смоук исходных проверок
  dsl/DslValidationTest.kt — тесты DSL (граничные случаи, порядок, предикаты…)
  CheckComparisonTest.kt   — сверка старых и новых check01–check06
  support/json/…           — тесты парсера
```

## Что сдавать

- реализация DSL и новые варианты check01–check06;
- исходные файлы стартового проекта без изменений;
- проходящие тесты;
- этот README.
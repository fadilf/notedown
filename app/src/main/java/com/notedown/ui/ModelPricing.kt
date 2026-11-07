package com.notedown.ui

import com.notedown.api.RouterModel
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.serialization.json.*

internal enum class ModelSortColumn { MODEL, INPUT, OUTPUT }

private fun tokenRate(value: JsonElement?): BigDecimal? =
    (value as? JsonPrimitive)?.contentOrNull?.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }

internal fun sortModels(models: List<RouterModel>, column: ModelSortColumn?, descending: Boolean): List<RouterModel> {
    if (column == null) return models
    val byName = compareBy(String.CASE_INSENSITIVE_ORDER) { model: RouterModel -> model.name }.thenBy { it.id }
    if (column == ModelSortColumn.MODEL) return models.sortedWith(if (descending) byName.reversed() else byName)
    val key = if (column == ModelSortColumn.INPUT) "prompt" else "completion"
    // Compare exact rates, before display rounding. Missing/invalid prices always sort last.
    return models.sortedWith(Comparator<RouterModel> { left, right ->
        val a = tokenRate(left.pricing?.get(key))
        val b = tokenRate(right.pricing?.get(key))
        when {
            a == null && b == null -> 0
            a == null -> 1
            b == null -> -1
            descending -> b.compareTo(a)
            else -> a.compareTo(b)
        }
    }.then(byName))
}

internal fun tokenPricePerMillion(value: JsonElement?): String {
    val rate = tokenRate(value) ?: return "Unknown"
    val price = rate.movePointRight(6)
    if (price.signum() > 0 && price < BigDecimal("0.01")) {
        // Keep two significant digits for small paid rates instead of displaying $0.00.
        val scale = (price.scale() - price.precision() + 2).coerceAtLeast(2)
        return "$" + price.setScale(scale, RoundingMode.HALF_UP).toPlainString()
    }
    return "$" + price.setScale(2, RoundingMode.HALF_UP).toPlainString()
}

internal fun pricingDetails(pricing: JsonObject): List<String> {
    val tokenRates = setOf("prompt", "completion", "input_cache_read", "input_cache_write")
    fun describe(value: JsonElement, path: String, key: String): List<String> = when (value) {
        is JsonObject -> value.flatMap { (name, child) ->
            describe(child, if (path.isEmpty()) name.replace('_', ' ') else "$path / ${name.replace('_', ' ')}", name)
        }
        is JsonArray -> value.flatMapIndexed { index, child -> describe(child, "$path ${index + 1}", key) }
        else -> listOf("$path: " + if (key in tokenRates) "${tokenPricePerMillion(value)} / 1M tokens"
            else (value as? JsonPrimitive)?.contentOrNull.orEmpty().ifEmpty { "Unknown" })
    }
    return pricing.filterKeys { it != "prompt" && it != "completion" }
        .flatMap { (key, value) -> describe(value, key.replace('_', ' '), key) }
}

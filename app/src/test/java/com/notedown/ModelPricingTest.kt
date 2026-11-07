package com.notedown

import com.notedown.ui.pricingDetails
import com.notedown.ui.tokenPricePerMillion
import com.notedown.ui.sortModels
import com.notedown.ui.ModelSortColumn
import com.notedown.api.RouterModel
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ModelPricingTest {
    private val catalog = listOf(
        RouterModel("z", "Zulu", Json.parseToJsonElement("""{"prompt":"0.000010","completion":"0"}""").jsonObject),
        RouterModel("a", "alpha", Json.parseToJsonElement("""{"prompt":"0.000002001","completion":"0.00002"}""").jsonObject),
        RouterModel("b", "Beta", Json.parseToJsonElement("""{"prompt":"0.000002004","completion":"0.00001"}""").jsonObject),
        RouterModel("missing", "Unknown rate"),
        RouterModel("invalid", "Invalid rate", Json.parseToJsonElement("""{"prompt":"-1","completion":"invalid"}""").jsonObject)
    )

    @Test fun sortsNamesCaseInsensitivelyInBothDirections() {
        assertEquals(listOf("a", "b", "invalid", "missing", "z"),
            sortModels(catalog, ModelSortColumn.MODEL, false).map { it.id })
        assertEquals(listOf("z", "missing", "invalid", "b", "a"),
            sortModels(catalog, ModelSortColumn.MODEL, true).map { it.id })
        assertEquals(catalog, sortModels(catalog, null, false))
    }

    @Test fun sortsExactPricesNumericallyWithUnknownLastInBothDirections() {
        // Alpha and Beta both display $2.00; sorting must retain their exact relative prices.
        assertEquals(listOf("a", "b", "z", "invalid", "missing"),
            sortModels(catalog, ModelSortColumn.INPUT, false).map { it.id })
        assertEquals(listOf("z", "b", "a", "invalid", "missing"),
            sortModels(catalog, ModelSortColumn.INPUT, true).map { it.id })
        assertEquals(listOf("z", "b", "a", "invalid", "missing"),
            sortModels(catalog, ModelSortColumn.OUTPUT, false).map { it.id })
        assertEquals(listOf("a", "b", "z", "invalid", "missing"),
            sortModels(catalog, ModelSortColumn.OUTPUT, true).map { it.id })
    }

    @Test fun equalPricesHaveStableNameAndIdOrder() {
        val pricing = Json.parseToJsonElement("""{"prompt":"0"}""").jsonObject
        val models = listOf(RouterModel("b", "Same", pricing), RouterModel("a", "Same", pricing))
        assertEquals(listOf("a", "b"), sortModels(models, ModelSortColumn.INPUT, true).map { it.id })
    }

    @Test fun convertsTokenRatesWithDecimalRounding() {
        assertEquals("$16.67", tokenPricePerMillion(JsonPrimitive("0.00001666667")))
        assertEquals("$0.05", tokenPricePerMillion(JsonPrimitive("0.00000005")))
        assertEquals("$0.00", tokenPricePerMillion(JsonPrimitive("0")))
        assertEquals("$1.01", tokenPricePerMillion(JsonPrimitive("0.000001005")))
    }

    @Test fun smallPaidRatesRemainVisible() {
        assertEquals("$0.0030", tokenPricePerMillion(JsonPrimitive("0.000000003")))
        assertEquals("$0.0000012", tokenPricePerMillion(JsonPrimitive("0.000000000001234")))
    }

    @Test fun unavailableOrInvalidRatesAreUnknown() {
        listOf(null, JsonNull, JsonPrimitive("invalid"), JsonPrimitive("-1"), buildJsonObject {}).forEach {
            assertEquals("Unknown", tokenPricePerMillion(it))
        }
    }

    @Test fun detailsPreserveOtherUnitsAndDescribePricingTiers() {
        val pricing = Json.parseToJsonElement("""{
            "prompt":"0.000001", "completion":"0", "web_search":"0.01",
            "input_cache_read":"0.000000003",
            "overrides":[{"min_prompt_tokens":272000,"prompt":"0.000002"}]
        }""").jsonObject
        val details = pricingDetails(pricing)
        assertEquals(listOf(
            "web search: 0.01",
            "input cache read: $0.0030 / 1M tokens",
            "overrides 1 / min prompt tokens: 272000",
            "overrides 1 / prompt: $2.00 / 1M tokens"
        ), details)
    }
}

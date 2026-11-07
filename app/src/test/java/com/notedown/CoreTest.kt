package com.notedown

import com.notedown.accessibility.insertAtSelection
import com.notedown.api.*
import com.notedown.domain.cleanupOrRaw
import com.notedown.domain.PendingTextInsertion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class CoreTest {
    private val json = Json { ignoreUnknownKeys = true }
    @Test fun overlayCompletionIsDeliveredOnlyOnce() {
        val request = PendingTextInsertion()
        request.begin(true)
        assertTrue(request.consume())
        assertFalse(request.consume())
    }
    @Test fun inAppDictationAndHistoryDoNotRequestAutomaticInsertion() {
        val request = PendingTextInsertion()
        assertFalse(request.consume())
        request.begin(true)
        request.begin(false)
        assertFalse(request.consume())
    }
    @Test fun cancelledRequestCannotPasteLater() {
        val request = PendingTextInsertion()
        request.begin(true); request.clear()
        assertFalse(request.consume())
    }
    @Test fun responseRetainsPreciseUsageAndIgnoresProviderExtensions() {
        val value = json.decodeFromString<TranscriptionResponse>("""{"text":"Hello","usage":{"cost":0.00000000123456789,"seconds":1.2,"input_tokens":4},"segments":[]}""")
        assertEquals("Hello", value.text)
        assertEquals("0.00000000123456789", reportedCost(value.usage))
    }
    @Test fun missingUsageIsUnknownRatherThanZero() { assertNull(reportedCost(json.decodeFromString<TranscriptionResponse>("""{"text":"Hi"}""").usage)) }
    @Test fun invalidResponseIsRejected() { assertThrows(SerializationException::class.java) { json.decodeFromString<TranscriptionResponse>("""{"error":"bad"}""") } }
    @Test fun selectionIsReplacedWithoutLosingSurroundingText() { assertEquals("Hello world!", insertAtSelection("Hello friend!", 6, 12, "world")) }
    @Test fun cursorInsertionKeepsExistingText() { assertEquals("abcXYZdef", insertAtSelection("abcdef", 3, 3, "XYZ")) }
    @Test fun invalidSelectionRefusesInsertion() { assertNull(insertAtSelection("abc", -1, 0, "x")); assertNull(insertAtSelection("abc", 3, 2, "x")); assertNull(insertAtSelection("abc", 4, 4, "x")) }
    @Test fun disabledCleanupNeverCallsModel() = runTest { assertEquals(null to null, cleanupOrRaw("hello", false) { error("Must not run") }) }
    @Test fun cleanupFailurePreservesRawFallback() = runTest { val result = cleanupOrRaw("hello", true) { error("Provider error") }; assertNull(result.first); assertNotNull(result.second) }
    @Test fun cleanupCancellationPropagates() = runTest { try { cleanupOrRaw("hello", true) { throw CancellationException() }; fail() } catch (_: CancellationException) { } }
    @Test fun emptyCleanupDoesNotReplaceRaw() = runTest { assertNull(cleanupOrRaw("hello", true) { "  " }.first) }
    @Test fun successfulCleanupReturnsSeparateText() = runTest { assertEquals("Hello." to null, cleanupOrRaw("hello", true) { "Hello." }) }
    @Test fun apiErrorsAreActionableAndDoNotExposeServerPayloads() {
        for ((code, fragment) in listOf(401 to "API key", 402 to "credits", 429 to "Rate limited", 503 to "temporarily", 415 to "WAV")) {
            val message = safeApiError(HttpException(Response.error<Unit>(code, "secret transcript".toResponseBody())))
            assertTrue(message.contains(fragment)); assertFalse(message.contains("secret"))
        }
    }
}

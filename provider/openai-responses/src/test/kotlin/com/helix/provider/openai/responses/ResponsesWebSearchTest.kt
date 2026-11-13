package com.helix.provider.openai.responses

import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class ResponsesWebSearchTest {
    @Test
    fun nativeSearchDoesNotBecomeLocalToolCall() {
        val stream =
            item("added", """{"type":"web_search_call","status":"in_progress"}""") +
                event("response.web_search_call.searching", """{"output_index":0}""") +
                item("done", """{"type":"web_search_call","status":"completed"}""") + completed()
        assertEquals(
            listOf(searchStatus("started"), searchStatus("completed"), ModelEvent.Completed("stop")),
            decode(stream),
        )
    }

    @Test
    fun streamedAndFinalCitationsArePersistableTextAndDeduplicated() {
        val message = message(citation())
        val stream =
            event("response.output_text.delta", """{"delta":"Answer."}""") +
                event("response.output_text.annotation.added", """{"annotation":${citation()}}""") +
                item("done", message) + completed("[$message]")
        assertEquals(
            listOf(
                ModelEvent.TextDelta("Answer."),
                ModelEvent.TextDelta("\n\n- [Source](https://example.com/a)"),
                ModelEvent.Completed("stop"),
            ),
            decode(stream),
        )
    }

    @Test
    fun finalResponseSuppliesCitationsWhenAnnotationEventsAreMissing() {
        assertEquals(
            listOf(ModelEvent.TextDelta("\n\n- [Source](https://example.com/a)"), ModelEvent.Completed("stop")),
            decode(completed("[${message(citation())}]")),
        )
    }

    @Test
    fun dangerousAndMalformedUrlsFailClosed() {
        val urls =
            listOf("javascript:alert(1)", "file:///tmp/a", "https://user:pass@example.com", "https://", "not a URL")
        for (url in urls) {
            assertEquals(listOf(protocolError()), decode(item("done", message(citation(url)))))
        }
    }

    @Test
    fun titlesCannotInjectMarkdown() {
        val source = """{"type":"url_citation","url":"https://example.com/a","title":"](javascript:evil)\n# x"}"""
        assertEquals(
            listOf(
                ModelEvent.TextDelta("\n\n- [］（javascript:evil）   x](https://example.com/a)"),
                ModelEvent.Completed("stop"),
            ),
            decode(item("done", message(source)) + completed()),
        )
    }

    @Test
    fun urlParenthesesAreEncodedForChatRendererAndSchemeIsNormalized() {
        assertEquals(
            listOf(
                ModelEvent.TextDelta("\n\n- [Source](https://example.com/a%28b%29)"),
                ModelEvent.Completed("stop"),
            ),
            decode(item("done", message(citation("HTTPS://example.com/a(b)"))) + completed()),
        )
    }

    @Test
    fun unfinishedFailedAndUnknownSearchOutcomesAreNotSuccess() {
        val added = item("added", """{"type":"web_search_call","status":"in_progress"}""")
        assertEquals(
            listOf(searchStatus("started"), searchStatus("outcome unknown"), protocolError()),
            decode(added + completed()),
        )
        for (status in listOf("failed", "incomplete", "cancelled", "unknown")) {
            assertEquals(
                listOf(searchStatus(if (status == "failed") "failed" else "outcome unknown"), protocolError()),
                decode(item("done", """{"type":"web_search_call","status":"$status"}""") + completed()),
            )
        }
        assertEquals(listOf(protocolError()), decode(event("response.web_search_call.failed", "{}") + completed()))
    }

    @Test
    fun finalResponseCanCloseSearchButNotHideItsFailure() {
        val added = item("added", """{"type":"web_search_call","status":"in_progress"}""")
        assertEquals(
            listOf(searchStatus("started"), searchStatus("completed"), ModelEvent.Completed("stop")),
            decode(added + completed("""[{"type":"web_search_call","status":"completed"}]""")),
        )
        assertEquals(
            listOf(searchStatus("failed"), protocolError()),
            decode(completed("""[{"type":"web_search_call","status":"failed"}]""")),
        )
    }

    @Test
    fun lateAnnotationIsProtocolError() {
        assertEquals(
            listOf(ModelEvent.Completed("stop"), protocolError()),
            decode(completed() + event("response.output_text.annotation.added", """{"annotation":${citation()}}""")),
        )
    }

    @Test
    fun disconnectedSearchPersistsUnknownOutcomeWithoutQueryOrId() {
        val added = item("added", """{"id":"sensitive-id","type":"web_search_call","status":"in_progress"}""")
        assertEquals(
            listOf(
                searchStatus("started"),
                searchStatus("outcome unknown"),
                ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = true),
            ),
            decode(added),
        )
    }

    private fun searchStatus(status: String): ModelEvent.TextDelta =
        ModelEvent.TextDelta("\n\nOpenAI web search: $status.\n\n")

    private fun citation(url: String = "https://example.com/a"): String =
        """{"type":"url_citation","url":"$url","title":"Source"}"""

    private fun message(annotation: String): String =
        """{"type":"message","status":"completed","content":[{"type":"output_text","annotations":[$annotation]}]}"""

    private fun completed(output: String = "[]"): String =
        event("response.completed", """{"response":{"status":"completed","output":$output}}""")

    private fun item(
        phase: String,
        item: String,
    ): String = event("response.output_item.$phase", """{"output_index":0,"item":$item}""")

    private fun event(
        type: String,
        payload: String,
    ): String = "event: $type\ndata: $payload\n\n"

    private fun protocolError(): ModelEvent = ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = false)

    private fun decode(stream: String): List<ModelEvent> {
        val decoder = ResponsesStreamDecoder()
        return stream
            .toByteArray()
            .toList()
            .chunked(7)
            .flatMap { decoder.feed(it.toByteArray()) } + decoder.finish()
    }
}

package com.helix.app.eval

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val SOURCE_QUOTE = "They are not available for registration or transfer."
private val officialSources =
    setOf("https://www.iana.org/domains/reserved", "https://www.iana.org/help/example-domains")

/** Persisted execution and the actual wire history must describe the same ordered source chain. */
internal fun verifyBrowserSearchModelEvidence(directory: File): Boolean {
    val evidence = BrowserSourceEvidence(directory)
    val chain = evidence.findChain()
    val initialAbsent =
        evidence.requests
            .firstOrNull()
            ?.toString()
            ?.contains(SOURCE_QUOTE) == false
    val answerSupported = chain != null && evidence.answerSupported(chain.source)
    val audit =
        JSONObject()
            .put("sourceUrl", chain?.source.orEmpty())
            .put("orderedSourceChain", chain != null)
            .put("sourceQuoteAbsentFromInitialRequest", initialAbsent)
            .put("answerSupported", answerSupported)
            .put("chain", chain?.json())
    File(directory, "source-audit.json").writeText(audit.toString(2))
    return chain != null && initialAbsent && answerSupported
}

private data class SourceChain(
    val source: String,
    val search: String,
    val searchPage: String,
    val searchConsumed: Int,
    val navigation: String,
    val body: String,
    val bodyConsumed: Int,
) {
    fun json(): JSONObject =
        JSONObject()
            .put("searchInternalCallId", search)
            .put("searchSnapshotInternalCallId", searchPage)
            .put("searchConsumedRequest", searchConsumed)
            .put("navigationInternalCallId", navigation)
            .put("bodySnapshotInternalCallId", body)
            .put("bodyConsumedRequest", bodyConsumed)
}

private data class SourceCall(
    val saved: JSONObject,
) {
    val id: String = saved.getString("callId")
    val name: String = saved.getString("name")
    val args: JSONObject = saved.getJSONObject("args")
    val payload: JSONObject = JSONObject(saved.getString("result"))
    val ref: String = saved.getString("turnId") + "/" + saved.getString("id")
}

private class BrowserSourceEvidence(
    private val directory: File,
) {
    private val result = JSONObject(File(directory, "result.json").readText())
    val requests: List<JSONObject> =
        directory
            .listFiles()
            .orEmpty()
            .filter { Regex("request-\\d+\\.json").matches(it.name) }
            .sortedBy {
                it.name
                    .removePrefix("request-")
                    .removeSuffix(".json")
                    .toInt()
            }.map { JSONObject(it.readText()).getJSONObject("sent") }
    private val calls =
        result
            .getJSONArray("tools")
            .objects()
            .filter { it.optString("state") == "COMPLETED" && it.optString("resultStatus") == "SUCCEEDED" }
            .map { SourceCall(it) }

    // Durable IDs and provider call IDs are different identities. Only host delivery facts join them.
    private val wireIds: Map<String, String> =
        requests
            .flatMap { request ->
                request.getJSONArray("messages").objects().filter { it.optString("role") == "tool" }
            }.filter { deliveryReference(it).isNotEmpty() && it.optString("tool_call_id").isNotEmpty() }
            .groupBy { deliveryReference(it) }
            .mapNotNull { (reference, messages) ->
                messages
                    .map { it.getString("tool_call_id") }
                    .distinct()
                    .singleOrNull()
                    ?.let { reference to it }
            }.toMap()

    fun findChain(): SourceChain? =
        calls.filter { it.name == "browser.search" }.firstNotNullOfOrNull { search ->
            val tab = search.payload.optString("tabId")
            val started = search.payload.optString("status") == "started" && tab.isNotEmpty()
            if (!started || firstSeen(search) < 1) return@firstNotNullOfOrNull null
            calls
                .filter { page ->
                    page.name == "browser.snapshot" && page.args.optString("tabId") == tab &&
                        searchOrigin(page, search) &&
                        firstSeen(page) > firstSeen(search)
                }.firstNotNullOfOrNull { page -> chainFromPage(search, page) }
        }

    private fun searchOrigin(
        page: SourceCall,
        search: SourceCall,
    ): Boolean {
        val origin = search.payload.optString("origin")
        val allowed = setOf("https://www.bing.com", "https://www.google.com", "https://duckduckgo.com")
        return origin in allowed && page.payload.optString("origin") == origin &&
            page.payload.optString("url").startsWith("$origin/")
    }

    private fun chainFromPage(
        search: SourceCall,
        page: SourceCall,
    ): SourceChain? =
        officialSources.firstNotNullOfOrNull { source ->
            val consumed = consumption(page, listOf(source))
            if (consumed < 0 ||
                !page.payload
                    .toString()
                    .replace("\\/", "/")
                    .contains(source)
            ) {
                return@firstNotNullOfOrNull null
            }
            calls
                .filter { nav ->
                    nav.name in setOf("browser.navigate", "browser.open") && nav.args.optString("url") == source &&
                        nav.payload.optString("url") == source && firstSeen(nav) > consumed &&
                        (nav.name == "browser.open" || nav.payload.optString("status") == "started")
                }.firstNotNullOfOrNull { nav -> chainFromNavigation(search, page, consumed, source, nav) }
        }

    private fun chainFromNavigation(
        search: SourceCall,
        page: SourceCall,
        consumed: Int,
        source: String,
        nav: SourceCall,
    ): SourceChain? {
        val tab = if (nav.name == "browser.open") nav.payload.optString("tabId") else nav.args.optString("tabId")
        return calls
            .filter { body ->
                body.name == "browser.snapshot" && tab.isNotEmpty() && body.args.optString("tabId") == tab &&
                    body.payload.optString("url") == source &&
                    body.payload.opt("textTruncated") == false &&
                    supportsBody(body.payload.optString("text")) && firstSeen(body) > firstSeen(nav)
            }.firstNotNullOfOrNull { body ->
                val read = consumption(body, listOf(SOURCE_QUOTE, "documentation"))
                if (read < 0) null else SourceChain(source, search.id, page.id, consumed, nav.id, body.id, read)
            }
    }

    /** A request contains outputs from prior model calls. Later first appearance proves a later model decision. */
    private fun firstSeen(call: SourceCall): Int =
        requests.indexOfFirst { request ->
            request.getJSONArray("messages").objects().any { message ->
                message.optString("role") == "assistant" &&
                    message.optJSONArray("tool_calls").objects().any { wire ->
                        val function = wire.optJSONObject("function")
                        wire.optString("id") == wireIds[call.ref] && function?.optString("name") == call.name &&
                            sameArguments(function.optString("arguments"), call.args)
                    }
            }
        }

    private fun sameArguments(
        raw: String,
        args: JSONObject,
    ): Boolean {
        val actual = JSONObject(raw)
        return actual.keys().asSequence().toSet() == args.keys().asSequence().toSet() &&
            args.keys().asSequence().all { actual.opt(it)?.toString() == args.opt(it)?.toString() }
    }

    private fun consumption(
        source: SourceCall,
        needles: List<String>,
    ): Int {
        val eligible =
            calls
                .filter { call ->
                    call.id == source.id ||
                        (
                            call.name == "tools.read_result" && call.args.optString("resultRef") == source.ref &&
                                firstSeen(call) > firstSeen(source)
                        )
                }.associateBy { wireIds[it.ref] }
        return requests.indices.firstOrNull { index ->
            index >= firstSeen(source) && firstSeen(source) >= 0 &&
                requests[index].getJSONArray("messages").objects().any { message ->
                    val call = eligible[message.optString("tool_call_id")]
                    message.optString("role") == "tool" && call != null && deliveryReference(message) == call.ref &&
                        firstSeen(call) in 0..index &&
                        deliveredText(message.optString("content"))?.let { text -> needles.all { it in text } } == true
                }
        } ?: -1
    }

    fun answerSupported(source: String): Boolean {
        val turns = result.getJSONArray("turns").objects()
        val answer =
            turns
                .singleOrNull()
                ?.takeIf { it.optString("state") == "COMPLETED" }
                ?.optString("answer")
                .orEmpty()
        val last = requests.lastIndex
        val metadata = File(directory, "response-$last.json")
        val stream = File(directory, "response-$last.sse.txt")
        if (!metadata.isFile || !stream.isFile) return false
        val capture = JSONObject(metadata.readText())
        val text =
            stream
                .readLines()
                .filter { it.startsWith("data: ") && it != "data: [DONE]" }
                .map { JSONObject(it.removePrefix("data: ")) }
                .flatMap { it.optJSONArray("choices").objects() }
                .joinToString("") { it.optJSONObject("delta")?.optString("content").orEmpty() }
        return capture.optBoolean("completeObservedDelivery") && !capture.optBoolean("captureClipped") &&
            source in answer && SOURCE_QUOTE in answer &&
            ("文档" in answer || "documentation" in answer.lowercase()) &&
            source in text && SOURCE_QUOTE in text && ("文档" in text || "documentation" in text.lowercase())
    }
}

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

private fun deliveryReference(message: JSONObject): String {
    val content = message.optString("content")
    val marker = "\nDelivery facts:"
    return if (marker in content) JSONObject(content.substringAfterLast(marker)).optString("callRef") else ""
}

private fun supportsBody(text: String): Boolean = SOURCE_QUOTE in text && "documentation" in text

/** Unwrap only a successful delivery, not metadata or a different message sharing matching words. */
private fun deliveredText(content: String): String? {
    if (!content.startsWith("[SUCCEEDED] ")) return null
    val payload = JSONObject(content.removePrefix("[SUCCEEDED] ").substringBefore("\nDelivery facts:"))
    return when {
        payload.has("preview") -> payload.getString("preview")
        payload.has("content") -> payload.getString("content")
        else -> payload.toString()
    }.replace("\\/", "/")
}

/** Synthetic evidence exercises the verifier only, never substitutes for real-model acceptance. */
internal fun exerciseBrowserSearchEvidenceVerifier() {
    val directory =
        File.createTempFile("browser-source-audit", ".test").apply {
            check(delete())
            check(mkdir())
        }
    try {
        val fixture = BrowserEvidenceFixture(directory)
        fixture.baseline()
        check(verifyBrowserSearchModelEvidence(directory))
        fixture.paginated()
        check(verifyBrowserSearchModelEvidence(directory))
        fixture.negativeCases().forEach { (name, mutate) ->
            fixture.baseline()
            mutate()
            check(!verifyBrowserSearchModelEvidence(directory)) { "Accepted $name" }
        }
    } finally {
        directory.deleteRecursively()
    }
}

private class BrowserEvidenceFixture(
    private val directory: File,
) {
    private val source = officialSources.first()
    private val searchUrl = "https://www.bing.com/search?q=IANA+example+domains"
    private val answer = "$source 文档 $SOURCE_QUOTE"

    private fun fixtureDelivery(call: JSONObject): String =
        "[SUCCEEDED] " + call.getString("result") +
            "\nDelivery facts: " + JSONObject().put("callRef", "turn/" + call.getString("id"))

    private fun tool(
        id: String,
        name: String,
        args: JSONObject,
        payload: JSONObject,
    ): JSONObject =
        JSONObject()
            .put("id", id)
            .put("callId", id)
            .put("turnId", "turn")
            .put("name", name)
            .put("args", args)
            .put("state", "COMPLETED")
            .put("resultStatus", "SUCCEEDED")
            .put("result", payload.toString())

    @Suppress("LongMethod") // Explicit synthetic wire history keeps the acceptance fixture inspectable.
    fun baseline() {
        directory.listFiles().orEmpty().forEach { check(it.delete()) }
        val tab =
            JSONObject()
                .put("tabId", "tab")
        val search =
            tool(
                "search",
                "browser.search",
                JSONObject()
                    .put("engine", "bing")
                    .put("query", "IANA example domains"),
                JSONObject()
                    .put("status", "started")
                    .put("tabId", "tab")
                    .put("url", searchUrl)
                    .put("origin", "https://www.bing.com"),
            )
        val page =
            tool(
                "page",
                "browser.snapshot",
                tab,
                JSONObject()
                    .put("url", searchUrl)
                    .put("origin", "https://www.bing.com")
                    .put("text", source),
            )
        val nav =
            tool(
                "nav",
                "browser.navigate",
                JSONObject()
                    .put("tabId", "tab")
                    .put("url", source),
                JSONObject()
                    .put("status", "started")
                    .put("url", source),
            )
        val body =
            tool(
                "body",
                "browser.snapshot",
                tab,
                JSONObject()
                    .put("url", source)
                    .put("text", "documentation $SOURCE_QUOTE")
                    .put("textTruncated", false),
            )
        val calls = listOf(search, page, nav, body)
        val result =
            JSONObject()
                .put("tools", JSONArray(calls))
                .put(
                    "turns",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("state", "COMPLETED")
                                .put("answer", answer),
                        ),
                )
        File(directory, "result.json").writeText(result.toString())
        val messages =
            JSONArray()
                .put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", "Search and read public source"),
                )
        saveRequest(0, messages)
        calls.forEachIndexed { index, call ->
            messages
                .put(
                    JSONObject()
                        .put("role", "assistant")
                        .put(
                            "tool_calls",
                            JSONArray()
                                .put(
                                    JSONObject()
                                        .put("id", "call_" + call.getString("id"))
                                        .put("type", "function")
                                        .put(
                                            "function",
                                            JSONObject()
                                                .put("name", call.getString("name"))
                                                .put("arguments", call.getJSONObject("args").toString()),
                                        ),
                                ),
                        ),
                )
            messages
                .put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", "call_" + call.getString("id"))
                        .put("content", fixtureDelivery(call)),
                )
            saveRequest(index + 1, messages)
        }
        File(directory, "response-4.json").writeText(
            JSONObject()
                .put("completeObservedDelivery", true)
                .toString(),
        )
        File(directory, "response-4.sse.txt").writeText(
            "data: " +
                JSONObject()
                    .put(
                        "choices",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put(
                                        "delta",
                                        JSONObject()
                                            .put("content", answer),
                                    ),
                            ),
                    ) + "\n\ndata: [DONE]\n",
        )
    }

    @Suppress("LongMethod") // Exercise the real resultRef paging envelope and request chronology.
    fun paginated(wrongRef: Boolean = false) {
        val resultFile = File(directory, "result.json")
        val result = JSONObject(resultFile.readText())
        val read =
            tool(
                "read",
                "tools.read_result",
                JSONObject()
                    .put("resultRef", if (wrongRef) "turn/other" else "turn/page")
                    .put("offset", 0),
                JSONObject()
                    .put("content", source),
            )
        result
            .getJSONArray("tools")
            .put(read)
        resultFile.writeText(result.toString())
        // Preserve the actual source call, but only the read-result delivery contains the URL.
        replaceDelivery("call_page", "call_page", "[SUCCEEDED] {}")
        for (index in 2..4) {
            val file = File(directory, "request-$index.json")
            val messages = JSONObject(file.readText()).getJSONObject("sent").getJSONArray("messages")
            val revised = JSONArray()
            messages.objects().forEach { message ->
                revised
                    .put(message)
                if (message.optString("tool_call_id") == "call_page" && index >= 3) {
                    revised
                        .put(
                            JSONObject()
                                .put("role", "assistant")
                                .put(
                                    "tool_calls",
                                    JSONArray()
                                        .put(
                                            JSONObject()
                                                .put("id", "call_read")
                                                .put(
                                                    "function",
                                                    JSONObject()
                                                        .put("name", "tools.read_result")
                                                        .put("arguments", read.getJSONObject("args").toString()),
                                                ),
                                        ),
                                ),
                        )
                    revised
                        .put(
                            JSONObject()
                                .put("role", "tool")
                                .put("tool_call_id", "call_read")
                                .put("content", fixtureDelivery(read)),
                        )
                }
            }
            saveRequest(index, revised)
        }
        // This extra request consumed the search page before generating navigation.
        val navigation = File(directory, "request-3.json").readText()
        val messages = JSONObject(navigation).getJSONObject("sent").getJSONArray("messages")
        val beforeNavigation = JSONArray(messages.objects().take(7))
        File(directory, "request-5.json").writeText(File(directory, "request-4.json").readText())
        File(directory, "request-4.json").writeText(navigation)
        saveRequest(3, beforeNavigation)
        File(directory, "response-4.json").renameTo(File(directory, "response-5.json"))
        File(directory, "response-4.sse.txt").renameTo(File(directory, "response-5.sse.txt"))
    }

    private fun saveRequest(
        index: Int,
        messages: JSONArray,
    ) {
        File(directory, "request-$index.json").writeText(
            JSONObject()
                .put(
                    "sent",
                    JSONObject()
                        .put("messages", messages),
                ).toString(),
        )
    }

    private fun editResult(edit: (JSONObject) -> Unit) {
        val file = File(directory, "result.json")
        val value = JSONObject(file.readText())
        edit(value)
        file.writeText(value.toString())
    }

    private fun editRequests(edit: (JSONArray) -> Unit) {
        for (index in 0..4) {
            val file = File(directory, "request-$index.json")
            val value = JSONObject(file.readText())
            edit(value.getJSONObject("sent").getJSONArray("messages"))
            file.writeText(value.toString())
        }
    }

    @Suppress("LongMethod") // Named independent mutations document the false-positive rejection matrix.
    fun negativeCases(): List<Pair<String, () -> Unit>> =
        listOf(
            "missing host call reference" to {
                editRequests { messages ->
                    messages.objects().filter { it.optString("role") == "tool" }.forEach {
                        it.put("content", it.getString("content").substringBefore("\nDelivery facts:"))
                    }
                }
            },
            "conflicting provider ID for host call reference" to {
                editRequests { messages ->
                    messages.objects().firstOrNull { it.optString("tool_call_id") == "call_body" }?.let {
                        messages.put(JSONObject(it.toString()).put("tool_call_id", "conflicting_wire_id"))
                    }
                }
            },
            "wrong read-result source reference" to {
                paginated(wrongRef = true)
            },
            "failed search" to {
                editResult {
                    it
                        .getJSONArray("tools")
                        .getJSONObject(0)
                        .put("resultStatus", "FAILED")
                }
            },
            "no search-page snapshot" to {
                editResult {
                    it
                        .getJSONArray("tools")
                        .getJSONObject(1)
                        .put("name", "browser.tabs")
                }
            },
            "wrong search tab" to {
                editResult {
                    it
                        .getJSONArray("tools")
                        .getJSONObject(1)
                        .getJSONObject("args")
                        .put("tabId", "other")
                }
            },
            "initial remembered quote" to {
                editRequests {
                    it
                        .getJSONObject(0)
                        .put("content", SOURCE_QUOTE)
                }
            },
            "foreign source" to {
                changeBody("url", "https://example.com/")
            },
            "missing source text" to {
                changeBody("text", "")
            },
            "truncated source" to {
                changeBody("textTruncated", true)
            },
            "failed source" to {
                editResult {
                    it
                        .getJSONArray("tools")
                        .getJSONObject(3)
                        .put("resultStatus", "FAILED")
                }
            },
            "wrong body call ID" to {
                replaceDelivery(
                    "call_body",
                    "call_other",
                    "[SUCCEEDED] " +
                        JSONObject()
                            .put("text", "documentation $SOURCE_QUOTE"),
                )
            },
            "missing body consumption" to {
                replaceDelivery("call_body", "call_body", "[SUCCEEDED] {}")
            },
            "search URL only from unrelated tool" to {
                replaceDelivery(
                    "call_page",
                    "call_other",
                    "[SUCCEEDED] " +
                        JSONObject()
                            .put("text", source),
                )
            },
            "answer only URL" to {
                editResult {
                    it
                        .getJSONArray("turns")
                        .getJSONObject(0)
                        .put("answer", source)
                }
            },
            "answer not generated by final request" to {
                File(directory, "response-4.sse.txt").writeText("data: [DONE]\n")
            },
            "navigation before search reading" to {
                swapRequests(2, 3)
            },
        )

    private fun changeBody(
        key: String,
        value: Any,
    ) = editResult {
        val call = it.getJSONArray("tools").getJSONObject(3)
        call
            .put(
                "result",
                JSONObject(call.getString("result"))
                    .put(key, value)
                    .toString(),
            )
    }

    private fun replaceDelivery(
        id: String,
        replacement: String,
        text: String,
    ) = editRequests { messages ->
        messages.objects().filter { it.optString("tool_call_id") == id }.forEach {
            it
                .put("tool_call_id", replacement)
                .put("content", text + "\nDelivery facts: " + JSONObject().put("callRef", deliveryReference(it)))
        }
    }

    private fun swapRequests(
        first: Int,
        second: Int,
    ) {
        val a = File(directory, "request-$first.json")
        val b = File(directory, "request-$second.json")
        val content = a.readText()
        a.writeText(b.readText())
        b.writeText(content)
    }
}

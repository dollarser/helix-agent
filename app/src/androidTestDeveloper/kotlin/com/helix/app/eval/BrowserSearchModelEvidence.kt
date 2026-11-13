package com.helix.app.eval

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI

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
    val answerSupported = chain != null && evidence.answerSupported(chain)
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
    val navigationStatus: String = "observed_navigation",
) {
    fun json(): JSONObject =
        JSONObject()
            .put("searchInternalCallId", search)
            .put("searchSnapshotInternalCallId", searchPage)
            .put("searchConsumedRequest", searchConsumed)
            .put("navigationInternalCallId", navigation)
            .put("bodySnapshotInternalCallId", body)
            .put("bodyConsumedRequest", bodyConsumed)
            .put("navigationStatus", navigationStatus)
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
    private val savedCalls = result.getJSONArray("tools").objects()
    private val turns = result.getJSONArray("turns").objects()
    private val calls =
        savedCalls
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
    ): SourceChain? {
        val direct =
            calls.filter { it.name in setOf("browser.navigate", "browser.open") }.firstNotNullOfOrNull { nav ->
                val consumed = consumption(page, listOf(nav.args.optString("url")))
                if (validDirectNavigation(
                        page,
                        nav,
                        consumed,
                    )
                ) {
                    chainFromNavigation(search, page, consumed, nav)
                } else {
                    null
                }
            }
        return direct ?: chainFromClick(search, page)
    }

    private fun validDirectNavigation(
        page: SourceCall,
        nav: SourceCall,
        consumed: Int,
    ): Boolean {
        val requested = nav.args.optString("url")
        val sourceMatches =
            officialNavigation(requested) &&
                page.payload
                    .toString()
                    .replace("\\/", "/")
                    .contains(requested)
        val navigationMatches =
            nav.payload.optString("url") == requested &&
                (nav.name == "browser.open" || nav.payload.optString("status") == "started")
        return sourceMatches && navigationMatches && consumed >= 0 && firstSeen(nav) > consumed
    }

    private fun chainFromClick(
        search: SourceCall,
        page: SourceCall,
    ): SourceChain? {
        val tab = page.args.optString("tabId")
        val observations =
            listOf(page) +
                calls.filter { find ->
                    find.name == "browser.find" && find.args.optString("tabId") == tab &&
                        find.payload.opt("observationExpired") == false && firstSeen(find) > firstSeen(page) &&
                        uninterruptedObservation(page, find, tab)
                }
        val unknownClicks = savedCalls.filter(::unknownClick).map { SourceCall(it) }
        return (calls + unknownClicks)
            .filter { click ->
                click.name == "browser.click" && click.args.optString("tabId") == tab &&
                    (click.payload.optString("status") == "performed" || unknownClick(click.saved))
            }.firstNotNullOfOrNull { click ->
                val token = click.args.optString("token")
                observations.firstNotNullOfOrNull { observation ->
                    val consumed = consumption(observation, listOf(token))
                    val observedToken = observedToken(observation, token)
                    val deliveredBeforeClick = token.isNotEmpty() && consumed >= 0 && firstSeen(click) > consumed
                    if (observedToken && deliveredBeforeClick && uninterruptedObservation(observation, click, tab)) {
                        chainFromNavigation(search, page, consumed, click)
                    } else {
                        null
                    }
                }
            }
    }

    private fun unknownClick(saved: JSONObject): Boolean {
        val uncertain = saved.optString("state") == "NEEDS_REVIEW" && saved.optString("resultStatus") == "NEEDS_REVIEW"
        val payload = runCatching { JSONObject(saved.optString("result")) }.getOrNull()
        return saved.optString("name") == "browser.click" && uncertain &&
            payload?.opt("requiresReview") == true && payload.opt("sideEffectFree") == false
    }

    private fun completedRecovery(): Boolean =
        turns.size == 2 && turns.first().optString("state") == "INTERRUPTED" &&
            turns.first().optString("errorCode") == "RECOVERY_INSPECTION_FINISHED" &&
            turns.last().optString("state") == "COMPLETED"

    private fun recoveredSource(
        nav: SourceCall,
        body: SourceCall,
    ): Boolean {
        if (!unknownClick(nav.saved)) return true
        return if (completedRecovery() && !replayedClick(nav)) {
            val original = turns.first().optString("id")
            val recovery = turns.last().optString("id")
            original.isNotEmpty() && recovery.isNotEmpty() && original != recovery &&
                nav.saved.optString("turnId") == original && body.saved.optString("turnId") == recovery
        } else {
            false
        }
    }

    private fun replayedClick(nav: SourceCall): Boolean =
        savedCalls.drop(savedCalls.indexOf(nav.saved) + 1).any {
            it.optString("name") == "browser.click" &&
                it.optJSONObject("args")?.optString("tabId") == nav.args.optString("tabId")
        }

    private fun observedToken(
        observation: SourceCall,
        token: String,
    ): Boolean =
        listOf("nodes", "links", "matches").any { key ->
            observation.payload
                .optJSONArray(key)
                .objects()
                .any { it.optString("token") == token }
        }

    private fun uninterruptedObservation(
        observation: SourceCall,
        next: SourceCall,
        tab: String,
    ): Boolean {
        if (!uninterruptedNavigation(observation, next, tab)) return false
        val start = savedCalls.indexOf(observation.saved)
        val end = savedCalls.indexOf(next.saved)
        return savedCalls.subList(start + 1, end).none {
            it.optString("name") == "browser.snapshot" && it.optJSONObject("args")?.optString("tabId") == tab
        }
    }

    private fun officialNavigation(url: String): Boolean =
        runCatching {
            val uri = URI(url)
            uri.scheme == "https" && uri.host == "www.iana.org" &&
                uri.rawUserInfo == null && uri.port == -1
        }.getOrDefault(false)

    private fun chainFromNavigation(
        search: SourceCall,
        page: SourceCall,
        consumed: Int,
        nav: SourceCall,
    ): SourceChain? {
        val tab = if (nav.name == "browser.open") nav.payload.optString("tabId") else nav.args.optString("tabId")
        return calls
            .filter { body ->
                body.name == "browser.snapshot" && tab.isNotEmpty() && body.args.optString("tabId") == tab &&
                    body.payload.optString("url") in officialSources &&
                    body.payload.opt("textTruncated") == false &&
                    supportsBody(body.payload.optString("text")) && firstSeen(body) > firstSeen(nav) &&
                    uninterruptedNavigation(nav, body, tab)
            }.firstNotNullOfOrNull { body ->
                val source = body.payload.getString("url")
                val read = consumption(body, listOf(SOURCE_QUOTE, "documentation"))
                val urlRead = consumption(body, listOf(source))
                if (read < 0 || urlRead < 0 || !recoveredSource(nav, body)) {
                    null
                } else {
                    val status =
                        if (unknownClick(
                                nav.saved,
                            )
                        ) {
                            "unknown_click_reconciled_by_recovery_observation"
                        } else {
                            "observed_navigation"
                        }
                    SourceChain(source, search.id, page.id, consumed, nav.id, body.id, maxOf(read, urlRead), status)
                }
            }
    }

    /** Inspect every persisted call, including failed actions whose effects may be uncertain. */
    private fun uninterruptedNavigation(
        nav: SourceCall,
        body: SourceCall,
        tab: String,
    ): Boolean {
        val start = savedCalls.indexOf(nav.saved)
        val end = savedCalls.indexOf(body.saved)
        if (start < 0 || end <= start) return false
        val retargeting =
            setOf(
                "browser.navigate",
                "browser.back",
                "browser.forward",
                "browser.reload",
                "browser.click",
                "browser.type",
            )
        return savedCalls.subList(start + 1, end).none { call ->
            val name = call.optString("name")
            val args = call.optJSONObject("args")
            val payload = runCatching { JSONObject(call.optString("result")) }.getOrNull()
            (name in retargeting && args?.optString("tabId") == tab) ||
                (name in setOf("browser.open", "browser.search") && payload?.optString("tabId") == tab)
        }
    }

    /** A request contains outputs from prior model calls. Later first appearance proves a later model decision. */
    private fun firstSeen(call: SourceCall): Int {
        val delivered = deliveryFirstSeen(call)
        val hasDelivery =
            requests.any { request ->
                request.getJSONArray("messages").objects().any { deliveryReference(it) == call.ref }
            }
        return if (delivered >= 0 || !unknownClick(call.saved) || hasDelivery) delivered else durableFirstSeen(call)
    }

    private fun deliveryFirstSeen(call: SourceCall): Int =
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

    /** Recovery may omit the uncertain call from later wire history. Never invent its provider ID. */
    private fun durableFirstSeen(call: SourceCall): Int {
        val wireId = persistedWireId(call) ?: return -1
        val matches =
            requests.indices.filter { index ->
                capturedToolCalls(index).count { wire ->
                    wire.optString("id") == wireId && wire.optString("name") == call.name &&
                        sameArguments(wire.optString("arguments"), call.args)
                } == 1
            }
        return matches.singleOrNull()?.plus(1) ?: -1
    }

    @Suppress("ReturnCount") // Fail closed at each independent persisted-evidence boundary.
    private fun persistedWireId(call: SourceCall): String? {
        val file = File(directory, "persisted-batches.json")
        if (!file.isFile) return null
        val batches = runCatching { JSONArray(file.readText()).objects() }.getOrNull() ?: return null
        val bindings =
            batches
                .filter { it.optString("turnId") == call.saved.optString("turnId") }
                .flatMap { batch ->
                    batch
                        .optJSONArray("calls")
                        .objects()
                        .filter { it.optString("localId") == call.id }
                        .map { batch to it }
                }
        val (batch, binding) = bindings.singleOrNull() ?: return null
        val valid =
            batch.optString("messageId").isNotEmpty() && binding.optString("name") == call.name &&
                sameArguments(binding.optString("arguments"), call.args)
        return binding.optString("id").takeIf { valid && it.isNotEmpty() }
    }

    @Suppress("ReturnCount") // Reject missing, clipped and incomplete captures before decoding them.
    private fun capturedToolCalls(index: Int): List<JSONObject> {
        val metadata = File(directory, "response-$index.json")
        val stream = File(directory, "response-$index.sse.txt")
        if (!metadata.isFile || !stream.isFile) return emptyList()
        val capture = JSONObject(metadata.readText())
        if (!capture.optBoolean("completeObservedDelivery") || capture.optBoolean("captureClipped")) return emptyList()
        val choices =
            stream
                .readLines()
                .filter { it.startsWith("data: ") && it != "data: [DONE]" }
                .flatMap { JSONObject(it.removePrefix("data: ")).optJSONArray("choices").objects() }
        val selected = choices.filter { it.optInt("index", 0) == 0 }
        if (selected.lastOrNull { !it.isNull("finish_reason") }?.optString("finish_reason") !=
            "tool_calls"
        ) {
            return emptyList()
        }
        val fragments = selected.flatMap { it.optJSONObject("delta")?.optJSONArray("tool_calls").objects() }
        return fragments
            .groupBy { it.optInt("index", -1) }
            .filterKeys { it >= 0 }
            .values
            .map(::joinToolFragments)
    }

    private fun joinToolFragments(parts: List<JSONObject>): JSONObject =
        JSONObject()
            .put("id", parts.joinToString("") { (it.opt("id") as? String).orEmpty() })
            .put("name", parts.joinToString("") { (it.optJSONObject("function")?.opt("name") as? String).orEmpty() })
            .put(
                "arguments",
                parts.joinToString("") { (it.optJSONObject("function")?.opt("arguments") as? String).orEmpty() },
            )

    private fun sameArguments(
        raw: String,
        args: JSONObject,
    ): Boolean {
        val actual = runCatching { JSONObject(raw) }.getOrNull() ?: return false
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

    fun answerSupported(chain: SourceChain): Boolean {
        val (capture, text) = capturedAnswer() ?: return false
        val singleCompleted = turns.size == 1 && turns.single().optString("state") == "COMPLETED"
        val recovered =
            completedRecovery() &&
                chain.navigationStatus == "unknown_click_reconciled_by_recovery_observation"
        return (singleCompleted || recovered) &&
            capture.optBoolean("completeObservedDelivery") && !capture.optBoolean("captureClipped") &&
            supportsAnswer(turns.last().optString("answer"), chain.source) && supportsAnswer(text, chain.source)
    }

    private fun supportsAnswer(
        text: String,
        source: String,
    ): Boolean = source in text && SOURCE_QUOTE in text && ("文档" in text || "documentation" in text.lowercase())

    private fun capturedAnswer(): Pair<JSONObject, String>? {
        val last = requests.lastIndex
        val metadata = File(directory, "response-$last.json")
        val stream = File(directory, "response-$last.sse.txt")
        if (!metadata.isFile || !stream.isFile) return null
        val text =
            stream
                .readLines()
                .filter { it.startsWith("data: ") && it != "data: [DONE]" }
                .map { JSONObject(it.removePrefix("data: ")) }
                .flatMap { it.optJSONArray("choices").objects() }
                .joinToString("") { it.optJSONObject("delta")?.optString("content").orEmpty() }
        return JSONObject(metadata.readText()) to text
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
        fixture.redirect()
        check(verifyBrowserSearchModelEvidence(directory))
        fixture.redirectNegativeCases().forEach { (name, mutate) ->
            fixture.redirect()
            mutate()
            check(!verifyBrowserSearchModelEvidence(directory)) { "Accepted $name" }
        }
        fixture.click()
        check(verifyBrowserSearchModelEvidence(directory))
        fixture.click(useFind = true)
        check(verifyBrowserSearchModelEvidence(directory))
        fixture.click(useNodes = true)
        check(verifyBrowserSearchModelEvidence(directory))
        fixture.clickNegativeCases().forEach { (name, mutate) ->
            fixture.click()
            mutate()
            check(!verifyBrowserSearchModelEvidence(directory)) { "Accepted $name" }
        }
        fixture.recoveredClick()
        check(verifyBrowserSearchModelEvidence(directory))
        fixture.recoveryNegativeCases().forEach { (name, mutate) ->
            fixture.recoveredClick()
            mutate()
            check(!verifyBrowserSearchModelEvidence(directory)) { "Accepted $name" }
        }
        fixture.undeliveredRecoveredClick()
        check(verifyBrowserSearchModelEvidence(directory))
        fixture.batchNegativeCases().forEach { (name, mutate) ->
            fixture.undeliveredRecoveredClick()
            mutate()
            check(!verifyBrowserSearchModelEvidence(directory)) { "Accepted $name" }
        }
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

    private fun fixtureDelivery(call: JSONObject): String =
        "[" + call.getString("resultStatus") + "] " + call.getString("result") +
            "\nDelivery facts: " + JSONObject().put("callRef", call.getString("turnId") + "/" + call.getString("id"))

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
    fun baseline(
        requested: String = source,
        finalSource: String = source,
        open: Boolean = false,
        bodyTab: String = "tab",
        interveningNavigation: Boolean = false,
    ) {
        val answer = "$finalSource 文档 $SOURCE_QUOTE"
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
                    .put("text", requested),
            )
        val nav =
            tool(
                "nav",
                if (open) "browser.open" else "browser.navigate",
                JSONObject()
                    .put("tabId", "tab")
                    .put("url", requested),
                JSONObject()
                    .put("status", "started")
                    .put("tabId", "tab")
                    .put("url", requested),
            )
        val body =
            tool(
                "body",
                "browser.snapshot",
                JSONObject().put("tabId", bodyTab),
                JSONObject()
                    .put("url", finalSource)
                    .put("text", "documentation $SOURCE_QUOTE")
                    .put("textTruncated", false),
            )
        if (open) nav.getJSONObject("args").remove("tabId")
        val intervening =
            tool(
                "retarget",
                "browser.navigate",
                JSONObject().put("tabId", "tab").put("url", "https://example.com/"),
                JSONObject().put("status", "started").put("url", "https://example.com/"),
            )
        val calls =
            if (interveningNavigation) {
                listOf(
                    search,
                    page,
                    nav,
                    intervening,
                    body,
                )
            } else {
                listOf(search, page, nav, body)
            }
        val result =
            JSONObject()
                .put("tools", JSONArray(calls))
                .put(
                    "turns",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("id", "turn")
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
        File(directory, "response-${calls.size}.json").writeText(
            JSONObject()
                .put("completeObservedDelivery", true)
                .toString(),
        )
        File(directory, "response-${calls.size}.sse.txt").writeText(
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

    fun redirect() =
        baseline(
            requested = "https://www.iana.org/domains/example",
            finalSource = officialSources.last(),
            open = true,
        )

    fun redirectNegativeCases(): List<Pair<String, () -> Unit>> =
        listOf(
            "redirect wrong body tab" to {
                baseline("https://www.iana.org/domains/example", officialSources.last(), open = true, bodyTab = "other")
            },
            "redirect foreign final URL" to {
                baseline("https://www.iana.org/domains/example", "https://example.com/", open = true)
            },
            "redirect missing consumed initial link" to {
                replaceDelivery("call_page", "call_page", "[SUCCEEDED] {}")
            },
            "redirect intervening navigation" to {
                baseline(
                    "https://www.iana.org/domains/example",
                    officialSources.last(),
                    open = true,
                    interveningNavigation = true,
                )
            },
            "redirect foreign initial link" to {
                baseline("https://www.iana.org.attacker.example/domains/example", officialSources.last(), open = true)
            },
            "redirect navigation result differs from request" to {
                editResult {
                    val nav = it.getJSONArray("tools").getJSONObject(2)
                    val payload = JSONObject(nav.getString("result")).put("url", officialSources.last())
                    nav.put("result", payload.toString())
                }
            },
            "redirect answer cites initial alias only" to {
                editResult {
                    it
                        .getJSONArray("turns")
                        .getJSONObject(0)
                        .put("answer", "https://www.iana.org/domains/example 文档 $SOURCE_QUOTE")
                }
            },
        )

    fun click(
        useFind: Boolean = false,
        useNodes: Boolean = false,
    ) {
        baseline()
        rewriteCalls { calls ->
            val token = JSONObject().put("token", "observed-link-token").put("role", "link")
            val page = calls[1]
            page.put(
                "result",
                JSONObject(
                    page.getString("result"),
                ).put(if (useNodes) "nodes" else "links", JSONArray().put(token)).toString(),
            )
            val click = calls[2]
            click.put("name", "browser.click")
            click.put("args", JSONObject().put("tabId", "tab").put("token", "observed-link-token"))
            click.put("result", JSONObject().put("status", "performed").toString())
            if (useFind) {
                page.put("result", JSONObject(page.getString("result")).apply { remove("links") }.toString())
                calls.add(
                    2,
                    tool(
                        "find",
                        "browser.find",
                        JSONObject().put("tabId", "tab").put("query", "IANA"),
                        JSONObject().put("matches", JSONArray().put(token)).put("observationExpired", false),
                    ),
                )
            }
        }
    }

    @Suppress("LongMethod") // Named independent mutations keep click provenance failures inspectable.
    fun clickNegativeCases(): List<Pair<String, () -> Unit>> =
        listOf(
            "click missing consumed token" to { replaceDelivery("call_page", "call_page", "[SUCCEEDED] {}") },
            "click foreign token" to {
                rewriteCalls { calls -> calls[2].getJSONObject("args").put("token", "foreign-token") }
            },
            "click wrong tab" to {
                rewriteCalls { calls -> calls[2].getJSONObject("args").put("tabId", "other") }
            },
            "click unperformed" to {
                rewriteCalls { calls -> calls[2].put("result", JSONObject().put("status", "stale").toString()) }
            },
            "find token from wrong tab" to {
                click(useFind = true)
                rewriteCalls { calls -> calls[2].getJSONObject("args").put("tabId", "other") }
            },
            "find expired observation" to {
                click(useFind = true)
                rewriteCalls { calls ->
                    val find = calls[2]
                    find.put("result", JSONObject(find.getString("result")).put("observationExpired", true).toString())
                }
            },
            "click uncertain intervening action" to {
                rewriteCalls { calls ->
                    calls.add(
                        3,
                        tool(
                            "uncertain",
                            "browser.navigate",
                            JSONObject().put("tabId", "tab").put("url", "https://example.com/"),
                            JSONObject(),
                        ).put("state", "NEEDS_REVIEW").put("resultStatus", "NEEDS_REVIEW"),
                    )
                }
            },
        )

    fun recoveredClick() {
        click()
        rewriteCalls { calls ->
            calls[2]
                .put("state", "NEEDS_REVIEW")
                .put("resultStatus", "NEEDS_REVIEW")
                .put("result", JSONObject().put("requiresReview", true).put("sideEffectFree", false).toString())
            calls[3].put("turnId", "recovery")
        }
        editResult { result ->
            val final = result.getJSONArray("turns").getJSONObject(0).put("id", "recovery")
            val original =
                JSONObject()
                    .put("id", "turn")
                    .put("state", "INTERRUPTED")
                    .put("errorCode", "RECOVERY_INSPECTION_FINISHED")
                    .put("answer", "")
            result.put("turns", JSONArray().put(original).put(final))
        }
    }

    fun recoveryNegativeCases(): List<Pair<String, () -> Unit>> =
        listOf(
            "unfinished recovery" to {
                editResult { it.getJSONArray("turns").getJSONObject(1).put("state", "RUNNING") }
            },
            "wrong recovery terminal reason" to {
                editResult { it.getJSONArray("turns").getJSONObject(0).put("errorCode", "USER_CANCELLED") }
            },
            "recovered click replayed" to {
                rewriteCalls { calls ->
                    val replay =
                        JSONObject(calls[2].toString())
                            .put("id", "replay")
                            .put("callId", "replay")
                            .put("turnId", "recovery")
                    calls.add(3, replay)
                }
            },
            "wrong recovery source turn" to {
                rewriteCalls { calls -> calls[3].put("turnId", "turn") }
            },
        )

    fun undeliveredRecoveredClick() {
        recoveredClick()
        val call = JSONObject(File(directory, "result.json").readText()).getJSONArray("tools").getJSONObject(2)
        val binding =
            JSONObject()
                .put("id", "call_nav")
                .put("localId", call.getString("callId"))
                .put("name", "browser.click")
                .put("arguments", call.getJSONObject("args").toString())
        val batch =
            JSONObject()
                .put("turnId", "turn")
                .put("messageId", "assistant-batch")
                .put("calls", JSONArray().put(binding))
        File(directory, "persisted-batches.json").writeText(JSONArray().put(batch).toString())
        for (index in 0..4) {
            val request = JSONObject(File(directory, "request-$index.json").readText())
            val messages =
                request.getJSONObject("sent").getJSONArray("messages").objects().filterNot {
                    it.optString("tool_call_id") == "call_nav" ||
                        it.optJSONArray("tool_calls").objects().any { wire -> wire.optString("id") == "call_nav" }
                }
            saveRequest(index, JSONArray(messages))
        }
        File(directory, "response-2.json").writeText(JSONObject().put("completeObservedDelivery", true).toString())
        val function = JSONObject().put("name", "browser.click").put("arguments", binding.getString("arguments"))
        val wire = JSONObject().put("index", 0).put("id", "call_nav").put("function", function)
        val choice =
            JSONObject()
                .put("index", 0)
                .put("delta", JSONObject().put("tool_calls", JSONArray().put(wire)))
                .put("finish_reason", "tool_calls")
        File(directory, "response-2.sse.txt").writeText(
            "data: " + JSONObject().put("choices", JSONArray().put(choice)) + "\n\ndata: [DONE]\n",
        )
    }

    fun batchNegativeCases(): List<Pair<String, () -> Unit>> =
        listOf(
            "missing durable batch" to { check(File(directory, "persisted-batches.json").delete()) },
            "conflicting durable batch" to {
                val file = File(directory, "persisted-batches.json")
                val batches = JSONArray(file.readText())
                val other = JSONObject(batches.getJSONObject(0).toString()).put("messageId", "conflicting-batch")
                other.getJSONArray("calls").getJSONObject(0).put("id", "conflicting-wire-id")
                file.writeText(batches.put(other).toString())
            },
            "wrong captured click ID" to { replaceClickStream("call_nav", "wrong_id") },
            "wrong captured click args" to { replaceClickStream("observed-link-token", "foreign-token") },
            "clipped click response" to {
                File(directory, "response-2.json").writeText(
                    JSONObject().put("completeObservedDelivery", true).put("captureClipped", true).toString(),
                )
            },
        )

    private fun replaceClickStream(
        before: String,
        after: String,
    ) {
        val file = File(directory, "response-2.sse.txt")
        file.writeText(file.readText().replace(before, after))
    }

    /** Rebuild matching persisted calls and wire deliveries so negative fixtures fail on provenance. */
    private fun rewriteCalls(edit: (MutableList<JSONObject>) -> Unit) {
        val resultFile = File(directory, "result.json")
        val result = JSONObject(resultFile.readText())
        val calls = result.getJSONArray("tools").objects().toMutableList()
        val oldLast = calls.size
        edit(calls)
        result.put("tools", JSONArray(calls))
        resultFile.writeText(result.toString())
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "Search and read public source"))
        saveRequest(0, messages)
        calls.forEachIndexed { index, call ->
            val function =
                JSONObject()
                    .put(
                        "name",
                        call.getString("name"),
                    ).put("arguments", call.getJSONObject("args").toString())
            val wire = JSONObject().put("id", "call_" + call.getString("id")).put("function", function)
            messages.put(JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(wire)))
            messages.put(
                JSONObject()
                    .put("role", "tool")
                    .put("tool_call_id", "call_" + call.getString("id"))
                    .put("content", fixtureDelivery(call)),
            )
            saveRequest(index + 1, messages)
        }
        if (calls.size != oldLast) {
            for (suffix in listOf("json", "sse.txt")) {
                check(
                    File(
                        directory,
                        "response-$oldLast.$suffix",
                    ).renameTo(File(directory, "response-${calls.size}.$suffix")),
                )
            }
        }
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

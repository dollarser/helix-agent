package com.helix.smoke

import org.json.JSONObject

/** Correlate canonical discovery results with this request's declarations, without reproducing aliases. */
internal object ReleaseDeclaredTools {
    fun searchName(request: JSONObject): String = select(request, "tools_search", "Search user-enabled tools")

    fun targetName(
        request: JSONObject,
        canonicalName: String,
    ): String? {
        val messages = request.optJSONArray("messages")
        val descriptions =
            (0 until (messages?.length() ?: 0))
                .map { messages!!.getJSONObject(it) }
                .filter { it.optString("role") == "tool" }
                .mapNotNull { message ->
                    val content = message.optString("content")
                    if (!content.startsWith("[SUCCEEDED] ")) return@mapNotNull null
                    val payload = content.removePrefix("[SUCCEEDED] ").substringBefore("\nDelivery facts:")
                    val result = runCatching { JSONObject(payload) }.getOrNull() ?: return@mapNotNull null
                    if (result.optString("schemaAvailability") != "next_request") return@mapNotNull null
                    val tools = result.getJSONArray("tools")
                    (0 until tools.length())
                        .map { tools.getJSONObject(it) }
                        .singleOrNull { it.getString("name") == canonicalName }
                        ?.getString("description")
                }.distinct()
        if (descriptions.isEmpty()) return null
        check(descriptions.size == 1) { "Conflicting discovery descriptions for $canonicalName" }
        return select(request, canonicalName, descriptions.single())
    }

    private fun select(
        request: JSONObject,
        canonicalName: String,
        descriptionPrefix: String,
    ): String {
        check(descriptionPrefix.isNotBlank()) { "Missing fixture description for $canonicalName" }
        val tools = request.optJSONArray("tools")
        val matches =
            (0 until (tools?.length() ?: 0))
                .map { tools!!.getJSONObject(it).getJSONObject("function") }
                .filter { it.optString("description").startsWith(descriptionPrefix) }
        check(matches.size == 1) {
            "Fixture tool $canonicalName must match exactly one current declaration, found ${matches.size}"
        }
        return matches.single().getString("name").also { name ->
            check(name.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid OpenAI fixture wire name for $canonicalName" }
        }
    }
}

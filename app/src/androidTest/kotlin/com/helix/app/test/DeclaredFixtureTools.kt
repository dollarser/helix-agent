package com.helix.app.test

import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import org.json.JSONObject

/** Select a model-visible name from this request, never from an alias ordinal or an earlier request. */
fun declaredFixtureToolName(
    requestBody: String,
    canonicalName: String,
    description: String,
): String =
    checkNotNull(findDeclaredFixtureToolName(requestBody, canonicalName, description)) {
        "Fixture tool $canonicalName is not declared in this request"
    }

/** Null means discovery is still needed; ambiguity and invalid declarations always fail. */
fun findDeclaredFixtureToolName(
    requestBody: String,
    canonicalName: String,
    description: String,
): String? {
    check(description.isNotBlank()) { "Fixture tool $canonicalName needs an explicit description" }
    val tools = JSONObject(requestBody).optJSONArray("tools")
    val matches =
        (0 until (tools?.length() ?: 0))
            .map { tools!!.getJSONObject(it).let { tool -> tool.optJSONObject("function") ?: tool } }
            .filter { it.optString("description").startsWith(description) }
    if (matches.isEmpty()) return null
    check(matches.size == 1) {
        "Fixture tool $canonicalName must match exactly one current declaration, found ${matches.size}"
    }
    return matches.single().getString("name").also { name ->
        check(name.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid OpenAI fixture wire name for $canonicalName" }
    }
}

/** Real registry metadata binds a known fixture intention; presence still requires a current wire declaration. */
fun registeredFixtureToolName(
    requestBody: String,
    canonicalName: String,
): String = declaredFixtureToolName(requestBody, canonicalName, registeredFixtureToolDescription(canonicalName))

fun registeredFixtureToolDescription(canonicalName: String): String {
    val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    val descriptor =
        checkNotNull(app.appContainer.toolPipeline.resolveLatest(canonicalName)) {
            "Fixture tool $canonicalName is not registered"
        }
    return descriptor.description
}

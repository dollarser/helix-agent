package com.helix.core.storage.repository

import com.helix.core.model.OperationEffect
import com.helix.core.model.OperationRule

/**
 * The deterministic flat-JSON codec for the materialized rule table of one
 * [com.helix.core.policy.SessionPermissionConfig] (HXA-209, ADR-PERMISSIONS-001 section 2 step
 * 4): `{"EFFECT":"RULE",...}`. Enum names are `[A-Z_0-9]+`, so no escaping exists; [encode]
 * emits sorted keys so the stored text is stable for the same table. [decode] is fail-closed:
 * malformed JSON, an unknown effect, an unknown rule or a duplicated key all throw — a stored
 * rule table is never guessed.
 */
object SessionPermissionRulesCodec {
    private val ENTRY = Regex("^\"([A-Z_0-9]+)\"\\s*:\\s*\"([A-Z_0-9]+)\"$")

    fun encode(rules: Map<OperationEffect, OperationRule>): String {
        if (rules.isEmpty()) {
            return "{}"
        }
        val parts =
            rules.entries
                .sortedBy { it.key.name }
                .joinToString(",") { (effect, rule) -> "\"${effect.name}\":\"${rule.name}\"" }
        return "{$parts}"
    }

    fun decode(rulesJson: String): Map<OperationEffect, OperationRule> {
        require(rulesJson.startsWith("{") && rulesJson.endsWith("}")) {
            "rules JSON must be a flat object: $rulesJson"
        }
        val body = rulesJson.substring(1, rulesJson.length - 1).trim()
        if (body.isEmpty()) {
            return emptyMap()
        }
        val result = linkedMapOf<OperationEffect, OperationRule>()
        for (entry in body.split(",")) {
            val (effect, rule) = parseEntry(entry)
            require(!result.containsKey(effect)) { "duplicate rule for ${effect.name}" }
            result[effect] = rule
        }
        return result
    }

    private fun parseEntry(entry: String): Pair<OperationEffect, OperationRule> {
        val match = ENTRY.matchEntire(entry.trim()) ?: error("malformed rule entry: $entry")
        val effectName = match.groupValues[1]
        val ruleName = match.groupValues[2]
        return parseEffect(effectName) to parseRule(ruleName)
    }

    private fun parseEffect(name: String): OperationEffect =
        runCatching { OperationEffect.valueOf(name) }.getOrElse {
            throw IllegalArgumentException("unknown operation effect in rules JSON: $name")
        }

    private fun parseRule(name: String): OperationRule =
        runCatching { OperationRule.valueOf(name) }.getOrElse {
            throw IllegalArgumentException("unknown operation rule in rules JSON: $name")
        }
}

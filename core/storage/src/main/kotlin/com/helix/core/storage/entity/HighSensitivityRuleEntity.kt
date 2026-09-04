package com.helix.core.storage.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A persisted ADVANCED high-sensitivity egress rule (ADR-0005; HXA-068). Every column is a
 * primitive so Room needs no type converters; the two value facets that are NOT primitive are
 * folded to their canonical storage strings:
 *
 * - [targetKind] is the closed "provider"/"mcp" discriminator of [com.helix.core.policy.EgressTarget]
 *   and [targetId] its stable id — template/product/display names never gate anything (ADR-0005);
 * - [originFull] is the [com.helix.core.model.NormalizedEndpoint.full] form (scheme-pinned, host
 *   lowercased, default port filled in), so a rehydrated rule equals the live request endpoint;
 * - [scopeEncoded] is the LOSSLESS [com.helix.core.policy.UserScopeCodec] encoding of the scope
 *   (every constructor field of every scope subtype), because the Policy Engine matches a stored
 *   rule by the exact `scope == scope` and any dropped display-only field would silently break it.
 *
 * There is deliberately NO data-category column: a stored rule is always
 * [com.helix.core.policy.DataSensitivity.SENSITIVE] — that invariant is enforced by the
 * [com.helix.core.policy.HighSensitivityRule] constructor, so storing a second value would be
 * unconstructable. Timestamps are whole `epochSecond` values; the four fixed rule TTLs (1h/24h/7d/
 * 30d) never carry sub-second precision that a match depends on, and `createdAt`/`expiresAt` share
 * a fractional part, so flooring both preserves the exact TTL.
 */
@Entity(tableName = "high_sensitivity_rules")
data class HighSensitivityRuleEntity(
    @PrimaryKey val id: String,
    val targetKind: String,
    val targetId: String,
    val originFull: String,
    val scopeEncoded: String,
    val createdAtEpoch: Long,
    val expiresAtEpoch: Long,
)

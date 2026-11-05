package com.helix.core.model

/**
 * Build-channel capability identity: consumer uses [STANDARD], developer uses [ADVANCED].
 * It is never a user preference or ToolCall parameter. Session authorization, directory scope,
 * Android grants and runtime readiness remain separate execution requirements.
 */
enum class SafetyProfile { STANDARD, ADVANCED }

package com.helix.tools.automation

internal object AndroidPackageName {
    private const val MAX_LENGTH = 255
    private val pattern = Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*){0,50}")

    fun isValid(value: String): Boolean = value.length in 1..MAX_LENGTH && pattern.matches(value)
}

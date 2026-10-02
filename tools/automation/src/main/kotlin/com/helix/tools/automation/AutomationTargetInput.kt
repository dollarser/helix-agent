package com.helix.tools.automation

/** User-entered targets are validated together before changing the saved selection. */
object AutomationTargetInput {
    fun parse(text: String): Set<String> {
        val packages = text.split(Regex("[,，\\s]+")).filter(String::isNotBlank).toSet()
        require(packages.isNotEmpty()) { "No target selected" }
        require(packages.all(AndroidPackageName::isValid)) { "Invalid target package" }
        return packages
    }
}

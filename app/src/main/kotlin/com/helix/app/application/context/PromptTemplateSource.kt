package com.helix.app.application.context

/** Supplies trusted packaged templates; external content cannot replace this host port. */
internal fun interface PromptTemplateSource {
    fun text(name: String): String
}

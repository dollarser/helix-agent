package com.helix.extensions.mcp

import io.modelcontextprotocol.kotlin.sdk.types.Prompt
import io.modelcontextprotocol.kotlin.sdk.types.PromptArgument
import io.modelcontextprotocol.kotlin.sdk.types.Resource
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Tool

internal fun List<Tool>.toToolMetadata(limits: McpMetadataLimits): List<McpToolMetadata> =
    take(limits.maxItemsPerKind).map { tool ->
        val inputSchema = tool.inputSchema.toBoundedJson(limits.maxSchemaBytes)
        val outputSchema = tool.outputSchema?.toBoundedJson(limits.maxSchemaBytes)
        McpToolMetadata(
            name = tool.name.requireBounded("tool name", limits.maxTextChars),
            title = tool.title?.bounded(limits.maxTextChars),
            description = tool.description?.bounded(limits.maxTextChars),
            inputSchema = inputSchema,
            outputSchema = outputSchema,
            schemaHash = hashFields(listOf(inputSchema.canonicalJson(), outputSchema?.canonicalJson())),
            serverProvidedHints =
                buildMap {
                    tool.annotations?.readOnlyHint?.let { put("readOnlyHint", it) }
                    tool.annotations?.destructiveHint?.let { put("destructiveHint", it) }
                    tool.annotations?.idempotentHint?.let { put("idempotentHint", it) }
                    tool.annotations?.openWorldHint?.let { put("openWorldHint", it) }
                },
        )
    }

internal fun List<Resource>.toResourceMetadata(limits: McpMetadataLimits): List<McpResourceMetadata> =
    take(limits.maxItemsPerKind).map { resource ->
        val uri = resource.uri.requireBounded("resource URI", limits.maxTextChars)
        val name = resource.name.requireBounded("resource name", limits.maxTextChars)
        val title = resource.title?.bounded(limits.maxTextChars)
        val description = resource.description?.bounded(limits.maxTextChars)
        val mimeType = resource.mimeType?.requireBounded("resource MIME type", limits.maxTextChars)
        McpResourceMetadata(
            uri = uri,
            name = name,
            title = title,
            description = description,
            mimeType = mimeType,
            size = resource.size,
            metadataHash = hashFields(listOf(uri, name, title, description, mimeType, resource.size?.toString())),
        )
    }

internal fun List<Prompt>.toPromptMetadata(limits: McpMetadataLimits): List<McpPromptMetadata> =
    take(limits.maxItemsPerKind).map { prompt -> prompt.toMetadata(limits) }

internal fun ServerCapabilities.toSnapshot(): McpCapabilitySnapshot =
    McpCapabilitySnapshot(
        tools = tools != null,
        resources = resources != null,
        prompts = prompts != null,
        toolListChanged = tools?.listChanged == true,
        resourceListChanged = resources?.listChanged == true,
        resourceSubscribe = resources?.subscribe == true,
        promptListChanged = prompts?.listChanged == true,
    )

internal fun McpMetadataSnapshot.requireWithinTotalBytes(maxBytes: Int) {
    var total = 0L
    metadataStrings().forEach { value ->
        if (value != null) total += value.toByteArray(Charsets.UTF_8).size
        require(total <= maxBytes) { "MCP metadata exceeds $maxBytes bytes" }
    }
}

private fun Prompt.toMetadata(limits: McpMetadataLimits): McpPromptMetadata {
    val allArguments = arguments.orEmpty()
    val boundedArguments = allArguments.take(limits.maxPromptArguments).map { it.toMetadata(limits.maxTextChars) }
    val name = name.requireBounded("prompt name", limits.maxTextChars)
    val title = title?.bounded(limits.maxTextChars)
    val description = description?.bounded(limits.maxTextChars)
    val fields = mutableListOf<String?>(name, title, description)
    boundedArguments.forEach { argument ->
        fields += listOf(argument.name, argument.description, argument.required.toString())
    }
    return McpPromptMetadata(
        name = name,
        title = title,
        description = description,
        arguments = boundedArguments,
        argumentsTruncated = allArguments.size > limits.maxPromptArguments,
        metadataHash = hashFields(fields),
    )
}

private fun PromptArgument.toMetadata(maxChars: Int) =
    McpPromptArgumentMetadata(
        name = name.requireBounded("prompt argument name", maxChars),
        description = description?.bounded(maxChars),
        required = required == true,
    )

private fun McpMetadataSnapshot.metadataStrings(): List<String?> =
    buildList {
        tools.forEach {
            addAll(
                listOf(
                    it.name,
                    it.title,
                    it.description,
                    it.inputSchema.canonicalJson(),
                    it.outputSchema?.canonicalJson(),
                    it.schemaHash,
                ),
            )
        }
        resources.forEach { addAll(listOf(it.uri, it.name, it.title, it.description, it.mimeType, it.metadataHash)) }
        prompts.forEach { prompt ->
            addAll(listOf(prompt.name, prompt.title, prompt.description, prompt.metadataHash))
            prompt.arguments.forEach { addAll(listOf(it.name, it.description)) }
        }
    }

private fun String.bounded(maxChars: Int): String = take(maxChars)

private fun String.requireBounded(
    label: String,
    maxChars: Int,
): String {
    require(isNotBlank()) { "$label must not be blank" }
    require(length <= maxChars) { "$label exceeds $maxChars characters" }
    return this
}

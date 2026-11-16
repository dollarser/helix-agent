package com.helix.tools.framework

import com.helix.core.model.ToolName
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/** External names are adapted once at registration; execution retains the original remote name. */
object RemoteToolNames {
    fun mcp(
        serverId: String,
        remoteName: String,
    ): ToolName {
        require(Regex("[A-Za-z0-9_.-]{1,128}").matches(remoteName)) { "invalid MCP tool name" }
        return scoped("mcp", serverId, remoteName)
    }

    fun a2a(
        agentId: String,
        skillId: String,
    ): ToolName {
        require(skillId.isNotBlank() && skillId.length <= 256) { "invalid A2A Skill id" }
        return scoped("a2a", agentId, skillId)
    }

    private fun scoped(
        kind: String,
        owner: String,
        remoteName: String,
    ): ToolName {
        val identity = JsonArray(listOf(kind, owner, remoteName).map(::JsonPrimitive)).toString()
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest(identity.toByteArray(Charsets.UTF_8))
                .take(16)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return ToolName("${kind}_${readable(owner)}_${readable(remoteName)}_$digest")
    }

    private fun readable(value: String): String = value.replace(Regex("[^A-Za-z0-9_-]"), "_").take(12)
}

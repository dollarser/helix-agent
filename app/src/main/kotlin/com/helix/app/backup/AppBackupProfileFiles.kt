package com.helix.app.backup

import com.helix.core.model.PlanArtifact
import com.helix.core.workspace.FileScopePath
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files

internal class AppBackupProfileFiles(
    private val directory: File,
    private val snapshot: AppBackupSnapshot,
    private val identities: PortableRestoreRecords,
    private val checkActive: () -> Unit,
) {
    val profile = File(directory, "profile")
    val content = File(profile, "content")
    val roots = linkedMapOf<String, File?>()
    val clones = mutableListOf<Pair<String, File>>()
    private val groups get() = snapshot.groups
    private val source get() = snapshot.state.getValue("records") as JsonObject

    fun stage() {
        copyGroup("content", content)
        roots["files/helix-content"] = content
        mapOf(
            "memory" to "memory",
            "skills" to "skills",
            "plans" to "helix-content-plans",
            "marketplaces" to "marketplaces",
            "history" to "agent-library-history/library",
        ).forEach { (group, relative) ->
            val target = File(profile, if (group == "plans") "content-plans" else group)
            copyGroup(group, target)
            roots["files/$relative"] = target
        }
        val privateFiles = File(profile, "restored-${identities.restoreId}").apply { check(mkdirs()) }
        copyGroup("attachment", File(privateFiles, "attachments"))
        val payloads =
            (snapshot.state.getValue("workspacePayloads") as JsonArray)
                .map { it.jsonPrimitive.content }
                .toSet()
        require(payloads.all { it == "app" || it in identities.workspaceClones }) { "BACKUP_WORKSPACE_INVALID" }
        if ("app" in payloads) {
            val target = File(privateFiles, "workspace").apply { check(mkdirs()) }
            copyWorkspace("workspace", "app", target)
        }
        roots["files/workspaces/app/restored-${identities.restoreId}"] = privateFiles
        stageWorkspaces(payloads)
        stageNetwork()
        stageApprovedPlans()
    }

    private fun stageWorkspaces(payloads: Set<String>) {
        val bindings = AppBackupCapture.rows(source, "session_workspaces") + AppBackupCapture.rows(source, "projects")
        identities.workspaceClones.forEach { (oldId, newId) ->
            val target = File(profile, "workspaces/$newId").apply { check(mkdirs()) }
            copyWorkspace(if (oldId in payloads) "workspace" else "instructions", oldId, target)
            bindings.filter { AppBackupCapture.text(it, "workspaceId") == oldId }.forEach { binding ->
                val relative = AppBackupCapture.text(binding, "relativePath")
                FileScopePath(oldId, relative)
                val current = File(target, relative)
                check(current.isDirectory || current.mkdirs())
            }
            roots["files/workspaces/managed/$newId"] = target
            clones += newId to target
        }
    }

    private fun stageNetwork() {
        groups["network"].orEmpty().forEach { descriptor ->
            require(descriptor.getValue("path").jsonPrimitive.content == "native-hosts.txt")
            roots["files/native-hosts.txt"] = blob(descriptor)
        }
        if (groups["network"].isNullOrEmpty()) roots["files/native-hosts.txt"] = null
    }

    private fun stageApprovedPlans() {
        AppBackupCapture.rows(source, "plans").forEach { plan ->
            val snapshot = plan["approvedSnapshot"]
            if (snapshot != null && snapshot != JsonNull) {
                val artifact = PlanArtifact.parse(snapshot.jsonPrimitive.content)
                require(artifact.id.value == AppBackupCapture.text(plan, "id"))
                val body = File(profile, "content-plans/plans/${artifact.id.value}.json")
                val parent = requireNotNull(body.parentFile)
                check(parent.isDirectory || parent.mkdirs())
                body.writeText(artifact.toStorageString())
            }
        }
    }

    private fun copyGroup(
        group: String,
        target: File,
    ) {
        check(target.isDirectory || target.mkdirs())
        groups[group].orEmpty().forEach { descriptor ->
            copy(descriptor, descriptor.getValue("path").jsonPrimitive.content, target)
        }
    }

    private fun copyWorkspace(
        group: String,
        id: String,
        target: File,
    ) {
        val prefix = "$id/"
        groups[group]
            .orEmpty()
            .filter {
                it
                    .getValue("path")
                    .jsonPrimitive.content
                    .startsWith(prefix)
            }.forEach { descriptor ->
                copy(
                    descriptor,
                    descriptor
                        .getValue("path")
                        .jsonPrimitive.content
                        .removePrefix(prefix),
                    target,
                )
            }
    }

    private fun copy(
        descriptor: JsonObject,
        relative: String,
        target: File,
    ) {
        checkActive()
        PortableFileSet.safePath(relative)
        val destination = File(target, relative)
        require(destination.toPath().normalize().startsWith(target.toPath()))
        val parent = requireNotNull(destination.parentFile)
        check(parent.isDirectory || parent.mkdirs())
        Files.copy(blob(descriptor).toPath(), destination.toPath())
    }

    private fun blob(descriptor: JsonObject) =
        snapshot.members.getValue("blobs/${descriptor.getValue("sha256").jsonPrimitive.content}")
}

internal data class AppBackupSnapshot(
    val state: JsonObject,
    val members: Map<String, File>,
    val groups: Map<String, List<JsonObject>>,
)

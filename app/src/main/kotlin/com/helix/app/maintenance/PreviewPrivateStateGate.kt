package com.helix.app.maintenance

import com.helix.app.proot.ExecutionOwnershipStore
import com.helix.core.storage.PreviewDatabaseResetRequired
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Checks only host-owned control records; never touches runtime/rootfs, workspaces, secrets or models. */
internal class PreviewPrivateStateGate(
    private val filesRoot: File,
    private val databaseReset: () -> PreviewDatabaseResetRequired?,
    private val readMobile: () -> List<String>,
    private val validateMobile: () -> Boolean,
    private val clearMobile: () -> Unit,
    private val rebootProvesStopped: (String) -> Boolean,
    private val runtimeActive: () -> Boolean = { false },
) {
    fun inspect(): PreviewResetRequest? {
        val steps = mutableListOf<PreviewResetStep>()
        val database = databaseReset()
        mobileStep()?.let(steps::add)
        val controls =
            terminalFiles().map { file ->
                val snapshot = PrivateRecord.capture(file)
                val current =
                    try {
                        Result.success(ExecutionOwnershipStore(file).owners())
                    } catch (failure: com.helix.app.proot.ExecutionControlUnreadable) {
                        Result.failure(failure)
                    }
                Control(snapshot, current.isFailure, current.getOrNull()?.isNotEmpty() == true)
            }
        val replaced = controls.filter { it.incompatible || database != null }
        replaced.forEach { control ->
            steps +=
                PreviewResetStep(PreviewResetSection.TERMINAL, control.record::verify) {
                    retire(control.record)
                }
        }
        // Database last: an interrupted reset must still discover the remaining related control records.
        database?.let { request ->
            steps += PreviewResetStep(PreviewResetSection.DATABASE, request::verifyUnchanged) { request.confirm() }
        }
        if (steps.isEmpty()) return null
        val uncertain = replaced.filter { !it.record.emptyAdmission || it.hasOwners }
        val requiresRestart =
            uncertain.isNotEmpty() &&
                !rebootProvesStopped(uncertain.map { it.record.fingerprint }.sorted().joinToString("|"))
        return PreviewResetRequest(steps, requiresRestart || runtimeActive()) {
            check(!runtimeActive()) { "Runtime became active; review reset again" }
        }
    }

    private fun mobileStep(): PreviewResetStep? {
        val before = readMobile()
        if (before.isEmpty() || validateMobile()) return null
        return PreviewResetStep(
            PreviewResetSection.MOBILE_USE,
            { check(readMobile() == before) { "Plugin configuration changed; review again" } },
            clearMobile,
        )
    }

    private fun terminalFiles(): List<File> {
        val root = File(filesRoot, "execution-admission")
        if (!root.exists()) return emptyList()
        return existingTerminalFiles(root)
    }

    private fun existingTerminalFiles(root: File): List<File> {
        require(Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS))
        val terminals = File(root, "terminals")
        val selected = listOf("owner", "manual-terminal", "manual-terminal-2").map { File(root, it) }
        if (!terminals.exists()) {
            return selected.filter(File::exists)
        }
        require(Files.isDirectory(terminals.toPath(), LinkOption.NOFOLLOW_LINKS))
        return selected.filter(File::exists) +
            requireNotNull(terminals.listFiles()).filter { it.name.endsWith(".owner") }
    }

    private fun retire(record: PrivateRecord) {
        record.verify()
        if (record.emptyAdmission) {
            Files.delete(record.file.toPath())
        } else {
            // Reboot proves process death, not command outcome. Retain original bytes as private evidence.
            val retired = File(filesRoot, "preview-retired-terminal")
            check(retired.isDirectory || retired.mkdirs())
            require(Files.isDirectory(retired.toPath(), LinkOption.NOFOLLOW_LINKS))
            val target = File(retired, record.fingerprint + ".record")
            if (target.exists()) {
                check(PrivateRecord.capture(target).fingerprint == record.fingerprint)
                Files.delete(record.file.toPath())
            } else {
                Files.move(record.file.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
        }
    }

    private data class Control(
        val record: PrivateRecord,
        val incompatible: Boolean,
        val hasOwners: Boolean,
    )
}

private data class PrivateRecord(
    val file: File,
    val bytes: ByteArray,
    val fingerprint: String,
) {
    val emptyAdmission: Boolean get() = bytes.size == 8 && ByteBuffer.wrap(bytes).getInt(4) == 0

    fun verify() {
        check(capture(file).bytes.contentEquals(bytes)) { "Execution identity changed; review again" }
    }

    companion object {
        fun capture(file: File): PrivateRecord {
            require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Control record is not regular" }
            require(file.length() <= 1024L * 1024) { "Control record exceeds its limit" }
            val bytes =
                file.inputStream().use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= 1024 * 1024) { "Control record exceeds its limit" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            return PrivateRecord(file, bytes, hash)
        }
    }
}

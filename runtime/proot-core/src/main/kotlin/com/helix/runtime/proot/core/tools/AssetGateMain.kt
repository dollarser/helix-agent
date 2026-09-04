package com.helix.runtime.proot.core.tools

import com.helix.runtime.proot.core.ElfLoaderAlignChecker
import java.io.File
import java.security.MessageDigest
import kotlin.system.exitProcess

/**
 * Build-time runtime asset gate (HXA-081). One JVM entry point used by
 * `scripts/build-proot-assets.sh` over the SAME [ElfLoaderAlignChecker] the on-device
 * installer (HXA-082) and the 16 KiB compat decision (HXA-086) use.
 *
 * Usage:
 *   asset-gate --min-align <bytes> --expect-machine <EM> [--expect-sha256 <sha256> <file>]* <path>...
 *
 * `<path>` may be a file or a directory (walked recursively, deterministic sorted order).
 * Every ELF found must: be structurally valid, have `e_machine == --expect-machine`, and
 * every `PT_LOAD` segment aligned to at least `--min-align`. Non-ELF files are skipped
 * (scripts, text, tarballs). An explicit `--expect-sha256 <sha256> <file>` pair must match
 * the file's SHA-256 (canonical lowercase hex). Exit 0 = pass, 1 = violation, 2 = usage.
 * The report is printed deterministically (sorted paths, one line per finding) so the
 * completion record can quote it verbatim.
 */
fun main(args: Array<String>) {
    val options = parseArgs(args)
    val report = runGate(options)
    printReport(report)
}

private data class GateOptions(
    val minAlign: Long,
    val expectMachine: Long?,
    val expectSha256: Map<String, String>, // path -> sha256
    val paths: List<String>,
)

private data class GateReport(
    val elfCount: Int,
    val minSeen: Long,
    val violations: List<String>,
)

private class ElfStats {
    var elfCount = 0
    var minSeen = Long.MAX_VALUE
}

private val SHA256_REGEX = Regex("[0-9a-f]{64}")

private fun parseArgs(args: Array<String>): GateOptions {
    var minAlign: Long = ElfLoaderAlignChecker.PAGE_SIZE_16KIB
    var expectMachine: Long? = null
    val expectSha256 = LinkedHashMap<String, String>()
    val paths = mutableListOf<String>()
    var i = 0
    while (i < args.size) {
        when (val arg = args[i]) {
            "--min-align" -> {
                i++
                minAlign = args.getOrNull(i)?.toLongOrNull() ?: usage()
            }

            "--expect-machine" -> {
                i++
                expectMachine = args.getOrNull(i)?.toLongOrNull() ?: usage()
            }

            "--expect-sha256" -> {
                i++
                val sha = args.getOrNull(i) ?: usage()
                i++
                val file = args.getOrNull(i) ?: usage()
                if (!sha.matches(SHA256_REGEX)) usage("expected-sha256 must be 64 lowercase hex chars")
                expectSha256[file] = sha
            }

            else -> {
                paths += arg
            }
        }
        i++
    }
    if (paths.isEmpty()) usage("at least one path is required")
    return GateOptions(minAlign, expectMachine, expectSha256, paths)
}

private fun runGate(options: GateOptions): GateReport {
    val violations = mutableListOf<String>()
    val stats = ElfStats()
    for (path in options.paths.sorted()) {
        val file = File(path)
        if (!file.exists()) {
            violations += "missing path: $path"
            continue
        }
        expand(file).forEach { f -> scanOne(f, options, stats, violations) }
    }
    return GateReport(stats.elfCount, stats.minSeen, violations)
}

private fun expand(file: File): List<File> =
    if (file.isDirectory) {
        file
            .walkTopDown()
            .filter { it.isFile }
            .toList()
            .sortedBy { it.path }
    } else {
        listOf(file)
    }

private fun scanOne(
    file: File,
    options: GateOptions,
    stats: ElfStats,
    violations: MutableList<String>,
) {
    val expected = options.expectSha256[file.path]
    if (expected != null) {
        val actual = sha256Hex(file)
        if (actual != expected) {
            violations += "sha256 mismatch: ${file.path} expected=$expected actual=$actual"
        }
    }
    val result = ElfLoaderAlignChecker.scan(file.readBytes())
    if (!result.isElf) return
    stats.elfCount++
    if (result.error != null) {
        violations += "malformed ELF: ${file.path} (${result.error})"
        return
    }
    stats.minSeen = minOf(stats.minSeen, result.minLoadAlign)
    if (result.minLoadAlign < options.minAlign) {
        violations += "alignment: ${file.path} min PT_LOAD p_align=${result.minLoadAlign} < ${options.minAlign}"
    }
    if (options.expectMachine != null && result.machine != options.expectMachine) {
        violations += "ABI: ${file.path} e_machine=${result.machine} != expected ${options.expectMachine}"
    }
}

private fun printReport(report: GateReport) {
    val min = if (report.elfCount > 0) report.minSeen.toString() else "n/a"
    println("asset-gate: scanned ${report.elfCount} ELFs; min PT_LOAD p_align=$min")
    if (report.violations.isEmpty()) {
        println("asset-gate: PASS")
        exitProcess(0)
    }
    report.violations.sorted().forEach { println("asset-gate: VIOLATION $it") }
    println("asset-gate: FAIL (${report.violations.size} violations)")
    exitProcess(1)
}

private fun sha256Hex(file: File): String =
    MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

private fun usage(
    message: String =
        "usage: asset-gate --min-align <bytes> --expect-machine <EM> " +
            "[--expect-sha256 <sha256> <file>]* <path>...",
): Nothing {
    System.err.println(message)
    exitProcess(2)
}

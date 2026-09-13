package com.helix.app.git

import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.Edit
import org.eclipse.jgit.diff.EditList
import org.eclipse.jgit.diff.MyersDiff
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.Sequence
import org.eclipse.jgit.diff.SequenceComparator
import org.eclipse.jgit.errors.LargeObjectException
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import java.io.File

private const val MAX_DIFF_SIDE_BYTES = 1024L * 1024L
private const val MAX_DIFF_SIDE_KIB = MAX_DIFF_SIDE_BYTES / 1024
private const val MAX_DIFF_LINES = 50_000
private const val DIFF_CONTEXT = 5
private const val NO_NEWLINE_MARKER = "\\ No newline at end of file\n"

/**
 * Renders ONE UNSTAGED (worktree-vs-index) diff entry as a unified diff WITHOUT touching the
 * repository — [GitWorkspaceReader] is read-only, and this is the half of the diff that used to
 * require a write. [org.eclipse.jgit.diff.DiffFormatter] cannot render an unstaged pair: JGit
 * hashes the worktree side into the [DiffEntry] but never stores it in the object database, so
 * the diff is computed here instead — the old side is read from the odb (the index blob always
 * exists there), the new side straight from the worktree file, both bounded by
 * [MAX_DIFF_SIDE_BYTES], and the lines are diffed with [MyersDiff.INSTANCE] over a line
 * [Sequence] whose [Edit] offsets are therefore line indices. Everything is local to the call:
 * two concurrent renders, or a render racing a real `git` process, share no state.
 */
internal class WorktreeDiffRenderer(
    private val repoDir: File,
) {
    /**
     * The unified diff for [entry], or an honest one-line message when it cannot be rendered
     * (binary content, a side over the size limit); `""` when the sides are identical.
     */
    @Suppress("ReturnCount") // one early-out per closed failure state (size limit, binary)
    fun render(
        repo: Repository,
        entry: DiffEntry,
    ): String {
        val oldBytes = readOdbSide(repo, entry.oldId?.toObjectId())
        val newBytes = readWorktreeSide(File(repoDir, entry.newPath))
        if (oldBytes == null || newBytes == null) {
            return "diff not rendered: ${nameOf(entry)} exceeds the $MAX_DIFF_SIDE_KIB KiB per-side limit\n"
        }
        if (RawText.isBinary(oldBytes) || RawText.isBinary(newBytes)) {
            return "Binary files a/${nameOf(entry)} and b/${nameOf(entry)} differ\n"
        }
        val old = splitLines(oldBytes)
        val new = splitLines(newBytes)
        return if (old.lines.size + new.lines.size > MAX_DIFF_LINES) {
            "diff not rendered: ${nameOf(entry)} exceeds the $MAX_DIFF_LINES-line limit\n"
        } else {
            unifiedEntry(entry, old, new, lineDiff(old, new))
        }
    }

    /** The old side: an index blob, always stored in the odb; `null` when it exceeds the cap. */
    private fun readOdbSide(
        repo: Repository,
        id: ObjectId?,
    ): ByteArray? {
        if (id == null || id == ObjectId.zeroId()) return ByteArray(0)
        return try {
            repo.open(id).getBytes(MAX_DIFF_SIDE_BYTES.toInt())
        } catch (_: LargeObjectException) {
            null
        }
    }

    /** The new side: the worktree file itself; `null` when it exceeds the cap. */
    private fun readWorktreeSide(file: File): ByteArray? =
        when {
            !file.isFile -> ByteArray(0)
            file.length() > MAX_DIFF_SIDE_BYTES -> null
            else -> file.readBytes()
        }

    /** The path the human sees for [entry] (a deleted file is known by its old path). */
    private fun nameOf(entry: DiffEntry): String =
        if (entry.changeType == DiffEntry.ChangeType.DELETE) entry.oldPath else entry.newPath

    private class TextLines(
        val lines: List<String>,
        val endsWithNewline: Boolean,
    )

    /**
     * git-style line split: lines are separated by `\n` (a trailing `\r` stays part of the
     * content), and whether the text ends with a newline is carried along so "add the final
     * newline" renders as a change instead of nothing.
     */
    private fun splitLines(bytes: ByteArray): TextLines {
        if (bytes.isEmpty()) return TextLines(emptyList(), true)
        val endsWithNewline = bytes[bytes.lastIndex] == '\n'.code.toByte()
        val parts = bytes.toString(Charsets.UTF_8).split('\n')
        return TextLines(if (endsWithNewline) parts.dropLast(1) else parts, endsWithNewline)
    }

    private fun lineDiff(
        old: TextLines,
        new: TextLines,
    ): EditList =
        MyersDiff.INSTANCE.diff(
            LineComparator(),
            LineSequence(old.lines, old.endsWithNewline),
            LineSequence(new.lines, new.endsWithNewline),
        )

    private fun unifiedEntry(
        entry: DiffEntry,
        old: TextLines,
        new: TextLines,
        changes: EditList,
    ): String {
        val hunks = buildHunks(changes)
        if (hunks.isEmpty()) return ""
        val isAdd = entry.changeType == DiffEntry.ChangeType.ADD
        val isDelete = entry.changeType == DiffEntry.ChangeType.DELETE
        val sb = StringBuilder()
        sb
            .append("diff --git a/")
            .append(entry.oldPath)
            .append(" b/")
            .append(entry.newPath)
            .append('\n')
        sb.append("--- ").append(if (isAdd) "/dev/null" else "a/${entry.oldPath}").append('\n')
        sb.append("+++ ").append(if (isDelete) "/dev/null" else "b/${entry.newPath}").append('\n')
        hunks.forEach { h ->
            val aLo = (h.aLo - DIFF_CONTEXT).coerceAtLeast(0)
            val aHi = (h.aHi + DIFF_CONTEXT).coerceAtMost(old.lines.size)
            val bLo = (h.bLo - DIFF_CONTEXT).coerceAtLeast(0)
            val bHi = (h.bHi + DIFF_CONTEXT).coerceAtMost(new.lines.size)
            sb
                .append("@@ -")
                .append(hunkStart(aLo, aHi))
                .append(',')
                .append(aHi - aLo)
                .append(" +")
                .append(hunkStart(bLo, bHi))
                .append(',')
                .append(bHi - bLo)
                .append(" @@\n")
            renderBody(sb, old, new, changes.subList(h.first, h.last + 1), BodyRange(aLo, aHi, bLo, bHi))
        }
        return sb.toString()
    }

    /** 1-based start line of a range; a zero-length range names its (0-based) insertion point. */
    private fun hunkStart(
        lo: Int,
        hi: Int,
    ): Int = if (hi > lo) lo + 1 else lo

    private class BodyRange(
        val aLo: Int,
        val aHi: Int,
        val bLo: Int,
        val bHi: Int,
    )

    private fun renderBody(
        sb: StringBuilder,
        old: TextLines,
        new: TextLines,
        changes: List<Edit>,
        r: BodyRange,
    ) {
        var ai = r.aLo
        var bi = r.bLo
        changes.forEach { c ->
            while (ai < c.beginA) {
                sb.append(' ').append(old.lines[ai]).append('\n')
                ai++
                bi++
            }
            while (ai < c.endA) {
                sb.append('-').append(old.lines[ai]).append('\n')
                if (!old.endsWithNewline && ai == old.lines.lastIndex) sb.append(NO_NEWLINE_MARKER)
                ai++
            }
            while (bi < c.endB) {
                sb.append('+').append(new.lines[bi]).append('\n')
                if (!new.endsWithNewline && bi == new.lines.lastIndex) sb.append(NO_NEWLINE_MARKER)
                bi++
            }
        }
        while (ai < r.aHi) {
            sb.append(' ').append(old.lines[ai]).append('\n')
            ai++
            bi++
        }
    }

    /**
     * Groups consecutive changes into hunks: a change joins the open hunk while its distance
     * from the hunk's last change (either side) is within the doubled context — exactly when
     * the two hunks' context would otherwise overlap.
     */
    private fun buildHunks(changes: EditList): List<Hunk> {
        val hunks = ArrayList<Hunk>()
        changes.forEachIndexed { i, c ->
            val last = hunks.lastOrNull()
            if (last != null &&
                (c.beginA - last.aHi <= DIFF_CONTEXT * 2 || c.beginB - last.bHi <= DIFF_CONTEXT * 2)
            ) {
                last.aHi = maxOf(last.aHi, c.endA)
                last.bHi = maxOf(last.bHi, c.endB)
                last.last = i
            } else {
                hunks.add(Hunk(c.beginA, c.endA, c.beginB, c.endB, i, i))
            }
        }
        return hunks
    }

    private class Hunk(
        var aLo: Int,
        var aHi: Int,
        var bLo: Int,
        var bHi: Int,
        val first: Int,
        var last: Int,
    )
}

/** A diff [Sequence] over lines: element i is line i, so the [Edit] offsets are line indices. */
private class LineSequence(
    val lines: List<String>,
    val endsWithNewline: Boolean,
) : Sequence() {
    override fun size(): Int = lines.size

    fun line(i: Int): String = lines[i]
}

/**
 * Line identity is the text, plus a sentinel on the LAST line ONLY when its side has no trailing
 * newline — so a line that keeps its newline matches the same text anywhere (matching is
 * position-independent, as the diff algorithm requires), while "a" vs "a" with a lost final
 * newline still differs on that line (the sentinel itself never renders; the renderer emits the
 * "\ No newline at end of file" marker instead).
 */
private class LineComparator : SequenceComparator<LineSequence>() {
    override fun equals(
        a: LineSequence,
        aOffset: Int,
        b: LineSequence,
        bOffset: Int,
    ): Boolean = identity(a, aOffset) == identity(b, bOffset)

    override fun hash(
        s: LineSequence,
        offset: Int,
    ): Int = identity(s, offset).hashCode()

    private fun identity(
        s: LineSequence,
        i: Int,
    ): String = if (i == s.size() - 1 && !s.endsWithNewline) s.line(i) + LINE_END_SENTINEL else s.line(i)

    private companion object {
        const val LINE_END_SENTINEL = "\u0001"
    }
}

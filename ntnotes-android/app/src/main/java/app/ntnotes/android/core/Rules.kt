package app.ntnotes.android.core

/*
 * Pure sync rules, identical to the desktop app (sync.rs / rtdb.rs / store.rs).
 * No Android classes in here so they are trivial to test.
 */

const val MAX_BACKOFF_MS = 60_000L

/** 1 s, 2 s, 4 s ... capped at 60 s. */
fun nextBackoffMs(previous: Long): Long = if (previous <= 0L) 1_000L else minOf(previous * 2, MAX_BACKOFF_MS)

/** A remote note replaces the local copy only when there is none, or it is strictly newer. */
fun remoteWins(localUpdatedAt: Long?, remoteUpdatedAt: Long): Boolean =
    localUpdatedAt == null || remoteUpdatedAt > localUpdatedAt

enum class UploadDecision { Put, AlreadyThere, RemoteNewer }

/** Compare-and-swap rule: never overwrite a newer remote note with an older local one. */
fun decideUpload(localUpdatedAt: Long, remoteUpdatedAt: Long?): UploadDecision = when {
    remoteUpdatedAt == null -> UploadDecision.Put
    remoteUpdatedAt > localUpdatedAt -> UploadDecision.RemoteNewer
    remoteUpdatedAt == localUpdatedAt -> UploadDecision.AlreadyThere
    else -> UploadDecision.Put
}

/** updatedAt of a local edit: now, but always strictly newer than the previous version. */
fun nextUpdatedAt(nowMs: Long, previous: Long?): Long =
    if (previous == null) nowMs else maxOf(nowMs, previous + 1)

fun firstLine(s: String): String {
    for (line in s.lineSequence()) {
        val t = line.trim()
        if (t.isNotEmpty()) return t
    }
    return ""
}

/** Pattern for SQL `LIKE ? ESCAPE '\'`. */
fun likePattern(query: String): String =
    "%" + query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

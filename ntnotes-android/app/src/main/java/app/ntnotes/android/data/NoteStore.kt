package app.ntnotes.android.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.ntnotes.android.core.firstLine
import app.ntnotes.android.core.likePattern
import app.ntnotes.android.core.nextUpdatedAt
import app.ntnotes.android.core.remoteWins
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Local copy of one user's notes (SQLite, WAL). The UI reads only from here, so everything is
 * instant and works offline; [app.ntnotes.android.sync.SyncEngine] mirrors it with Firebase.
 *
 * All WRITES must run on Graph.dbDispatcher (a single thread) so read-modify-write steps are ordered.
 * Reads may run anywhere.
 */
class NoteStore(context: Context, uid: String) {

    private class Helper(context: Context, name: String) : SQLiteOpenHelper(context, name, null, 1) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.enableWriteAheadLogging()
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE notes(
                    id TEXT PRIMARY KEY NOT NULL,
                    title TEXT NOT NULL,
                    body TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    deleted INTEGER NOT NULL DEFAULT 0,
                    device_id TEXT NOT NULL,
                    dirty INTEGER NOT NULL DEFAULT 0
                )"""
            )
            db.execSQL("CREATE INDEX notes_list ON notes(deleted, updated_at DESC)")
            db.execSQL("CREATE INDEX notes_dirty ON notes(dirty) WHERE dirty = 1")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    private val helper = Helper(
        context.applicationContext,
        "notes_" + uid.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("") + ".db",
    )
    private val db: SQLiteDatabase get() = helper.writableDatabase

    /** Bumped after every write; the list and the sync status re-query when it changes. */
    val changes = MutableStateFlow(0L)

    /** Ids of notes replaced by a newer version from the server. */
    val remoteChanged = MutableSharedFlow<String>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private fun touch() = changes.update { it + 1 }

    // ------------------------------------------------------------------ reads

    fun get(id: String): Note? =
        db.rawQuery(
            "SELECT id, title, body, created_at, updated_at, deleted, device_id FROM notes WHERE id = ?",
            arrayOf(id),
        ).use { c -> if (c.moveToFirst()) readNote(c) else null }

    fun list(query: String): List<NoteItem> {
        val q = query.trim()
        val sql = StringBuilder("SELECT id, title, substr(body, 1, 300), updated_at, dirty FROM notes WHERE deleted = 0")
        val args = ArrayList<String>(2)
        if (q.isNotEmpty()) {
            sql.append(" AND (title LIKE ? ESCAPE '\\' OR body LIKE ? ESCAPE '\\')")
            val p = likePattern(q)
            args.add(p)
            args.add(p)
        }
        sql.append(" ORDER BY updated_at DESC")
        return db.rawQuery(sql.toString(), args.toTypedArray()).use { c ->
            val out = ArrayList<NoteItem>(c.count)
            while (c.moveToNext()) {
                out.add(NoteItem(c.getString(0), c.getString(1), firstLine(c.getString(2)), c.getLong(3), c.getInt(4) != 0))
            }
            out
        }
    }

    /** Notes waiting to be uploaded, oldest first. */
    fun dirty(limit: Int): List<Note> =
        db.rawQuery(
            "SELECT id, title, body, created_at, updated_at, deleted, device_id FROM notes WHERE dirty = 1 ORDER BY updated_at LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c ->
            val out = ArrayList<Note>()
            while (c.moveToNext()) out.add(readNote(c))
            out
        }

    fun dirtyCount(): Int =
        db.rawQuery("SELECT COUNT(*) FROM notes WHERE dirty = 1", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** id -> updatedAt for every local note (tombstones included). */
    fun allMeta(): Map<String, Long> =
        db.rawQuery("SELECT id, updated_at FROM notes", null).use { c ->
            val out = HashMap<String, Long>(c.count)
            while (c.moveToNext()) out[c.getString(0)] = c.getLong(1)
            out
        }

    // ------------------------------------------------------------------ writes (dbDispatcher only)

    /**
     * A local edit. Returns the new updatedAt, or 0 when nothing was stored
     * (a brand-new note that is still empty is never stored).
     * An edited note is alive again, even if it was deleted meanwhile.
     */
    fun saveLocal(id: String, title: String, body: String, deviceId: String): Long {
        var result = 0L
        db.beginTransaction()
        try {
            val row = existingTimes(id)
            val createdAt: Long? = row?.first
            val prevUpdated: Long? = row?.second
            if (prevUpdated == null && title.isBlank() && body.isBlank()) return 0L
            val now = System.currentTimeMillis()
            val updated = nextUpdatedAt(now, prevUpdated)
            val cv = ContentValues(8)
            cv.put("id", id)
            cv.put("title", title)
            cv.put("body", body)
            cv.put("created_at", createdAt ?: now)
            cv.put("updated_at", updated)
            cv.put("deleted", 0)
            cv.put("device_id", deviceId)
            cv.put("dirty", 1)
            db.insertWithOnConflict("notes", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
            result = updated
        } finally {
            db.endTransaction()
        }
        if (result > 0) touch()
        return result
    }

    /** Delete = tombstone (kept and synced), exactly like the desktop app. */
    fun delete(id: String, title: String, body: String, deviceId: String): Boolean {
        var ok = false
        db.beginTransaction()
        try {
            val row = existingTimes(id) ?: return false // never stored (empty draft)
            val createdAt = row.first
            val prevUpdated = row.second
            val cv = ContentValues(8)
            cv.put("id", id)
            cv.put("title", title)
            cv.put("body", body)
            cv.put("created_at", createdAt)
            cv.put("updated_at", nextUpdatedAt(System.currentTimeMillis(), prevUpdated))
            cv.put("deleted", 1)
            cv.put("device_id", deviceId)
            cv.put("dirty", 1)
            db.insertWithOnConflict("notes", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
            ok = true
        } finally {
            db.endTransaction()
        }
        if (ok) touch()
        return ok
    }

    /** A note from the server. Newest updatedAt wins; an obsolete queued local edit is dropped. */
    fun mergeRemote(n: Note): Boolean {
        var changed = false
        db.beginTransaction()
        try {
            val local: Long? = db.rawQuery("SELECT updated_at FROM notes WHERE id = ?", arrayOf(n.id))
                .use { c -> if (c.moveToFirst()) c.getLong(0) else null }
            if (remoteWins(local, n.updatedAt)) {
                val cv = ContentValues(8)
                cv.put("id", n.id)
                cv.put("title", n.title)
                cv.put("body", n.body)
                cv.put("created_at", n.createdAt)
                cv.put("updated_at", n.updatedAt)
                cv.put("deleted", if (n.deleted) 1 else 0)
                cv.put("device_id", n.deviceId)
                cv.put("dirty", 0)
                db.insertWithOnConflict("notes", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                db.setTransactionSuccessful()
                changed = true
            }
        } finally {
            db.endTransaction()
        }
        if (changed) {
            touch()
            remoteChanged.tryEmit(n.id)
        }
        return changed
    }

    /** The version with this updatedAt is on the server. A newer local edit stays queued. */
    fun markClean(id: String, updatedAt: Long) {
        val cv = ContentValues(1)
        cv.put("dirty", 0)
        val rows = db.update("notes", cv, "id = ? AND updated_at = ?", arrayOf(id, updatedAt.toString()))
        if (rows > 0) touch()
    }

    fun markDirty(id: String) {
        val cv = ContentValues(1)
        cv.put("dirty", 1)
        val rows = db.update("notes", cv, "id = ? AND dirty = 0", arrayOf(id))
        if (rows > 0) touch()
    }

    fun close() {
        try {
            helper.close()
        } catch (_: Throwable) {
        }
    }

    /** (createdAt, updatedAt) of a stored note, or null. */
    private fun existingTimes(id: String): Pair<Long, Long>? =
        db.rawQuery("SELECT created_at, updated_at FROM notes WHERE id = ?", arrayOf(id))
            .use { c -> if (c.moveToFirst()) Pair(c.getLong(0), c.getLong(1)) else null }

    private fun readNote(c: android.database.Cursor) = Note(
        id = c.getString(0),
        title = c.getString(1),
        body = c.getString(2),
        createdAt = c.getLong(3),
        updatedAt = c.getLong(4),
        deleted = c.getInt(5) != 0,
        deviceId = c.getString(6),
    )
}

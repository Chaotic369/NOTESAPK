package app.ntnotes.android.data

/** Shared JSON contract with the desktop app: users/{uid}/notes/{id}. */
data class Note(
    val id: String,
    val title: String,
    val body: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean,
    val deviceId: String,
) {
    /** Exactly the seven fields the database rules allow. */
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "title" to title,
        "body" to body,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt,
        "deleted" to deleted,
        "deviceId" to deviceId,
    )
}

/** Light row for the list: the body is cut to a short preview inside SQLite. */
data class NoteItem(
    val id: String,
    val title: String,
    val preview: String,
    val updatedAt: Long,
    val pending: Boolean,
)

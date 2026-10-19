@file:OptIn(ExperimentalCoroutinesApi::class)

package app.ntnotes.android.sync

import app.ntnotes.android.core.UploadDecision
import app.ntnotes.android.core.decideUpload
import app.ntnotes.android.core.nextBackoffMs
import app.ntnotes.android.data.Note
import app.ntnotes.android.data.NoteStore
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

enum class SyncStatus { Synced, Syncing, Offline }

/**
 * Firebase Realtime Database sync, same contract as the desktop app:
 *   users/{uid}/notes/{noteId} = { id, title, body, createdAt, updatedAt, deleted, deviceId }
 *
 *  - Downstream: one child listener (the SDK keeps a websocket open and, with disk persistence on,
 *    only transfers what changed since last time). Newest updatedAt wins.
 *  - Upstream: every local edit is stored in SQLite with dirty=1 first (so nothing is lost offline or
 *    when the app is killed), then uploaded with a transaction that refuses to overwrite a newer
 *    remote note (compare-and-swap).
 */
class SyncEngine(
    private val uid: String,
    private val store: NoteStore,
    private val scope: CoroutineScope,
    private val dbDispatcher: CoroutineDispatcher,
    database: FirebaseDatabase,
) {
    private sealed class Inbound {
        class Child(val snap: DataSnapshot) : Inbound()
        class Initial(val snap: DataSnapshot) : Inbound()
    }

    private sealed class Outcome {
        object Done : Outcome()
        class Superseded(val remote: Note) : Outcome()
        class Failed(val message: String) : Outcome()
    }

    private val notesRef = database.getReference("users").child(uid).child("notes")
    private val connectedRef = database.getReference(".info/connected")

    private val connected = MutableStateFlow(false)
    private val initialDone = MutableStateFlow(false)

    /** Last sync problem (e.g. permission denied); cleared by the next success. */
    val lastError = MutableStateFlow<String?>(null)

    private val kicks = Channel<Unit>(Channel.CONFLATED)
    private val inbox = Channel<Inbound>(Channel.UNLIMITED)

    private var childListener: ChildEventListener? = null
    private var initialListener: ValueEventListener? = null
    private var connectedListener: ValueEventListener? = null

    val status: StateFlow<SyncStatus> =
        combine(connected, initialDone, lastError, store.changes) { c, i, e, _ -> Triple(c, i, e) }
            .mapLatest { (c, i, e) ->
                when {
                    !c || (e != null && !i) -> SyncStatus.Offline
                    !i || store.dirtyCount() > 0 -> SyncStatus.Syncing
                    else -> SyncStatus.Synced
                }
            }
            .flowOn(Dispatchers.IO)
            .stateIn(scope, SharingStarted.Eagerly, SyncStatus.Syncing)

    fun start() {
        // Everything coming from Firebase is applied on the single DB thread, never on the main thread.
        scope.launch(dbDispatcher) {
            for (m in inbox) {
                try {
                    handle(m)
                } catch (e: Exception) {
                    lastError.value = e.message ?: e.javaClass.simpleName
                }
            }
        }
        scope.launch { pusher() }

        connectedListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val c = snapshot.value == true
                connected.value = c
                if (c) kicks.trySend(Unit)
            }

            override fun onCancelled(error: DatabaseError) {}
        }.also { connectedRef.addValueEventListener(it) }

        childListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                inbox.trySend(Inbound.Child(snapshot))
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                inbox.trySend(Inbound.Child(snapshot))
            }

            // The desktop app never deletes a note because it vanished remotely; neither do we.
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                lastError.value = describe(error)
            }
        }.also { notesRef.addChildEventListener(it) }

        // Fires once, after all the child events of the initial data. Shares the listener above.
        initialListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                inbox.trySend(Inbound.Initial(snapshot))
            }

            override fun onCancelled(error: DatabaseError) {
                lastError.value = describe(error)
            }
        }.also { notesRef.addListenerForSingleValueEvent(it) }
    }

    fun stop() {
        childListener?.let { notesRef.removeEventListener(it) }
        initialListener?.let { notesRef.removeEventListener(it) }
        connectedListener?.let { connectedRef.removeEventListener(it) }
        childListener = null
        initialListener = null
        connectedListener = null
        inbox.close()
        scope.cancel()
    }

    /** Call after every local write: uploads whatever is dirty as soon as we are online. */
    fun kick() {
        kicks.trySend(Unit)
    }

    // ------------------------------------------------------------------ downstream

    private fun handle(m: Inbound) {
        when (m) {
            is Inbound.Child -> parseNote(m.snap)?.let { store.mergeRemote(it) }
            is Inbound.Initial -> reconcile(m.snap)
        }
    }

    /**
     * After the first full snapshot: every local note the server lacks, or has an older copy of,
     * (tombstones included) is queued for upload.
     */
    private fun reconcile(snap: DataSnapshot) {
        val remote = HashMap<String, Long>()
        for (c in snap.children) {
            val key = c.key ?: continue
            val updated = (c.child("updatedAt").value as? Number)?.toLong() ?: continue
            remote[key] = updated
        }
        for ((id, localUpdated) in store.allMeta()) {
            val r = remote[id]
            if (r == null || localUpdated > r) store.markDirty(id)
        }
        lastError.value = null
        initialDone.value = true
        kicks.trySend(Unit)
    }

    // ------------------------------------------------------------------ upstream

    private suspend fun pusher() {
        var backoff = 0L
        while (true) {
            kicks.receive()
            while (connected.value && initialDone.value) {
                val batch = withContext(Dispatchers.IO) { store.dirty(25) }
                if (batch.isEmpty()) break
                val gate = Semaphore(4)
                val allOk = coroutineScope {
                    batch.map { n -> async { gate.withPermit { upload(n) } } }.awaitAll().all { it }
                }
                if (allOk) {
                    backoff = 0L
                } else {
                    backoff = nextBackoffMs(backoff)
                    delay(backoff)
                }
            }
        }
    }

    private suspend fun upload(n: Note): Boolean {
        val outcome = withTimeoutOrNull(30_000L) { transact(n) } ?: return false
        return when (outcome) {
            is Outcome.Done -> {
                withContext(dbDispatcher) { store.markClean(n.id, n.updatedAt) }
                lastError.value = null
                true
            }
            is Outcome.Superseded -> {
                withContext(dbDispatcher) { store.mergeRemote(outcome.remote) }
                lastError.value = null
                true
            }
            is Outcome.Failed -> {
                lastError.value = outcome.message
                false
            }
        }
    }

    /** Compare-and-swap: write only if the server has no copy or an older one. */
    private suspend fun transact(n: Note): Outcome = suspendCancellableCoroutine { cont ->
        notesRef.child(n.id).runTransaction(object : Transaction.Handler {
            override fun doTransaction(currentData: MutableData): Transaction.Result {
                val remoteUpdated = (currentData.child("updatedAt").value as? Number)?.toLong()
                return if (decideUpload(n.updatedAt, remoteUpdated) == UploadDecision.Put) {
                    currentData.value = n.toMap()
                    Transaction.success(currentData)
                } else {
                    Transaction.abort()
                }
            }

            override fun onComplete(error: DatabaseError?, committed: Boolean, currentData: DataSnapshot?) {
                val outcome: Outcome = when {
                    error != null -> Outcome.Failed(describe(error))
                    committed -> Outcome.Done
                    else -> {
                        val remote = currentData?.let { parseNote(it) }
                        when {
                            remote == null -> Outcome.Failed("The server copy of a note could not be read.")
                            remote.updatedAt > n.updatedAt -> Outcome.Superseded(remote)
                            else -> Outcome.Done // the server already has exactly this version
                        }
                    }
                }
                if (cont.isActive) cont.resume(outcome)
            }
        }, false)
    }

    // ------------------------------------------------------------------ helpers

    private fun parseNote(s: DataSnapshot): Note? {
        val id = s.key ?: return null
        val updated = (s.child("updatedAt").value as? Number)?.toLong() ?: return null
        return Note(
            id = id,
            title = s.child("title").value as? String ?: "",
            body = s.child("body").value as? String ?: "",
            createdAt = (s.child("createdAt").value as? Number)?.toLong() ?: updated,
            updatedAt = updated,
            deleted = s.child("deleted").value as? Boolean ?: false,
            deviceId = s.child("deviceId").value as? String ?: "",
        )
    }

    private fun describe(e: DatabaseError): String = when (e.code) {
        DatabaseError.PERMISSION_DENIED ->
            "Firebase refused access (permission denied). Check the Realtime Database rules and that you are signed in."
        DatabaseError.DISCONNECTED, DatabaseError.NETWORK_ERROR, DatabaseError.UNAVAILABLE ->
            "No connection to Firebase. Changes are kept and will sync later."
        else -> e.message
    }
}

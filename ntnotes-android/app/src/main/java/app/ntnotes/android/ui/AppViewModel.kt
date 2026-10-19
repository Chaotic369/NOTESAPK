@file:OptIn(ExperimentalCoroutinesApi::class)

package app.ntnotes.android.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ntnotes.android.Graph
import app.ntnotes.android.auth.AuthFailure
import app.ntnotes.android.auth.AuthRepo
import app.ntnotes.android.data.NoteItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** The note that is open in the editor. Text lives in Compose state so typing never waits for a database. */
class EditorState(val id: String, val isNew: Boolean) {
    var title by mutableStateOf("")
    var body by mutableStateOf("")
    var updatedAt by mutableLongStateOf(0L)
    var loaded by mutableStateOf(isNew)
}

class AppViewModel : ViewModel() {

    // ------------------------------------------------------------------ list

    val query = MutableStateFlow("")

    /** null while the first query is running. */
    val notes: StateFlow<List<NoteItem>?> = Graph.session
        .flatMapLatest { s ->
            if (s == null) flowOf<List<NoteItem>?>(emptyList())
            else combine(query, s.store.changes) { q, _ -> q }.mapLatest { q -> s.store.list(q) }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ------------------------------------------------------------------ editor

    var editor by mutableStateOf<EditorState?>(null)
        private set

    private var editDirty = false
    private var saveJob: Job? = null

    init {
        // A newer version of the open note arrived from another device: show it, unless we are mid-edit.
        viewModelScope.launch {
            Graph.session
                .flatMapLatest { it?.store?.remoteChanged ?: emptyFlow() }
                .collect { id ->
                    val e = editor
                    if (e != null && e.id == id && !editDirty) load(e)
                }
        }
    }

    fun newNote() {
        flush()
        editor = EditorState(UUID.randomUUID().toString(), isNew = true)
    }

    fun open(id: String) {
        flush()
        val e = EditorState(id, isNew = false)
        editor = e
        load(e)
    }

    private fun load(e: EditorState) {
        val store = Graph.session.value?.store ?: return
        viewModelScope.launch {
            val n = withContext(Dispatchers.IO) { store.get(e.id) }
            if (n != null && editor === e && !editDirty) {
                e.title = n.title
                e.body = n.body
                e.updatedAt = n.updatedAt
                e.loaded = true
            }
        }
    }

    fun onTitle(text: String) {
        val e = editor ?: return
        e.title = text
        scheduleSave()
    }

    fun onBody(text: String) {
        val e = editor ?: return
        e.body = text
        scheduleSave()
    }

    private fun scheduleSave() {
        editDirty = true
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(600)
            commit()
        }
    }

    /** Writes the open note to SQLite (instantly durable) and wakes the uploader. */
    private fun commit() {
        val e = editor ?: return
        if (!editDirty || !e.loaded) return
        editDirty = false
        val session = Graph.session.value ?: return
        val (id, title, body) = Triple(e.id, e.title, e.body)
        Graph.appScope.launch(Graph.dbDispatcher) {
            val updated = session.store.saveLocal(id, title, body, Graph.deviceId)
            if (updated > 0) {
                e.updatedAt = updated
                session.engine.kick()
            }
        }
    }

    /** Save right now (leaving the editor, app going to background). */
    fun flush() {
        saveJob?.cancel()
        commit()
    }

    fun close() {
        flush()
        editor = null
    }

    fun deleteOpenNote() {
        val e = editor ?: return
        saveJob?.cancel()
        editDirty = false
        editor = null
        val session = Graph.session.value ?: return
        val (id, title, body) = Triple(e.id, e.title, e.body)
        Graph.appScope.launch(Graph.dbDispatcher) {
            if (session.store.delete(id, title, body, Graph.deviceId)) session.engine.kick()
        }
    }

    override fun onCleared() {
        flush()
    }

    // ------------------------------------------------------------------ account

    var authBusy by mutableStateOf(false)
        private set
    var authError by mutableStateOf<String?>(null)
    var authInfo by mutableStateOf<String?>(null)

    private fun auth(block: suspend () -> Unit) {
        if (authBusy) return
        authBusy = true
        authError = null
        authInfo = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthFailure) {
                authError = e.message
            } catch (e: Exception) {
                authError = e.message ?: "Something went wrong."
            } finally {
                authBusy = false
            }
        }
    }

    fun signIn(email: String, password: String) = auth {
        AuthRepo.signIn(email, password)
        Graph.lastEmail = email.trim()
    }

    fun signUp(email: String, password: String) = auth {
        AuthRepo.signUp(email, password)
        Graph.lastEmail = email.trim()
    }

    fun resetPassword(email: String) = auth {
        AuthRepo.sendPasswordReset(email)
        authInfo = "Password reset email sent. Check your inbox."
    }

    fun google(activity: Context) = auth { AuthRepo.signInWithGoogle(activity) }

    fun signOut(context: Context) {
        flush()
        editor = null
        query.value = ""
        Graph.appScope.launch { AuthRepo.signOut(context.applicationContext) }
    }
}

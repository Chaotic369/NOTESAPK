package app.ntnotes.android

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import app.ntnotes.android.data.NoteStore
import app.ntnotes.android.sync.SyncEngine
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.Executors

/** One signed-in user: their local store and the engine that syncs it. */
class Session(val uid: String, val store: NoteStore, val engine: SyncEngine)

/** App-wide singletons (kept deliberately tiny: no DI framework, nothing slow at startup). */
object Graph {
    lateinit var app: Application
        private set

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The only thread that writes to SQLite, so read-modify-write steps are strictly ordered. */
    val dbDispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "notes-db") }.asCoroutineDispatcher()

    private lateinit var prefs: SharedPreferences

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    /** null = follow the system theme. */
    private val _darkPref = MutableStateFlow<Boolean?>(null)
    val darkPref: StateFlow<Boolean?> = _darkPref.asStateFlow()

    val deviceId: String by lazy {
        prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("deviceId", it).apply()
        }
    }

    var lastEmail: String
        get() = prefs.getString("lastEmail", "") ?: ""
        set(v) {
            prefs.edit().putString("lastEmail", v).apply()
        }

    fun init(application: Application) {
        app = application
        prefs = application.getSharedPreferences("ntnotes", Context.MODE_PRIVATE)
        _darkPref.value = if (prefs.contains("dark")) prefs.getBoolean("dark", true) else null

        // Must happen before anything else touches the database. The disk cache means that on the next
        // launch Firebase only has to fetch what changed.
        try {
            FirebaseDatabase.getInstance().setPersistenceEnabled(true)
        } catch (_: Throwable) {
        }

        val auth = FirebaseAuth.getInstance()
        onUser(auth.currentUser?.uid)
        auth.addAuthStateListener { onUser(it.currentUser?.uid) }
    }

    fun setDark(dark: Boolean) {
        _darkPref.value = dark
        prefs.edit().putBoolean("dark", dark).apply()
    }

    @Synchronized
    private fun onUser(uid: String?) {
        val current = _session.value
        if (current?.uid == uid) return
        current?.let {
            it.engine.stop()
            it.store.close()
        }
        _session.value = uid?.let { openSession(it) }
    }

    private fun openSession(uid: String): Session {
        val store = NoteStore(app, uid)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = SyncEngine(uid, store, scope, dbDispatcher, FirebaseDatabase.getInstance())
        engine.start()
        return Session(uid, store, engine)
    }
}

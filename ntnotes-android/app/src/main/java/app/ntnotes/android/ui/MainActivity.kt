package app.ntnotes.android.ui

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ntnotes.android.Graph

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val darkPref by Graph.darkPref.collectAsStateWithLifecycle()
            val dark = darkPref ?: isSystemInDarkTheme()

            // Keep the status / navigation bar icons readable when the in-app theme differs from the system one.
            DisposableEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                } else {
                    SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }

            NtTheme(dark) { AppRoot(vm) }
        }
    }

    /** Anything typed in the last fraction of a second is written to disk before we may be killed. */
    override fun onStop() {
        vm.flush()
        super.onStop()
    }
}

@Composable
private fun AppRoot(vm: AppViewModel) {
    val session by Graph.session.collectAsStateWithLifecycle()
    val s = session
    if (s == null) {
        LoginScreen(vm)
    } else {
        val editor = vm.editor
        if (editor != null) EditorScreen(vm, editor) else NotesScreen(vm, s)
    }
}

package app.ntnotes.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.remember

@Composable
fun EditorScreen(vm: AppViewModel, editor: EditorState) {
    BackHandler { vm.close() }

    val onSurface = MaterialTheme.colorScheme.onSurface
    val hint = MaterialTheme.colorScheme.onSurfaceVariant
    val cursor = SolidColor(MaterialTheme.colorScheme.primary)
    val titleFocus = remember { FocusRequester() }

    LaunchedEffect(editor.id) {
        if (editor.isNew) titleFocus.requestFocus()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.close() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Box(Modifier.weight(1f))
                IconButton(onClick = { vm.deleteOpenNote() }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete note")
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().padding(horizontal = 16.dp)) {
            BasicTextField(
                value = editor.title,
                onValueChange = { vm.onTitle(it) },
                enabled = editor.loaded,
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineSmall.copy(color = onSurface),
                cursorBrush = cursor,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().focusRequester(titleFocus),
                decorationBox = { inner ->
                    Box {
                        if (editor.title.isEmpty()) {
                            Text("Title", style = MaterialTheme.typography.headlineSmall, color = hint.copy(alpha = 0.6f))
                        }
                        inner()
                    }
                },
            )
            if (editor.updatedAt > 0L) {
                Text(
                    "Last edited " + formatFull(editor.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = hint,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                BasicTextField(
                    value = editor.body,
                    onValueChange = { vm.onBody(it) },
                    enabled = editor.loaded,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = onSurface, fontSize = 16.sp, lineHeight = 24.sp),
                    cursorBrush = cursor,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 48.dp),
                    decorationBox = { inner ->
                        Box {
                            if (editor.body.isEmpty()) {
                                Text("Write something…", style = MaterialTheme.typography.bodyLarge, color = hint.copy(alpha = 0.6f))
                            }
                            inner()
                        }
                    },
                )
            }
        }
    }
}

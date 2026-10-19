package app.ntnotes.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ntnotes.android.Graph
import app.ntnotes.android.Session
import app.ntnotes.android.auth.AuthRepo
import app.ntnotes.android.core.firstLine
import app.ntnotes.android.data.NoteItem
import app.ntnotes.android.sync.SyncStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private val dayFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
private val fullFmt: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

fun formatShort(ms: Long): String {
    val zone = ZoneId.systemDefault()
    val t = Instant.ofEpochMilli(ms).atZone(zone)
    return if (t.toLocalDate() == LocalDate.now(zone)) timeFmt.format(t) else dayFmt.format(t)
}

fun formatFull(ms: Long): String =
    if (ms <= 0L) "" else fullFmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

@Composable
fun NotesScreen(vm: AppViewModel, session: Session) {
    val context = LocalContext.current
    val notes by vm.notes.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val status by session.engine.status.collectAsStateWithLifecycle()
    val error by session.engine.lastError.collectAsStateWithLifecycle()
    val darkPref by Graph.darkPref.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var errorDismissed by remember(error) { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            FloatingActionButton(onClick = { vm.newNote() }) { Icon(Icons.Filled.Add, contentDescription = "New note") }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // header
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("NT Notes", style = MaterialTheme.typography.titleLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val (color, label) = when (status) {
                            SyncStatus.Synced -> Color(0xFF4CAF50) to "Synced"
                            SyncStatus.Syncing -> Color(0xFFFFB300) to "Syncing"
                            SyncStatus.Offline -> Color.Gray to "Offline"
                        }
                        Box(Modifier.size(8.dp).background(color, CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            label + (notes?.size?.let { "  ·  $it notes" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Menu") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        AuthRepo.email?.let {
                            DropdownMenuItem(text = { Text(it, style = MaterialTheme.typography.bodySmall) }, onClick = {}, enabled = false)
                        }
                        val isDark = darkPref ?: (context.resources.configuration.uiMode and
                            android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)
                        DropdownMenuItem(
                            text = { Text(if (isDark) "Light theme" else "Dark theme") },
                            onClick = {
                                menuOpen = false
                                Graph.setDark(!isDark)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Sign out") },
                            onClick = {
                                menuOpen = false
                                vm.signOut(context)
                            },
                        )
                    }
                }
            }

            // search
            OutlinedTextField(
                value = query,
                onValueChange = { vm.query.value = it },
                placeholder = { Text("Search notes") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            )

            // sync problem banner
            val shownError = error
            if (shownError != null && !errorDismissed) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp))
                        .padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        shownError,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    TextButton(onClick = { errorDismissed = true }) { Text("Dismiss") }
                }
            }

            // list
            val list = notes
            when {
                list == null -> Box(Modifier.fillMaxSize())
                list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (query.isBlank()) "No notes yet" else "No matching notes",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp)) {
                    items(list, key = { it.id }) { item -> NoteRow(item) { vm.open(item.id) } }
                }
            }
        }
    }
}

@Composable
private fun NoteRow(item: NoteItem, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val title = firstLine(item.title).ifEmpty { "Untitled" }
                Text(
                    title,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    formatShort(item.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.preview.isNotEmpty()) {
                Text(
                    item.preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

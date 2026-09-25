package kr.co.donghyun.turtlelauncher.presentation.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.BgBorder
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.BgDark
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.BgSurface
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.Turtle
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.TextMain
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.TextSub
import java.io.File

/** A bounded, real file browser for instance files, logs, saves, packs, and screenshots. */
@Composable
fun FileBrowserScreen(
    root: File,
    directory: File,
    onNavigate: (File) -> Unit,
    onBack: () -> Unit,
    onDelete: (File) -> Unit,
) {
    var deleteTarget by remember { mutableStateOf<File?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }
    val entries = remember(directory, directory.lastModified(), refreshTick) {
        directory.listFiles()?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            ?: emptyList()
    }
    val canGoUp = directory != root && directory.parentFile?.canonicalFile?.path?.startsWith(root.canonicalPath) == true

    Column(Modifier.fillMaxSize().background(BgDark)) {
        Row(
            Modifier.fillMaxWidth().background(BgSurface).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("Back", color = TextSub) }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("Files", color = TextMain, fontWeight = FontWeight.Bold)
                Text(directory.relativeToOrNull(root)?.path ?: "Instances", color = TextSub, fontSize = 10.sp)
            }
            if (canGoUp) TextButton(onClick = { directory.parentFile?.let(onNavigate) }) { Text("Up", color = Turtle) }
        }
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("This folder is empty", color = TextSub)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(entries, key = { it.absolutePath }) { entry ->
                    Row(
                        Modifier.fillMaxWidth().background(BgSurface, RoundedCornerShape(10.dp))
                            .border(1.dp, BgBorder, RoundedCornerShape(10.dp))
                            .clickable { if (entry.isDirectory) onNavigate(entry) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (entry.isDirectory) "📁" else "📄", fontSize = 18.sp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry.name, color = TextMain, maxLines = 1)
                            Text(if (entry.isDirectory) "Folder" else "${entry.length()} bytes", color = TextSub, fontSize = 10.sp)
                        }
                        if (!entry.isDirectory || entry.listFiles().isNullOrEmpty()) {
                            TextButton(onClick = { deleteTarget = entry }) { Text("Delete", color = TextSub, fontSize = 10.sp) }
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete file?", color = TextMain) },
            text = { Text("Remove ${target.name}? This cannot be undone.", color = TextSub) },
            confirmButton = {
                TextButton(onClick = { onDelete(target); refreshTick++; deleteTarget = null }) {
                    Text("Delete", color = Turtle)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel", color = TextSub) } },
        )
    }
}

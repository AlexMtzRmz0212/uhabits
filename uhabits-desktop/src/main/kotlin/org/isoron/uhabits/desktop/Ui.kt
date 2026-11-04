package org.isoron.uhabits.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.models.HabitType
import java.nio.file.Paths
import javax.swing.JFileChooser

private const val DAYS = 7

private val appColors = lightColorScheme(
    primary = Color(0xFF00897B),
    onPrimary = Color.White,
    background = Color(0xFFF4F4F4),
    surface = Color.White
)

@Composable
fun App(model: AppModel) {
    MaterialTheme(colorScheme = appColors) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val openId = model.openHabitId
            when {
                model.folder == null && !model.skippedSync -> SetupScreen(model)
                openId != null -> DetailScreen(model, openId)
                else -> MainScreen(model)
            }
        }
    }
}

private fun pickFolder(): String? {
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "Choose the sync folder"
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null
}

@Composable
private fun SetupScreen(model: AppModel) {
    var path by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(modifier = Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("My Habits", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(
                "To see your habits here, choose the folder that your phone also syncs through, " +
                    "for example your Google Drive \"Habits\" folder as it appears in File Explorer. " +
                    "A \"LoopSync\" folder is created inside it."
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text("Sync folder") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { pickFolder()?.let { path = it } }) { Text("Browse…") }
            }
            model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = path.isNotBlank(),
                    onClick = {
                        try {
                            model.chooseFolder(Paths.get(path.trim()))
                        } catch (e: Exception) {
                            model.error = "Cannot use that folder: ${e.message}"
                        }
                    }
                ) { Text("Start syncing") }
                TextButton(onClick = { model.skipSync() }) { Text("Use without syncing") }
            }
        }
    }
}

private sealed interface EditTarget {
    val row: HabitRow
    val date: LocalDate

    data class Number(override val row: HabitRow, override val date: LocalDate) : EditTarget
    data class Time(override val row: HabitRow, override val date: LocalDate) : EditTarget
}

@Composable
private fun MainScreen(model: AppModel) {
    // Reading revision makes the list rebuild after every edit or sync that changed something.
    val revision = model.revision
    val rows = remember(revision) { model.rows(DAYS) }
    var editing by remember { mutableStateOf<EditTarget?>(null) }
    var creating by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(model, onNew = { creating = true })
        HorizontalDivider()
        UpdateBanner(model)
        if (rows.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (model.folder != null) "No habits yet. They appear here after the first sync." else "No habits yet.",
                    color = Color.Gray
                )
            }
        } else {
            DayHeader(model.today())
            HorizontalDivider()
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(rows, key = { it.id }) { row ->
                    HabitRowView(
                        row,
                        onOpen = { model.openHabitId = row.id },
                        onCell = { cell ->
                            when {
                                row.type.isTimeOfDay -> editing = EditTarget.Time(row, cell.date)
                                row.type == HabitType.NUMERICAL -> editing = EditTarget.Number(row, cell.date)
                                else -> model.toggle(row.id, cell.date)
                            }
                        }
                    )
                    HorizontalDivider(color = Color(0xFFE0E0E0))
                }
            }
        }
    }

    editing?.let { target ->
        ValueDialog(
            title = target.row.name,
            date = target.date,
            isTime = target is EditTarget.Time,
            unitLabel = target.row.unit.ifEmpty { "Value" },
            initial = model.currentValue(target.row.id, target.date),
            onDismiss = { editing = null },
            onSave = { value ->
                when (target) {
                    is EditTarget.Number -> model.setNumber(target.row.id, target.date, value)
                    is EditTarget.Time -> model.setTime(target.row.id, target.date, value?.toInt())
                }
                editing = null
            }
        )
    }
    if (creating) EditorDialog(model, null, onClose = { creating = false })
    model.error?.let { message ->
        AlertDialog(
            onDismissRequest = { model.error = null },
            confirmButton = { TextButton(onClick = { model.error = null }) { Text("OK") } },
            text = { Text(message) }
        )
    }
}

/** Shown when a newer version of the app is on GitHub. */
@Composable
private fun UpdateBanner(model: AppModel) {
    val info = model.update ?: return
    if (model.updateDismissed && !model.updateBusy) return
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            model.updateStatus ?: "A new version of My Habits is available (build ${info.build}).",
            color = Color.White,
            modifier = Modifier.weight(1f)
        )
        if (!model.updateBusy) {
            Button(
                onClick = { model.installUpdate() },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF00695C))
            ) { Text("Install and restart") }
            TextButton(onClick = { model.updateDismissed = true }) { Text("Later", color = Color.White) }
        }
    }
}

@Composable
private fun TopBar(model: AppModel, onNew: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Color(0xFF333333)).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Habits", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onNew) { Text("+ New habit", color = Color.White) }
        if (model.folder != null) {
            Text(model.syncStatus, color = Color(0xFFE0E0E0), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Button(enabled = !model.syncing, onClick = { model.syncNow() }) { Text("Sync now") }
            TextButton(onClick = { pickFolder()?.let { model.chooseFolder(Paths.get(it)) } }) {
                Text("Folder…", color = Color.White)
            }
        } else {
            TextButton(onClick = { pickFolder()?.let { model.chooseFolder(Paths.get(it)) } }) {
                Text("Set up sync…", color = Color.White)
            }
        }
    }
}

private val weekdayNames = listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")

internal fun toJava(date: LocalDate): java.time.LocalDate = java.time.LocalDate.of(2000, 1, 1).plusDays(date.daysSince2000.toLong())

@Composable
private fun DayHeader(today: LocalDate) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Color.White).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.width(64.dp))
        for (i in 0 until DAYS) {
            val d = toJava(today.minus(i))
            Column(modifier = Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(weekdayNames[d.dayOfWeek.value % 7], fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                Text(d.dayOfMonth.toString(), fontSize = 13.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun HabitRowView(row: HabitRow, onOpen: () -> Unit, onCell: (Cell) -> Unit) {
    val habitColor = Color(0xFF000000 or row.color.toLong().and(0xFFFFFF)).copy(alpha = 1f)
    Row(
        modifier = Modifier.fillMaxWidth().background(Color.White).height(56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.width(5.dp).fillMaxSize().background(habitColor))
        Column(modifier = Modifier.weight(1f).clickable(onClick = onOpen).padding(start = 12.dp, end = 8.dp)) {
            Text(row.name, color = habitColor, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (row.category.isNotEmpty()) {
                Text(row.category, color = Color.Gray, fontSize = 11.sp, maxLines = 1)
            }
        }
        Text(
            "${(row.score * 100).toInt()}%",
            modifier = Modifier.width(64.dp),
            textAlign = TextAlign.Center,
            color = Color.Gray,
            fontSize = 13.sp
        )
        for (cell in row.cells) {
            CellView(cell, row, habitColor, onClick = { onCell(cell) })
        }
    }
}

@Composable
private fun CellView(cell: Cell, row: HabitRow, habitColor: Color, onClick: () -> Unit) {
    val (color, weight) = when (cell.kind) {
        CellKind.YES -> habitColor to FontWeight.Bold
        CellKind.YES_AUTO -> habitColor.copy(alpha = 0.45f) to FontWeight.Normal
        CellKind.NO -> Color(0xFF9E9E9E) to FontWeight.Bold
        CellKind.SKIP -> Color(0xFF9E9E9E) to FontWeight.Normal
        CellKind.UNKNOWN -> Color(0xFFE0E0E0) to FontWeight.Normal
        CellKind.VALUE -> habitColor to FontWeight.Medium
    }
    Box(
        modifier = Modifier.width(72.dp).fillMaxSize().clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(cell.text, color = color, fontWeight = weight, fontSize = if (cell.kind == CellKind.VALUE) 14.sp else 20.sp)
    }
}

@Composable
internal fun ValueDialog(
    title: String,
    date: LocalDate,
    isTime: Boolean,
    unitLabel: String,
    initial: Double?,
    onDismiss: () -> Unit,
    onSave: (Double?) -> Unit
) {
    var text by remember {
        mutableStateOf(
            when {
                initial == null -> ""
                isTime -> AppModel.formatTime(initial.toInt())
                else -> AppModel.formatNumber(initial)
            }
        )
    }
    val parsed: Double? = if (isTime) AppModel.parseTime(text)?.toDouble() else text.trim().replace(',', '.').toDoubleOrNull()
    val valid = text.isBlank() || parsed != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val d = toJava(date)
                Text("${weekdayNames[d.dayOfWeek.value % 7]} ${d.dayOfMonth}", color = Color.Gray)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    isError = !valid,
                    label = { Text(if (isTime) "Time (HH:MM)" else unitLabel) }
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onSave(if (text.isBlank()) null else parsed) }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onSave(null) }) { Text("Clear") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

package org.isoron.uhabits.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTapGestures
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.models.HabitPin
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.floor

internal fun habitColor(rgb: Int): Color = Color(0xFF000000L or (rgb.toLong() and 0xFFFFFF))

private fun formatDate(date: LocalDate): String =
    toJava(date).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

private val bucketNames = listOf("Day", "Week", "Month", "Quarter", "Year")

private fun percent(value: Double) = "${(value * 100).toInt()}%"

private fun signedPercent(value: Double): String {
    val p = (value * 100).toInt()
    return if (p > 0) "+$p%" else "$p%"
}

@Composable
internal fun DetailScreen(model: AppModel, habitId: Long) {
    var bucket by remember { mutableStateOf(1) }
    var editing by remember { mutableStateOf(false) }
    var editingDay by remember { mutableStateOf<LocalDate?>(null) }
    val revision = model.revision
    val d = remember(revision, bucket, habitId) { model.detail(habitId, bucket) }
    if (d == null) {
        model.openHabitId = null
        return
    }
    val color = habitColor(d.color)

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF333333)).padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { model.openHabitId = null }) { Text("← Back", color = Color.White) }
            Text(d.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            TextButton(onClick = { editing = true }) { Text("Edit", color = Color.White) }
        }
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (d.question.isNotEmpty() || d.category.isNotEmpty() || d.targetText != null) {
                Column {
                    if (d.question.isNotEmpty()) Text(d.question, fontSize = 16.sp)
                    val line = listOfNotNull(d.category.ifEmpty { null }, d.targetText).joinToString(" · ")
                    if (line.isNotEmpty()) Text(line, color = Color.Gray, fontSize = 13.sp)
                }
            }

            Card("Overview") {
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    Stat(percent(d.scoreToday), "score today", color)
                    Stat(signedPercent(d.monthDiff), "this month", color)
                    Stat(signedPercent(d.yearDiff), "this year", color)
                    Stat(d.totalCount.toString(), "total", color)
                }
            }

            Card("Score") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    bucketNames.forEachIndexed { i, name ->
                        FilterChip(selected = bucket == i, onClick = { bucket = i }, label = { Text(name) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (d.scores.isEmpty()) {
                    Text("No data yet.", color = Color.Gray)
                } else {
                    ScoreChart(d.scores, color)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatDate(d.scores.first().date), color = Color.Gray, fontSize = 11.sp)
                        Text(formatDate(d.scores.last().date), color = Color.Gray, fontSize = 11.sp)
                    }
                }
            }

            Card("History") {
                HistoryGrid(d, color, onDay = { editingDay = it })
                Text("Click a day to change it.", color = Color.Gray, fontSize = 11.sp)
            }

            Card("Best streaks") {
                if (d.streaks.isEmpty()) {
                    Text("No streaks yet.", color = Color.Gray)
                } else {
                    d.streaks.take(5).forEach { s ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${formatDate(s.start)} – ${formatDate(s.end)}", fontSize = 14.sp)
                            Text("${s.length} days", color = color, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }

    editingDay?.let { date ->
        val dismiss = { editingDay = null }
        when {
            d.type.isTimeOfDay || d.type == HabitType.NUMERICAL -> ValueDialog(
                title = d.name,
                date = date,
                isTime = d.type.isTimeOfDay,
                unitLabel = "Value",
                initial = model.currentValue(d.id, date),
                onDismiss = dismiss,
                onSave = { value ->
                    if (d.type.isTimeOfDay) model.setTime(d.id, date, value?.toInt()) else model.setNumber(d.id, date, value)
                    dismiss()
                }
            )
            else -> {
                model.toggle(d.id, date)
                dismiss()
            }
        }
    }
    if (editing) {
        EditorDialog(model, habitId, onClose = { editing = false })
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color.White).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF616161))
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun Stat(value: String, label: String, color: Color) {
    Column {
        Text(value, color = color, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Color.Gray, fontSize = 12.sp)
    }
}

@Composable
private fun ScoreChart(bars: List<ScoreBar>, color: Color) {
    Canvas(modifier = Modifier.fillMaxWidth().height(150.dp)) {
        val gridColor = Color(0xFFE0E0E0)
        for (p in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val y = size.height * (1 - p)
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        val slot = size.width / bars.size
        bars.forEachIndexed { i, bar ->
            val h = size.height * bar.value.toFloat().coerceIn(0f, 1f)
            drawRoundRect(
                color,
                topLeft = Offset(i * slot + slot * 0.15f, size.height - h),
                size = Size(slot * 0.7f, h),
                cornerRadius = CornerRadius(2f, 2f)
            )
        }
    }
}

@Composable
private fun HistoryGrid(d: DetailData, color: Color, onDay: (LocalDate) -> Unit) {
    val square = 18f
    val gap = 3f
    val step = with(LocalDensity.current) { (square + gap).dp.toPx() }
    val squarePx = with(LocalDensity.current) { square.dp.toPx() }

    val firstDay = d.firstWeekday.daysSinceSunday
    fun rowOf(date: LocalDate) = (date.dayOfWeek.daysSinceSunday - firstDay + 7) % 7
    val weekStart = d.today.daysSince2000 - rowOf(d.today)
    fun colOf(date: LocalDate) = floor((date.daysSince2000 - weekStart) / 7.0).toInt()
    val oldest = d.today.minus(maxOf(d.history.size - 1, 0))
    val minCol = colOf(oldest)
    val nCols = -minCol + 1

    val scroll = rememberScrollState()
    LaunchedEffect(nCols) { scroll.scrollTo(scroll.maxValue) }

    Box(modifier = Modifier.horizontalScroll(scroll)) {
        Canvas(
            modifier = Modifier
                .size((nCols * (square + gap)).dp, (7 * (square + gap)).dp)
                .pointerInput(d.today, nCols) {
                    detectTapGestures { offset ->
                        val col = (offset.x / step).toInt() + minCol
                        val row = (offset.y / step).toInt()
                        val days = weekStart + col * 7 + row
                        val age = d.today.daysSince2000 - days
                        if (age in d.history.indices) onDay(LocalDate(days))
                    }
                }
        ) {
            for (i in d.history.indices) {
                val date = d.today.minus(i)
                val topLeft = Offset((colOf(date) - minCol) * step, rowOf(date) * step)
                val fill = when (d.history[i]) {
                    DayKind.ON -> color
                    DayKind.DIMMED -> color.copy(alpha = 0.4f)
                    DayKind.GREY -> Color(0xFFBDBDBD)
                    DayKind.HATCHED -> Color(0xFFE0E0E0)
                    DayKind.OFF -> Color(0xFFEEEEEE)
                }
                drawRoundRect(fill, topLeft, Size(squarePx, squarePx), CornerRadius(3f, 3f))
                if (d.history[i] == DayKind.HATCHED) {
                    drawLine(Color(0xFF9E9E9E), topLeft + Offset(2f, squarePx - 2f), topLeft + Offset(squarePx - 2f, 2f), strokeWidth = 2f)
                }
            }
        }
    }
}

// --- create and edit ----------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EditorDialog(model: AppModel, habitId: Long?, onClose: () -> Unit) {
    val initial = remember { habitId?.let { model.editorFor(it) } ?: EditorData() }
    val existing = remember { model.existingTypes() }
    val categories = remember { model.categories() }
    var data by remember { mutableStateOf(initial) }
    var freqNum by remember { mutableStateOf(initial.freqNum.toString()) }
    var freqDen by remember { mutableStateOf(initial.freqDen.toString()) }
    var target by remember {
        mutableStateOf(
            when {
                initial.targetValue == 0.0 -> ""
                initial.type.isTimeOfDay -> AppModel.formatTime(initial.targetValue.toInt())
                else -> AppModel.formatNumber(initial.targetValue)
            }
        )
    }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val isNew = habitId == null
    val isTime = data.type.isTimeOfDay
    val isNumber = data.type == HabitType.NUMERICAL

    fun save() {
        val targetValue = when {
            target.isBlank() -> 0.0
            isTime -> AppModel.parseTime(target)?.toDouble() ?: run { message = "Write the target time as HH:MM."; return }
            else -> target.replace(',', '.').toDoubleOrNull() ?: run { message = "The target must be a number."; return }
        }
        val num = freqNum.toIntOrNull()
        val den = freqDen.toIntOrNull()
        if (num == null || den == null) {
            message = "The frequency must be whole numbers."
            return
        }
        val error = model.saveHabit(habitId, data.copy(freqNum = num, freqDen = den, targetValue = targetValue))
        if (error != null) message = error else onClose()
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (isNew) "New habit" else "Edit habit") },
        text = {
            Column(
                modifier = Modifier.width(460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (!isNew && initial.type == HabitType.NUMERICAL) {
                    Text("Type", fontSize = 12.sp, color = Color.Gray)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(HabitType.NUMERICAL to "Measurable", HabitType.TIME_OF_DAY to "Time of day").forEach { (type, label) ->
                            FilterChip(
                                selected = data.type == type,
                                onClick = {
                                    data = data.copy(type = type)
                                    target = ""
                                },
                                label = { Text(label) }
                            )
                        }
                    }
                    if (data.type == HabitType.TIME_OF_DAY) {
                        Text(
                            "Saved numbers become times: 6.40 turns into 06:40. Entries that are not valid times stop the change.",
                            fontSize = 12.sp, color = Color.Gray
                        )
                    }
                }
                if (isNew) {
                    Text("Type", fontSize = 12.sp, color = Color.Gray)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            HabitType.YES_NO to "Yes/No",
                            HabitType.NUMERICAL to "Measurable",
                            HabitType.TIME_OF_DAY to "Time of day",
                            HabitType.WAKE_UP to "Wake up",
                            HabitType.BEDTIME to "Bedtime"
                        ).forEach { (type, label) ->
                            FilterChip(
                                selected = data.type == type,
                                enabled = type !in existing,
                                onClick = { data = data.copy(type = type) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = data.name, onValueChange = { data = data.copy(name = it) },
                    label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = data.question, onValueChange = { data = data.copy(question = it) },
                    label = { Text("Question (for example: Did you read today?)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = data.category, onValueChange = { data = data.copy(category = it) },
                    label = { Text("Category") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                if (categories.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        categories.forEach { c ->
                            FilterChip(selected = data.category == c, onClick = { data = data.copy(category = c) }, label = { Text(c) })
                        }
                    }
                }

                Text("Color", fontSize = 12.sp, color = Color.Gray)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (i in 0..19) {
                        val rgb = org.isoron.uhabits.core.ui.views.LightTheme().color(i).let {
                            (Math.round(it.red * 255).toInt() shl 16) or (Math.round(it.green * 255).toInt() shl 8) or Math.round(it.blue * 255).toInt()
                        }
                        Box(
                            modifier = Modifier.size(28.dp).clip(CircleShape).background(habitColor(rgb))
                                .border(if (data.colorIndex == i) 3.dp else 0.dp, Color(0xFF212121), CircleShape)
                                .clickable { data = data.copy(colorIndex = i) }
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Repeat")
                    OutlinedTextField(freqNum, { freqNum = it }, singleLine = true, modifier = Modifier.width(72.dp))
                    Text(if (freqNum == "1") "time every" else "times every")
                    OutlinedTextField(freqDen, { freqDen = it }, singleLine = true, modifier = Modifier.width(72.dp))
                    Text(if (freqDen == "1") "day" else "days")
                }

                if (isNumber) {
                    OutlinedTextField(
                        value = data.unit, onValueChange = { data = data.copy(unit = it) },
                        label = { Text("Unit (for example: glasses)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
                if (isNumber || isTime) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = data.targetType == NumericalHabitType.AT_LEAST,
                            onClick = { data = data.copy(targetType = NumericalHabitType.AT_LEAST) },
                            label = { Text(if (isTime) "After" else "At least") }
                        )
                        FilterChip(
                            selected = data.targetType == NumericalHabitType.AT_MOST,
                            onClick = { data = data.copy(targetType = NumericalHabitType.AT_MOST) },
                            label = { Text(if (isTime) "Before" else "At most") }
                        )
                        OutlinedTextField(
                            value = target, onValueChange = { target = it },
                            label = { Text(if (isTime) "Target time (HH:MM)" else "Target") },
                            singleLine = true, modifier = Modifier.weight(1f)
                        )
                    }
                }
                if (data.type == HabitType.WAKE_UP || data.type == HabitType.BEDTIME) {
                    val pin = if (data.type == HabitType.WAKE_UP) HabitPin.TOP else HabitPin.BOTTOM
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = data.pin == pin,
                            onCheckedChange = { data = data.copy(pin = if (it) pin else HabitPin.NONE) }
                        )
                        Text(if (pin == HabitPin.TOP) "Always keep at the top of the list" else "Always keep at the bottom of the list")
                    }
                }
                OutlinedTextField(
                    value = data.description, onValueChange = { data = data.copy(description = it) },
                    label = { Text("Notes") }, modifier = Modifier.fillMaxWidth().height(90.dp)
                )
                message?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            }
        },
        confirmButton = { Button(onClick = { save() }) { Text("Save") } },
        dismissButton = {
            Row {
                if (!isNew) {
                    TextButton(onClick = { model.archive(habitId!!); onClose() }) { Text("Archive") }
                    TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onClose) { Text("Cancel") }
            }
        }
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete \"${data.name}\"?") },
            text = { Text("The habit and all its entries are deleted, here and on your other devices after the next sync. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { model.delete(habitId!!); confirmDelete = false; onClose() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep it") } }
        )
    }
}

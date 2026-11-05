package org.isoron.uhabits.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import org.isoron.platform.time.resetToday
import org.isoron.uhabits.core.database.EntryData
import org.isoron.uhabits.core.database.EntryRepository
import org.isoron.uhabits.core.database.HabitData
import org.isoron.uhabits.core.database.HabitRepository
import org.isoron.uhabits.core.models.TimeOfDay
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Draws the window off-screen, so the layout can be checked without opening a real window. */
@OptIn(ExperimentalComposeUiApi::class)
class RenderTest {
    private val tmp: Path = Files.createTempDirectory("myhabits-render")
    private var model: AppModel? = null

    @AfterTest
    fun cleanUp() {
        model?.close()
        resetToday()
        tmp.toFile().deleteRecursively()
    }

    private fun seed(dbPath: Path) {
        val db = DesktopDatabase.open(dbPath)
        val habits = HabitRepository(db)
        val entries = EntryRepository(db)
        fun habit(uuid: String, name: String, pos: Int, type: Int = 0, color: Int = 8, category: String = "", unit: String = "", target: Double = 0.0) =
            habits.insert(
                HabitData(
                    name = name, uuid = uuid, position = pos, type = type, color = color,
                    category = category, unit = unit, targetValue = target, freqNum = 1, freqDen = 1
                )
            )
        val read = habit("r", "Read 20 minutes", 0, color = 11, category = "Mind")
        val water = habit("g", "Water", 1, type = 1, color = 10, category = "Health", unit = "glasses", target = 8.0)
        val wake = habit("w", "Wake up", 2, type = 11, color = 2)
        val today = org.isoron.platform.time.computeToday(AppModel.MIDNIGHT_DELAY_HOURS, 0)
        for (i in 0 until 7) {
            val ts = today.minus(i).unixTime
            if (i % 3 != 1) entries.insert(EntryData(habitId = read, timestamp = ts, value = 2))
            if (i > 0) entries.insert(EntryData(habitId = water, timestamp = ts, value = (4 + i) * 1000))
            if (i > 0) entries.insert(EntryData(habitId = wake, timestamp = ts, value = TimeOfDay.encode(6 * 60 + 20 + i * 4)))
        }
        db.close()
    }

    private fun render(model: AppModel, name: String, height: Int = 460): ByteArray {
        val scene = ImageComposeScene(width = 1000, height = height, density = Density(1f)) { App(model) }
        val image = scene.render()
        scene.close()
        val bytes = image.encodeToData(EncodedImageFormat.PNG)!!.bytes
        val out = Path.of("build", "screenshots").also { Files.createDirectories(it) }
        Files.write(out.resolve("$name.png"), bytes)
        return bytes
    }

    @Test
    fun testSetupScreenAndMainScreenRender() {
        val dbPath = tmp.resolve("uhabits.db")
        seed(dbPath)
        val m = AppModel(dbPath).also { model = it }

        assertTrue(render(m, "setup").size > 1000)

        m.skipSync()
        assertTrue(render(m, "main").size > 1000)

        val rows = m.rows(7)
        // Like the phone, habits without a category come first, then the categories in order.
        assertEquals(listOf("Wake up", "Water", "Read 20 minutes"), rows.map { it.name })
        assertEquals(7, rows[0].cells.size)
        assertEquals(CellKind.UNKNOWN, rows[1].cells[0].kind)
        assertEquals("9", rows[1].cells[5].text)
    }

    @Test
    fun testSearchFiltersLikeThePhone() {
        val dbPath = tmp.resolve("uhabits.db")
        seed(dbPath)
        val m = AppModel(dbPath).also { model = it }
        assertEquals(listOf("Water"), m.rows(7, "wat").map { it.name })
        assertEquals(listOf("Read 20 minutes"), m.rows(7, "  READ ").map { it.name })
        // The category is not searched, as on the phone.
        assertTrue(m.rows(7, "Health").isEmpty())
        assertEquals(3, m.rows(7, "").size)

        m.skipSync()
        m.searchQuery = "zzz"
        assertTrue(render(m, "main-no-match").size > 1000)
        m.searchQuery = "wat"
        assertTrue(render(m, "main-search").size > 1000)
    }

    @Test
    fun testCategoryHeadersGroupTheList() {
        val dbPath = tmp.resolve("uhabits.db")
        seed(dbPath)
        val m = AppModel(dbPath).also { model = it }
        val items = buildListItems(m.rows(7))
        // Uncategorized habit first with no header, then one header per category.
        assertEquals(
            listOf("habit:Wake up", "header:Health", "habit:Water", "header:Mind", "habit:Read 20 minutes"),
            items.map {
                when (it) {
                    is ListItem.Header -> "header:${it.title}"
                    is ListItem.Habit -> "habit:${it.row.name}"
                }
            }
        )
        assertEquals(items.size, items.map { it.key }.distinct().size)
        assertTrue(m.rows(7).none { it.category.isEmpty() && it.name != "Wake up" })
    }

    @Test
    fun testTogglingADayChangesTheCell() {
        val dbPath = tmp.resolve("uhabits.db")
        seed(dbPath)
        val m = AppModel(dbPath).also { model = it }
        val row = m.rows(7).first { it.name == "Read 20 minutes" }
        val cell = row.cells[0]
        val before = cell.kind
        m.toggle(row.id, cell.date)
        val after = m.rows(7).first { it.id == row.id }.cells[0].kind
        assertTrue(before != after)
    }

    @Test
    fun testSettingANumberAndATime() {
        val dbPath = tmp.resolve("uhabits.db")
        seed(dbPath)
        val m = AppModel(dbPath).also { model = it }
        val water = m.rows(7).first { it.name == "Water" }
        val wake = m.rows(7).first { it.name == "Wake up" }
        m.setNumber(water.id, water.cells[0].date, 7.5)
        m.setTime(wake.id, wake.cells[0].date, 7 * 60 + 5)
        val rows = m.rows(7)
        assertEquals("7.5", rows.first { it.name == "Water" }.cells[0].text)
        assertEquals("07:05", rows.first { it.name == "Wake up" }.cells[0].text)
        m.setNumber(water.id, water.cells[0].date, null)
        assertEquals(CellKind.UNKNOWN, m.rows(7).first { it.name == "Water" }.cells[0].kind)
    }

    @Test
    fun testParsingTimes() {
        assertEquals(450, AppModel.parseTime("7:30"))
        assertEquals(450, AppModel.parseTime("07:30"))
        assertEquals(450, AppModel.parseTime("0730"))
        assertEquals(null, AppModel.parseTime("25:00"))
        assertEquals(null, AppModel.parseTime("abc"))
    }

    @Test
    fun testDetailDataAndScreen() {
        val dbPath = tmp.resolve("uhabits.db")
        seed(dbPath)
        val m = AppModel(dbPath).also { model = it }
        m.skipSync()
        val read = m.rows(7).first { it.name == "Read 20 minutes" }

        val d = m.detail(read.id, 1)!!
        assertEquals("Read 20 minutes", d.name)
        assertTrue(d.scores.isNotEmpty())
        assertTrue(d.history.size >= 7)
        assertEquals(5L, d.totalCount)
        assertTrue(d.streaks.isNotEmpty())

        m.openHabitId = read.id
        assertTrue(render(m, "detail", height = 900).size > 1000)
        m.openHabitId = null
    }

    @Test
    fun testCreateEditArchiveAndDeleteAHabit() {
        val dbPath = tmp.resolve("uhabits.db")
        seed(dbPath)
        val m = AppModel(dbPath).also { model = it }

        assertEquals("Give the habit a name.", m.saveHabit(null, EditorData()))
        assertEquals(null, m.saveHabit(null, EditorData(name = "Stretch", category = "Health", colorIndex = 3)))
        var row = m.rows(7).first { it.name == "Stretch" }
        assertEquals("Health", row.category)

        assertEquals(null, m.saveHabit(row.id, m.editorFor(row.id)!!.copy(name = "Stretch more", freqNum = 3, freqDen = 7)))
        row = m.rows(7).first { it.id == row.id }
        assertEquals("Stretch more", row.name)
        assertEquals(3, m.editorFor(row.id)!!.freqNum)

        m.archive(row.id)
        assertTrue(m.rows(7).none { it.id == row.id })

        val other = m.rows(7).first { it.name == "Water" }
        m.delete(other.id)
        assertTrue(m.rows(7).none { it.id == other.id })
    }

    @Test
    fun testWakeUpAndBedtimeCanOnlyBeCreatedOnce() {
        val dbPath = tmp.resolve("uhabits.db")
        seed(dbPath) // already has a wake up habit
        val m = AppModel(dbPath).also { model = it }
        assertTrue(org.isoron.uhabits.core.models.HabitType.WAKE_UP in m.existingTypes())
        val error = m.saveHabit(null, EditorData(type = org.isoron.uhabits.core.models.HabitType.WAKE_UP))
        assertTrue(error!!.contains("already have"))
        assertEquals(
            null,
            m.saveHabit(null, EditorData(type = org.isoron.uhabits.core.models.HabitType.BEDTIME, pin = org.isoron.uhabits.core.models.HabitPin.BOTTOM))
        )
        assertEquals("Bedtime", m.rows(7).last().name)
    }

    @Test
    fun testEditorDialogRenders() {
        val dbPath = tmp.resolve("uhabits.db")
        seed(dbPath)
        val m = AppModel(dbPath).also { model = it }
        m.skipSync()
        val scene = ImageComposeScene(width = 1000, height = 900, density = Density(1f)) {
            androidx.compose.material3.MaterialTheme { EditorDialog(m, null, onClose = {}) }
        }
        val bytes = scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes
        scene.close()
        Files.write(Path.of("build", "screenshots").also { Files.createDirectories(it) }.resolve("editor.png"), bytes)
        assertTrue(bytes.size > 1000)
    }
}

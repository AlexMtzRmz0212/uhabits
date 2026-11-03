/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.isoron.uhabits.core.models.sqlite

import me.tatarka.inject.annotations.Inject
import org.isoron.platform.io.getVersion
import org.isoron.uhabits.core.database.EntryRepository
import org.isoron.uhabits.core.database.HabitRepository
import org.isoron.uhabits.core.models.EntryList
import org.isoron.uhabits.core.models.ModelFactory
import org.isoron.uhabits.core.models.ScoreList
import org.isoron.uhabits.core.models.StreakList
import org.isoron.uhabits.core.sync.SyncRepository

/**
 * Factory that provides models backed by an SQLite database.
 */
@Inject
class SQLModelFactory(
    val database: org.isoron.platform.io.Database
) : ModelFactory {
    // The sync tables only exist from database version 27 on.
    val syncRepository = if (database.getVersion() >= 27) SyncRepository(database) else null
    val habitRepository = HabitRepository(database, syncRepository)
    val entryRepository = EntryRepository(database, syncRepository)

    override fun buildOriginalEntries() = SQLiteEntryList(entryRepository)
    override fun buildComputedEntries() = EntryList()
    override fun buildHabitList() = SQLiteHabitList(this)
    override fun buildScoreList() = ScoreList()
    override fun buildStreakList() = StreakList()
}

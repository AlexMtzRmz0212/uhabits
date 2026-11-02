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

package org.isoron.uhabits.activities.habits.edit

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDialogFragment
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.databinding.SelectHabitTypeBinding
import org.isoron.uhabits.intents.IntentFactory

class HabitTypeDialog : AppCompatDialogFragment() {
    override fun getTheme() = R.style.Translucent

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val binding = SelectHabitTypeBinding.inflate(inflater, container, false)

        binding.buttonYesNo.setOnClickListener {
            val intent = IntentFactory().startEditActivity(requireActivity(), HabitType.YES_NO.value)
            startActivity(intent)
            dismiss()
        }

        binding.buttonMeasurable.setOnClickListener {
            val intent = IntentFactory().startEditActivity(requireActivity(), HabitType.NUMERICAL.value)
            startActivity(intent)
            dismiss()
        }

        binding.buttonTimeOfDay.setOnClickListener {
            val intent = IntentFactory().startEditActivity(requireActivity(), HabitType.TIME_OF_DAY.value)
            startActivity(intent)
            dismiss()
        }

        binding.buttonWakeUp.setOnClickListener {
            val intent = IntentFactory().startEditActivity(requireActivity(), HabitType.WAKE_UP.value)
            startActivity(intent)
            dismiss()
        }

        binding.buttonBedtime.setOnClickListener {
            val intent = IntentFactory().startEditActivity(requireActivity(), HabitType.BEDTIME.value)
            startActivity(intent)
            dismiss()
        }

        // Wake up and bedtime habits are unique: once one exists, it is not offered again until
        // it is deleted.
        val existing = (requireActivity().application as HabitsApplication).component.habitList
            .map { it.type }
            .toSet()
        if (HabitType.WAKE_UP in existing) binding.buttonWakeUp.visibility = View.GONE
        if (HabitType.BEDTIME in existing) binding.buttonBedtime.visibility = View.GONE

        binding.background.setOnClickListener {
            dismiss()
        }

        return binding.root
    }
}

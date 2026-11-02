package org.isoron.uhabits.utils

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.DialogInterface
import android.text.format.DateFormat
import org.isoron.uhabits.R

/**
 * Shows a time picker for a time-of-day habit entry. [minutes] is the current value (minutes since
 * midnight) or null if there is none. [onResult] receives the chosen minutes, or null if the user
 * pressed "Clear".
 */
fun showTimeOfDayDialog(context: Context, minutes: Int?, onResult: (Int?) -> Unit) {
    val initial = minutes ?: currentMinutes()
    val dialog = TimePickerDialog(
        context,
        { _, hour, minute -> onResult(hour * 60 + minute) },
        initial / 60,
        initial % 60,
        DateFormat.is24HourFormat(context)
    )
    if (minutes != null) {
        dialog.setButton(AlertDialog.BUTTON_NEUTRAL, context.getString(R.string.clear)) { _: DialogInterface, _: Int ->
            onResult(null)
        }
    }
    dialog.show()
}

private fun currentMinutes(): Int {
    val now = java.util.Calendar.getInstance()
    return now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
}

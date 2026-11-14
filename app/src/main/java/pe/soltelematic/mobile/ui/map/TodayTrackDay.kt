package pe.soltelematic.mobile.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import pe.soltelematic.mobile.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Cómo se nombra el día que muestra la "Última ruta": "Hoy", "Ayer" o su fecha. */
enum class TrackDayKind { TODAY, YESTERDAY, DATE }

fun trackDayKind(day: LocalDate, today: LocalDate): TrackDayKind = when (day) {
    today -> TrackDayKind.TODAY
    today.minusDays(1) -> TrackDayKind.YESTERDAY
    else -> TrackDayKind.DATE
}

private val TRACK_DAY_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

/** Texto del día mostrado, para la chip y el panel. */
@Composable
fun trackDayText(day: LocalDate, today: LocalDate = LocalDate.now()): String = when (trackDayKind(day, today)) {
    TrackDayKind.TODAY -> stringResource(R.string.today_track_day_today)
    TrackDayKind.YESTERDAY -> stringResource(R.string.today_track_day_yesterday)
    TrackDayKind.DATE -> TRACK_DAY_DATE_FORMAT.format(day)
}

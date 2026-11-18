package space.opensensor.strings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.jetbrains.compose.resources.stringResource
import space.opensensor.strings.resources.Res
import space.opensensor.strings.resources.ago_days
import space.opensensor.strings.resources.ago_hours
import space.opensensor.strings.resources.ago_just_now
import space.opensensor.strings.resources.ago_minutes
import space.opensensor.strings.resources.ago_seconds

/** The unit an "N ago" line uses. */
enum class AgoUnit { JUST_NOW, SECONDS, MINUTES, HOURS, DAYS }

/**
 * The unit and whole count of an age, the largest unit that fits. A future time reads as just now,
 * and so does an age under a minute when [seconds] is false.
 */
fun agoParts(ageMs: Long, seconds: Boolean = true): Pair<AgoUnit, Long> {
    val s = ageMs / 1000
    return when {
        ageMs < 0 -> AgoUnit.JUST_NOW to 0L
        !seconds && s < 60 -> AgoUnit.JUST_NOW to 0L
        s < 60 -> AgoUnit.SECONDS to s
        s < 3600 -> AgoUnit.MINUTES to s / 60
        s < 86_400 -> AgoUnit.HOURS to s / 3600
        else -> AgoUnit.DAYS to s / 86_400
    }
}

/** Puts [count] into a template's first positional slot, `%1$d` or `%1$s`. */
fun fillCount(template: String, count: Long): String =
    template.replace("%1\$d", count.toString()).replace("%1\$s", count.toString())

/**
 * "12 s ago" as a plain function bound to the current language, for ticking leaf lambdas. The
 * templates are read once per composition and the lambda only fills in the number, so every screen
 * that shows an age words it the same on both phones. Without [seconds], as on a map where minutes
 * are the finest step, an age under a minute reads just now.
 */
@Composable
fun rememberAgoFormatter(seconds: Boolean = true): (Long) -> String {
    val justNow = stringResource(Res.string.ago_just_now)
    val secondsAgo = stringResource(Res.string.ago_seconds)
    val minutes = stringResource(Res.string.ago_minutes)
    val hours = stringResource(Res.string.ago_hours)
    val days = stringResource(Res.string.ago_days)
    return remember(seconds, justNow, secondsAgo, minutes, hours, days) {
        { ageMs ->
            val (unit, n) = agoParts(ageMs, seconds)
            when (unit) {
                AgoUnit.JUST_NOW -> justNow
                AgoUnit.SECONDS -> fillCount(secondsAgo, n)
                AgoUnit.MINUTES -> fillCount(minutes, n)
                AgoUnit.HOURS -> fillCount(hours, n)
                AgoUnit.DAYS -> fillCount(days, n)
            }
        }
    }
}

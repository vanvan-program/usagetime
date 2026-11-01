package dev.usagetime.core

import java.time.ZoneId

object RetentionCalendar {
    /** A civil-day boundary keeps 90 full days plus the current partial day, including DST changes. */
    fun cutoff(now:Long,zone:ZoneId)=Dates.midnight(Dates.date(now,zone).minusDays(90),zone)
}

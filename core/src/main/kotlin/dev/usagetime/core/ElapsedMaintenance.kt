package dev.usagetime.core

/** Successful-sync maintenance cadence. Wall time is intentionally not used. */
object ElapsedMaintenance {
    const val INTERVAL=6*3_600_000L
    fun due(elapsed:Long,boot:Int,previousElapsed:Long,previousBoot:Int,interval:Long=INTERVAL):Boolean {
        require(elapsed>=0&&interval>0)
        return boot<0||boot!=previousBoot||previousElapsed<0||elapsed<previousElapsed||elapsed-previousElapsed>=interval
    }
}

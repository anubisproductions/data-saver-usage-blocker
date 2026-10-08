package com.anubisproductions.datagate

import android.app.Activity

/**
 * FOSS build: there is nothing to ask.
 *
 * Play's review library is a proprietary blob, and the whole point of the F-Droid and
 * IzzyOnDroid builds is that an auditor can read every line that ships. A rating prompt is
 * also meaningless outside the Play Store, where there are no ratings to leave.
 *
 * This stub exists rather than an `if (BuildConfig.FLAVOR == ...)` in MainActivity so that
 * the dependency is absent from the FOSS variant's classpath entirely, not merely unreached.
 */
object ReviewPrompt {
    @Suppress("UNUSED_PARAMETER")
    fun ask(activity: Activity) = Unit
}

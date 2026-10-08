package com.anubisproductions.datagate

import android.app.Activity
import com.google.android.play.core.review.ReviewManagerFactory

/**
 * Play Store build: hand the rating request to the Play Store app.
 *
 * Play decides whether a dialog actually appears - it has its own quotas and stays silent if
 * the user has already rated or was asked recently. The caller has already recorded that it
 * asked, which is deliberate: if a silent Play were treated as "not asked yet", every render
 * would queue another request for a dialog nobody is going to see.
 *
 * Nothing here may fail loudly. A rating prompt is not worth a crash.
 */
object ReviewPrompt {
    fun ask(activity: Activity) {
        runCatching {
            val manager = ReviewManagerFactory.create(activity)
            manager.requestReviewFlow().addOnCompleteListener { task ->
                if (!task.isSuccessful || activity.isFinishing || activity.isDestroyed) {
                    return@addOnCompleteListener
                }
                runCatching { manager.launchReviewFlow(activity, task.result) }
            }
        }
    }
}

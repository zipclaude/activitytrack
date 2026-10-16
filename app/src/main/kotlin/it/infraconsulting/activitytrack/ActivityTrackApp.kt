/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack

import android.app.Application
import it.infraconsulting.activitytrack.data.db.ActivityTrackDatabase
import it.infraconsulting.activitytrack.notify.NotificationChannels

class ActivityTrackApp : Application() {
	val database: ActivityTrackDatabase by lazy { ActivityTrackDatabase.build(this) }

	override fun onCreate() {
		super.onCreate()
		NotificationChannels.register(this)
	}
}

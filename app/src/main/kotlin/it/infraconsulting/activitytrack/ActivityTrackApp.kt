/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack

import android.app.Application
import it.infraconsulting.activitytrack.notify.NotificationChannels

class ActivityTrackApp : Application() {
	lateinit var container: AppContainer
		private set

	override fun onCreate() {
		super.onCreate()
		container = AppContainer(this)
		NotificationChannels.register(this)
	}
}

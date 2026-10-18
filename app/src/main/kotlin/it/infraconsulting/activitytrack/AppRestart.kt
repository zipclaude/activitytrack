/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack

import android.content.Context
import android.content.Intent
import it.infraconsulting.activitytrack.data.db.ActivityTrackDatabase
import it.infraconsulting.activitytrack.data.storage.DatabaseBackup

/* Backup restore (DESIGN §8.2): swap the database file, then start over in a fresh process. */
object AppRestart {
	fun replaceDatabase(context: Context, container: AppContainer, staged: DatabaseBackup.Staged) =
		container.backup.swapIn(context.getDatabasePath(ActivityTrackDatabase.FILE_NAME), staged)

	/* The closed database cannot be reopened in this process: relaunch and exit. */
	fun now(context: Context) {
		val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
		context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
		Runtime.getRuntime().exit(0)
	}
}

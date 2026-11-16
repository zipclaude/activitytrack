/* SPDX-License-Identifier: GPL-3.0-or-later */
/* SPDX-FileCopyrightText: 2026 Flavio Visentin <flavio-activitytrack@infraconsulting.it> */
package it.infraconsulting.activitytrack.location

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import it.infraconsulting.activitytrack.domain.geo.NavTarget
import it.infraconsulting.activitytrack.domain.geo.NavigationProposal

/*
 * Starts navigation to [NavTarget] with an implicit intent any app may take
 * (issue #193): turn-by-turn (google.navigation:) when an app handles it,
 * else a geo: link with the label; no app at all is quietly ignored.
 */
object Navigator {
	private const val TAG = "Navigator"

	fun start(context: Context, t: NavTarget): Boolean {
		for (uri in listOf(NavigationProposal.navigationUri(t), NavigationProposal.geoUri(t))) {
			val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			if (intent.resolveActivity(context.packageManager) == null)
				continue
			try {
				context.startActivity(intent)
				return true
			} catch (x: ActivityNotFoundException) {
				Log.w(TAG, "no app for $uri", x)
			} catch (x: SecurityException) {
				Log.w(TAG, "refused: $uri", x)
			}
		}
		return false
	}
}

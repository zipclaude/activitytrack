/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.connect.plugin

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import it.infraconsulting.activitytrack.ActivityTrackApp
import it.infraconsulting.activitytrack.MainActivity
import it.infraconsulting.activitytrack.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Receives "<applicationId>:/oauth2redirect?code=…&state=…" from the browser
 * after a plugin's login, turns the code into tokens and goes back to the app.
 */
class OAuthRedirectActivity : Activity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val app = application as ActivityTrackApp
		val uri = intent?.data
		finish()
		if (uri == null)
			return
		app.appScope.launch {
			val message = try {
				val c = app.oauth.finishLogin(uri)
				val name = app.plugins.plugin(c.pluginId)?.name ?: c.pluginId
				app.getString(R.string.plugin_logged_in, name)
			} catch (e: Exception) {
				Log.w("OAuth", "login failed", e)
				app.getString(R.string.plugin_login_failed, e.message.orEmpty())
			}
			withContext(Dispatchers.Main) {
				Toast.makeText(app, message, Toast.LENGTH_LONG).show()
				app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
			}
		}
	}
}

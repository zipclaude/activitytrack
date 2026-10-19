/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.connect.antares

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import it.infraconsulting.activitytrack.ActivityTrackApp
import it.infraconsulting.activitytrack.MainActivity
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.domain.interchange.AntaresConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/*
 * Receives "<applicationId>:/oauth2redirect?code=…&state=…" from the browser
 * after the Antares login, turns the code into tokens, records who logged in
 * (person id = OwnerId of the activities) and goes back to the app.
 */
class OAuthRedirectActivity : Activity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val app = application as ActivityTrackApp
		val uri = intent?.data
		finish()
		if (uri == null)
			return
		scope.launch {
			val c = app.container
			val message = try {
				val companyId = app.antares.finishLogin(uri) { id -> AntaresConfig.of(c.masterData.company(id)?.connectorConfig) }
				val company = c.masterData.company(companyId) ?: error("company $companyId")
				val config = AntaresConfig.of(company.connectorConfig)
				val (person, user) = AntaresApi(app.antares, companyId, config).me()
				val json = runCatching { JSONObject(company.connectorConfig ?: "{}") }.getOrDefault(JSONObject())
					.put(AntaresConnector.PERSON_ID, person).put(AntaresConnector.USERNAME, user)
				c.masterData.saveCompany(company.copy(connectorConfig = json.toString()))
				app.getString(R.string.antares_logged_in, user.ifEmpty { person })
			} catch (e: Exception) {
				Log.w("Antares", "login failed", e)
				app.getString(R.string.antares_login_failed, e.message.orEmpty())
			}
			launch(Dispatchers.Main) {
				Toast.makeText(app, message, Toast.LENGTH_LONG).show()
				app.startActivity(android.content.Intent(app, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
			}
		}
	}

	private companion object {
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	}
}

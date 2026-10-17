/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.domain.geo.GeoPoint
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/* Centre of Italy at country zoom, when nothing better is known. */
private val FALLBACK = GeoPoint(42.5, 12.5)

/*
 * Map picker (M2, decision A10): pan the map under a fixed cross-hair and
 * take its centre. Raster tiles from the configured endpoint; MapLibre
 * shows the OSM attribution of the source (ODbL).
 */
@Composable
fun MapPickerDialog(
	tilesUrl: String,
	initial: GeoPoint?,
	onDismiss: () -> Unit,
	onPick: (GeoPoint) -> Unit,
) {
	val context = LocalContext.current
	val lifecycle = LocalLifecycleOwner.current.lifecycle
	/* set once the map is ready; survives recompositions */
	val map = remember { AtomicReference<MapLibreMap?>(null) }
	val view = remember {
		MapLibre.getInstance(context.applicationContext)
		MapView(context).apply {
			onCreate(null)
			getMapAsync { m ->
				map.set(m)
				m.setStyle(Style.Builder().fromJson(rasterStyle(tilesUrl)))
				val start = initial ?: FALLBACK
				m.cameraPosition = CameraPosition.Builder()
					.target(LatLng(start.lat, start.lon))
					.zoom(if (initial != null) 17.0 else 5.0)
					.build()
			}
		}
	}
	/* MapView needs the host lifecycle forwarded, and a final onDestroy */
	DisposableEffect(lifecycle) {
		val observer = LifecycleEventObserver { _, event ->
			when (event) {
				Lifecycle.Event.ON_START -> view.onStart()
				Lifecycle.Event.ON_RESUME -> view.onResume()
				Lifecycle.Event.ON_PAUSE -> view.onPause()
				Lifecycle.Event.ON_STOP -> view.onStop()
				else -> {}
			}
		}
		lifecycle.addObserver(observer)
		if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
			view.onStart()
		if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
			view.onResume()
		onDispose {
			lifecycle.removeObserver(observer)
			view.onPause()
			view.onStop()
			view.onDestroy()
		}
	}

	Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
		Surface(modifier = Modifier.fillMaxSize()) {
			Column(modifier = Modifier.safeDrawingPadding()) {
				Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
					AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
					Text("+", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.align(Alignment.Center))
				}
				Row(
					modifier = Modifier.fillMaxWidth().padding(16.dp),
					horizontalArrangement = Arrangement.spacedBy(8.dp),
				) {
					OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_cancel)) }
					Button(
						onClick = { map.get()?.cameraPosition?.target?.let { onPick(GeoPoint(it.latitude, it.longitude)) } },
						modifier = Modifier.weight(1f),
					) { Text(stringResource(R.string.map_use_point)) }
				}
			}
		}
	}
}

/* Style spec v8 with a single raster source. */
private fun rasterStyle(tilesUrl: String): String =
	JSONObject()
		.put("version", 8)
		.put(
			"sources",
			JSONObject().put(
				"osm",
				JSONObject()
					.put("type", "raster")
					.put("tiles", JSONArray().put(tilesUrl))
					.put("tileSize", 256)
					.put("maxzoom", 19)
					.put("attribution", "© OpenStreetMap contributors"),
			),
		)
		.put("layers", JSONArray().put(JSONObject().put("id", "osm").put("type", "raster").put("source", "osm")))
		.toString()

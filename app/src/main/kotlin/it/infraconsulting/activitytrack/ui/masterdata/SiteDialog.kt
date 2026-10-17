/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.masterdata

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import it.infraconsulting.activitytrack.ActivityTrackApp
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.data.repo.ValidationException.Reason
import it.infraconsulting.activitytrack.domain.geo.GeoPoint
import it.infraconsulting.activitytrack.domain.settings.OnlineService
import it.infraconsulting.activitytrack.location.CurrentPosition
import it.infraconsulting.activitytrack.ui.common.EditDialog
import it.infraconsulting.activitytrack.ui.common.MapPickerDialog
import it.infraconsulting.activitytrack.ui.common.TextInput
import it.infraconsulting.activitytrack.ui.common.parseDecimal
import java.util.Locale
import kotlinx.coroutines.launch

/* Location fields shared by company sites, client sites and places. */
data class SiteFields(
	val label: String,
	val lat: Double,
	val lon: Double,
	val radiusM: Int,
	val address: String?,
)

/*
 * Edit a site. Coordinates can be typed, taken from the current position
 * (high accuracy) or picked on the map when map tiles are enabled.
 */
@Composable
fun SiteDialog(
	title: String,
	initial: SiteFields?,
	error: Reason?,
	withAddress: Boolean = true,
	extra: @Composable () -> Unit = {},
	onDismiss: () -> Unit,
	onDelete: (() -> Unit)?,
	onSave: (SiteFields) -> Unit,
) {
	var label by remember { mutableStateOf(initial?.label.orEmpty()) }
	var lat by remember { mutableStateOf(initial?.lat?.toString().orEmpty()) }
	var lon by remember { mutableStateOf(initial?.lon?.toString().orEmpty()) }
	var radius by remember { mutableStateOf((initial?.radiusM ?: 100).toString()) }
	var address by remember { mutableStateOf(initial?.address.orEmpty()) }
	var locating by remember { mutableStateOf(false) }
	var unavailable by remember { mutableStateOf(false) }
	var picking by remember { mutableStateOf(false) }
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	val container = (context.applicationContext as ActivityTrackApp).container
	val tiles = produceState<OnlineService?>(null) { container.settings.settings.collect { value = it.tiles } }.value
	val typed = parseDecimal(lat)?.let { la -> parseDecimal(lon)?.let { lo -> runCatching { GeoPoint(la, lo) }.getOrNull() } }
	val setPoint: (GeoPoint) -> Unit = {
		lat = String.format(Locale.ROOT, "%.6f", it.lat)
		lon = String.format(Locale.ROOT, "%.6f", it.lon)
	}

	EditDialog(
		title = title,
		error = error,
		onDismiss = onDismiss,
		onDelete = onDelete,
		onConfirm = {
			onSave(
				SiteFields(
					label = label,
					/* NaN makes the repository report invalid coordinates */
					lat = parseDecimal(lat) ?: Double.NaN,
					lon = parseDecimal(lon) ?: Double.NaN,
					radiusM = radius.trim().toIntOrNull() ?: 0,
					address = address,
				),
			)
		},
	) {
		extra()
		TextInput(stringResource(R.string.field_label), label, { label = it })
		TextInput(stringResource(R.string.field_lat), lat, { lat = it }, KeyboardType.Decimal)
		TextInput(stringResource(R.string.field_lon), lon, { lon = it }, KeyboardType.Decimal)
		Row {
			TextButton(enabled = !locating, onClick = {
				locating = true
				unavailable = false
				scope.launch {
					val here = CurrentPosition.get(context, highAccuracy = true)
					here?.let(setPoint)
					unavailable = here == null
					locating = false
				}
			}) { Text(stringResource(R.string.site_current_position)) }
			if (tiles?.enabled == true)
				TextButton(onClick = { picking = true }) { Text(stringResource(R.string.site_map)) }
		}
		if (unavailable)
			Text(stringResource(R.string.position_unavailable), color = MaterialTheme.colorScheme.error)
		TextInput(stringResource(R.string.field_radius), radius, { radius = it }, KeyboardType.Number)
		if (withAddress)
			TextInput(stringResource(R.string.field_address), address, { address = it })
		Text(stringResource(R.string.site_hint))
	}
	if (picking && tiles != null)
		MapPickerDialog(tiles.endpoint, typed, onDismiss = { picking = false }) {
			setPoint(it)
			picking = false
		}
}

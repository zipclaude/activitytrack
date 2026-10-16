/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.masterdata

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.data.repo.ValidationException.Reason
import it.infraconsulting.activitytrack.ui.common.EditDialog
import it.infraconsulting.activitytrack.ui.common.TextInput
import it.infraconsulting.activitytrack.ui.common.parseDecimal

/* Location fields shared by company sites, client sites and places. */
data class SiteFields(
	val label: String,
	val lat: Double,
	val lon: Double,
	val radiusM: Int,
	val address: String?,
)

/*
 * Edit a site. Coordinates are typed for now (e.g. copied from a map app);
 * "use current position" and the map picker arrive with location in M2.
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
		TextInput(stringResource(R.string.field_radius), radius, { radius = it }, KeyboardType.Number)
		if (withAddress)
			TextInput(stringResource(R.string.field_address), address, { address = it })
		Text(stringResource(R.string.site_hint))
	}
}

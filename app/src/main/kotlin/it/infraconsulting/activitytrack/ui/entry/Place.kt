/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.entry

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import it.infraconsulting.activitytrack.domain.model.LocationKind
import it.infraconsulting.activitytrack.ui.common.ClearButton
import it.infraconsulting.activitytrack.ui.common.CompactTextField
import it.infraconsulting.activitytrack.ui.common.Importance
import it.infraconsulting.activitytrack.ui.common.LocalFieldEnabled
import it.infraconsulting.activitytrack.ui.common.fold
import it.infraconsulting.activitytrack.ui.common.importanceColors
import it.infraconsulting.activitytrack.ui.common.rememberMenuHeight

/*
 * The one "Luogo" field of the detail (issue #184), in place of "Località"
 * (the free text `locality`) and "Dove" (the kind `location_kind`): the
 * text is the locality; the kind follows from it. A label picked from the
 * list stores its kind (a site of the client → CLIENT, of the company →
 * COMPANY, "Casa" → HOME), any other text OTHER, an empty field none.
 */
data class PlaceOption(val label: String, val kind: LocationKind)

/*
 * The list of the field, in the owner's order: the site labels of the
 * client, those of the company, "Casa" last ([home]); blank and repeated
 * labels left out (the first one wins).
 */
fun placeOptions(clientSites: List<String>, companySites: List<String>, home: String): List<PlaceOption> {
	val all = clientSites.map { PlaceOption(it.trim(), LocationKind.CLIENT) } +
		companySites.map { PlaceOption(it.trim(), LocationKind.COMPANY) } +
		PlaceOption(home.trim(), LocationKind.HOME)
	return all.filter { it.label.isNotEmpty() }.distinctBy { fold(it.label) }
}

/* The kind the text stands for: none when empty, an option's kind when it is one of the labels (case and accents ignored), else OTHER. */
fun placeKindOf(text: String, options: List<PlaceOption>): LocationKind? {
	if (text.isBlank())
		return null
	val key = fold(text)
	return options.firstOrNull { fold(it.label) == key }?.kind ?: LocationKind.OTHER
}

/* The options the drop-down shows: all while the field is empty or holds one of them, else those containing what is typed. */
fun placeMatches(text: String, options: List<PlaceOption>): List<PlaceOption> {
	val key = fold(text)
	if (key.isEmpty() || options.any { fold(it.label) == key })
		return options
	return options.filter { fold(it.label).contains(key) }
}

/*
 * Editable combo box (owner, issue #184): free text, or a pick from
 * [options]; the ✕ empties it; [extra] goes before the drop-down arrow
 * (the route button, issue #191).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaceField(
	label: String,
	value: String,
	options: List<PlaceOption>,
	importance: Importance?,
	onText: (String) -> Unit,
	onPick: (PlaceOption) -> Unit,
	kindLabel: @Composable (LocationKind) -> String,
	extra: (@Composable () -> Unit)? = null,
) {
	var expanded by remember { mutableStateOf(false) }
	val editable = LocalFieldEnabled.current
	val menu = rememberMenuHeight()
	val shown = placeMatches(value, options)
	ExposedDropdownMenuBox(expanded = expanded && editable, onExpandedChange = { expanded = it && editable }) {
		CompactTextField(
			value = value,
			onValueChange = { onText(it); expanded = true },
			readOnly = !editable,
			singleLine = true,
			label = { Text(label) },
			trailingIcon = {
				Row(verticalAlignment = Alignment.CenterVertically) {
					if (value.isNotEmpty() && editable)
						ClearButton { onText("") }
					extra?.invoke()
					if (options.isNotEmpty())
						ExposedDropdownMenuDefaults.TrailingIcon(expanded)
				}
			},
			colors = importanceColors(importance, value.isNotBlank()),
			modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable).then(menu.anchor),
		)
		ExposedDropdownMenu(expanded = expanded && editable && shown.isNotEmpty(), onDismissRequest = { expanded = false }, modifier = menu.limit) {
			shown.forEach { o ->
				DropdownMenuItem(
					text = { Text(o.label) },
					/* where the label comes from, but for "Casa" itself */
					trailingIcon = if (o.kind == LocationKind.HOME) null else ({
						Text(kindLabel(o.kind), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
					}),
					onClick = {
						onPick(o)
						expanded = false
					},
				)
			}
		}
	}
}

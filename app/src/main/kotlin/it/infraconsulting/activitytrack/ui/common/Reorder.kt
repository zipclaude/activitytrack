/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import it.infraconsulting.activitytrack.R
import it.infraconsulting.activitytrack.domain.settings.ListOrder
import it.infraconsulting.activitytrack.domain.settings.Ordering

/*
 * Drag to reorder the rows of a LazyColumn (M16, backlog 1): the handle of
 * a row starts the drag, the row follows the finger and swaps with its
 * neighbour once it passes the neighbour's middle; [onDrop] gets the new
 * order when the finger lifts. Rows only swap within the same [group]
 * (clients: within their company; archived rows apart, they stay last).
 * The LazyColumn item keys must be the [key]s of the items.
 */
class Reorder<T>(
	private val listState: LazyListState,
	private val key: (T) -> Any,
	private val group: (T) -> Any?,
) {
	var items by mutableStateOf(emptyList<T>())
		internal set
	internal var dragging by mutableStateOf<Any?>(null)
	internal var offset by mutableFloatStateOf(0f)
	internal var onDrop: (List<T>) -> Unit = {}

	internal fun start(k: Any) {
		dragging = k
		offset = 0f
	}

	internal fun drag(dy: Float) {
		val k = dragging ?: return
		offset += dy
		val info = listState.layoutInfo
		val cur = info.visibleItemsInfo.firstOrNull { it.key == k } ?: return
		/* near the edges the list scrolls; the row stays under the finger */
		val top = cur.offset + offset
		if ((dy > 0 && top + cur.size > info.viewportEndOffset) || (dy < 0 && top < info.viewportStartOffset))
			offset += listState.dispatchRawDelta(dy)
		val keys = items.map(key)
		val from = keys.indexOf(k).takeIf { it >= 0 } ?: return
		/* the next row of the same group: rows of other groups may sit between them in [items] */
		val g = group(items[from])
		val to = (if (offset > 0) (from + 1 until items.size) else (from - 1 downTo 0))
			.firstOrNull { group(items[it]) == g } ?: return
		val neighbour = items[to]
		val n = info.visibleItemsInfo.firstOrNull { it.key == key(neighbour) } ?: return
		val passed = if (offset > 0) cur.offset + offset + cur.size > n.offset + n.size / 2f
			else cur.offset + offset < n.offset + n.size / 2f
		if (!passed)
			return
		items = Ordering.move(items, from, to)
		/* the row is laid out at its new place next frame: keep it where it is drawn */
		val newOffset = if (to > from) n.offset + n.size - cur.size else n.offset
		offset -= newOffset - cur.offset
	}

	internal fun end() {
		if (dragging != null)
			onDrop(items)
		dragging = null
		offset = 0f
	}
}

/*
 * [items] as they come from the repository; the local order wins while a
 * drag runs and until they change. Show [Reorder.items] once filled.
 */
@Composable
fun <T> rememberReorder(
	listState: LazyListState,
	items: List<T>,
	key: (T) -> Any,
	group: (T) -> Any? = { null },
	onDrop: (List<T>) -> Unit,
): Reorder<T> {
	val r = remember(listState) { Reorder(listState, key, group) }
	r.onDrop = onDrop
	LaunchedEffect(items) {
		if (r.dragging == null)
			r.items = items
	}
	return r
}

/*
 * A list row: clickable; with [reorder] (manual order) a drag handle at the
 * end moves it.
 */
@Composable
fun <T> LazyItemScope.ReorderRow(
	headline: String,
	supporting: String?,
	reorder: Reorder<T>?,
	key: Any,
	/* the chosen colour of a company or client (issue #44); none: no dot */
	color: Int? = null,
	onClick: () -> Unit,
) {
	val dragged = reorder != null && reorder.dragging == key
	val modifier = when {
		dragged -> Modifier.zIndex(1f).graphicsLayer { translationY = reorder!!.offset }.shadow(6.dp)
		else -> Modifier.animateItem()
	}
	ListItem(
		headlineContent = { Text(headline) },
		supportingContent = supporting?.let { { Text(it) } },
		leadingContent = color?.let { { ColorDot(it) } },
		trailingContent = reorder?.let { r ->
			{
				Icon(
					painterResource(R.drawable.ic_drag),
					contentDescription = stringResource(R.string.order_drag),
					modifier = Modifier.pointerInput(key) {
						detectVerticalDragGestures(
							onDragStart = { r.start(key) },
							onDragEnd = r::end,
							onDragCancel = r::end,
						) { change, dy ->
							change.consume()
							r.drag(dy)
						}
					},
				)
			}
		},
		modifier = modifier.background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick),
	)
}

/* The order chips at the top of a master-data list (M16). */
@Composable
fun ListOrderChips(order: ListOrder, onPick: (ListOrder) -> Unit) {
	Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
		for ((o, label) in listOf(
			ListOrder.ALPHABETICAL to R.string.order_alpha,
			ListOrder.LAST_USE to R.string.order_last_use,
			ListOrder.MANUAL to R.string.order_manual,
		))
			FilterChip(selected = order == o, onClick = { onPick(o) }, label = { Text(stringResource(label)) })
	}
}

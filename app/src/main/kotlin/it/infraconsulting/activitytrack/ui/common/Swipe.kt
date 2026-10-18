/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/* A button revealed behind a swiped card. */
data class SwipeAction(
	val label: String,
	@DrawableRes val icon: Int,
	val container: Color,
	val content: Color,
	val run: () -> Unit,
)

/* Where a released card goes: back, open on one side, or run the first action of that side. */
sealed interface Settle {
	data object Closed : Settle
	data class Open(val towardEnd: Boolean) : Settle
	data class Run(val towardEnd: Boolean) : Settle
}

/*
 * The release decision (pure, unit-tested). [towardEnd] is the card's
 * offset in the layout direction (positive = moved toward the end edge),
 * [velocity] likewise; [reveal] the widths of the buttons on each side.
 * A long swipe (past 60 % of the width) runs the first action when
 * [fullSwipe]; past half of the buttons, or a fling, opens them.
 */
fun settle(towardEnd: Float, velocity: Float, width: Float, revealEnd: Float, revealStart: Float, fullSwipe: Boolean): Settle {
	val end = towardEnd > 0
	val reveal = if (end) revealEnd else revealStart
	if (reveal <= 0f)
		return Settle.Closed
	val dist = abs(towardEnd)
	if (fullSwipe && dist > width * 0.6f)
		return Settle.Run(end)
	val fling = abs(velocity) > 1500f && (velocity > 0) == end
	return if (dist > reveal / 2 || (fling && dist > 8f)) Settle.Open(end) else Settle.Closed
}

private val ACTION_WIDTH = 88.dp

/*
 * Swipeable card (M10, owner decisions 2026-10-18). Dragging toward the
 * start edge reveals [toStart] at the end edge, toward the end reveals
 * [toEnd] at the start edge (left / right in left-to-right languages); a
 * tap on a button runs it, a long swipe runs the first one. Empty lists
 * disable that direction.
 */
@Composable
fun SwipeCard(
	toStart: List<SwipeAction>,
	toEnd: List<SwipeAction>,
	fullSwipe: Boolean,
	shape: Shape,
	modifier: Modifier = Modifier,
	content: @Composable () -> Unit,
) {
	val density = LocalDensity.current
	val dir = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1f else -1f
	/* screen offset in px, positive = moved right */
	var offset by remember { mutableFloatStateOf(0f) }
	val scope = rememberCoroutineScope()
	suspend fun animateTo(target: Float) = animate(offset, target) { v, _ -> offset = v }
	val actionPx = with(density) { ACTION_WIDTH.toPx() }

	BoxWithConstraints(modifier.clip(shape)) {
		val width = constraints.maxWidth.toFloat()
		/* layout offset = screen offset * dir (positive = toward the end edge) */
		val maxEnd = if (toEnd.isEmpty()) 0f else width
		val maxStart = if (toStart.isEmpty()) 0f else width
		fun close() = scope.launch { animateTo(0f) }
		fun runAndClose(a: SwipeAction) {
			close()
			a.run()
		}

		val towardEnd = offset * dir
		val side = when {
			towardEnd > 0f -> toEnd
			towardEnd < 0f -> toStart
			else -> emptyList()
		}
		if (side.isNotEmpty())
			Box(Modifier.matchParentSize().background(side.first().container)) {
				Row(
					modifier = Modifier.fillMaxHeight().align(if (towardEnd > 0f) Alignment.CenterStart else Alignment.CenterEnd),
					horizontalArrangement = Arrangement.Start,
				) {
					for (a in side)
						ActionButton(a) { runAndClose(a) }
				}
			}

		Box(
			Modifier
				.offset { IntOffset(offset.roundToInt(), 0) }
				.draggable(
					orientation = Orientation.Horizontal,
					state = rememberDraggableState { delta ->
						offset = ((offset + delta) * dir).coerceIn(-maxStart, maxEnd) * dir
					},
					onDragStopped = { velocity ->
						val s = settle(offset * dir, velocity * dir, width, toEnd.size * actionPx, toStart.size * actionPx, fullSwipe)
						when (s) {
							Settle.Closed -> animateTo(0f)
							is Settle.Open -> animateTo((if (s.towardEnd) toEnd.size else -toStart.size) * actionPx * dir)
							is Settle.Run -> {
								animateTo(0f)
								(if (s.towardEnd) toEnd else toStart).firstOrNull()?.run?.invoke()
							}
						}
					},
				),
		) { content() }
	}
}

@Composable
private fun ActionButton(a: SwipeAction, onClick: () -> Unit) {
	Column(
		modifier = Modifier.width(ACTION_WIDTH).fillMaxHeight().background(a.container).clickable(onClick = onClick),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.Center,
	) {
		Icon(painterResource(a.icon), contentDescription = null, tint = a.content, modifier = Modifier.size(24.dp))
		Text(a.label, color = a.content, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
	}
}

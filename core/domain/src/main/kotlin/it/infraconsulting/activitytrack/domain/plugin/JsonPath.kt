/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.plugin

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/*
 * The JSONPath subset of plugin selectors (docs/PLUGINS.md §5):
 *   $            the record
 *   .Name        a field ("['Odd name']" for other characters)
 *   [3]          an index;  [*]  every item
 *   [?(@.a.b == 'x')]  items whose field equals ('==', '!=') a string,
 *                      number, true, false or null
 * After a [*] or a filter the result is a list; a scalar use takes its
 * first item.
 */
class JsonPath private constructor(val text: String, private val steps: List<Step>) {
	private sealed interface Step {
		data class Field(val name: String) : Step
		data class Index(val i: Int) : Step
		data object All : Step
		data class Filter(val field: List<String>, val equal: Boolean, val value: JsonElement) : Step
	}

	/* every match, in order */
	fun all(root: JsonElement?): List<JsonElement> {
		var cur: List<JsonElement> = listOfNotNull(root)
		for (s in steps)
			cur = cur.flatMap { e ->
				when (s) {
					is Step.Field -> listOfNotNull((e as? JsonObject)?.get(s.name))
					is Step.Index -> listOfNotNull((e as? JsonArray)?.getOrNull(s.i))
					Step.All -> (e as? JsonArray) ?: (e as? JsonObject)?.values?.toList() ?: emptyList()
					is Step.Filter -> ((e as? JsonArray) ?: listOf(e)).filter { item -> matches(item, s) }
				}
			}
		return cur
	}

	/* a single value: the first match, or JsonNull */
	fun first(root: JsonElement?): JsonElement = all(root).firstOrNull() ?: JsonNull

	/* whether the path can give several values */
	val isList: Boolean get() = steps.any { it is Step.All || it is Step.Filter }

	private fun matches(item: JsonElement, f: Step.Filter): Boolean {
		var v: JsonElement? = item
		for (name in f.field)
			v = (v as? JsonObject)?.get(name)
		val same = sameValue(v ?: JsonNull, f.value)
		return same == f.equal
	}

	override fun toString() = text
	override fun equals(other: Any?) = other is JsonPath && other.text == text
	override fun hashCode() = text.hashCode()

	companion object {
		/* equality across number spellings ("1" vs 1.0 stays different: types count) */
		fun sameValue(a: JsonElement, b: JsonElement): Boolean = when {
			a is JsonNull || b is JsonNull -> a is JsonNull && b is JsonNull
			a is JsonPrimitive && b is JsonPrimitive && !a.isString && !b.isString && a.doubleOrNull != null && b.doubleOrNull != null ->
				a.doubleOrNull == b.doubleOrNull
			a is JsonPrimitive && b is JsonPrimitive -> a.isString == b.isString && a.content == b.content
			else -> a == b
		}

		/* @throws IllegalArgumentException with the position of the problem */
		fun parse(text: String): JsonPath {
			require(text.startsWith("$")) { "must start with \"$\"" }
			val steps = ArrayList<Step>()
			var i = 1
			fun fail(msg: String): Nothing = throw IllegalArgumentException("$msg at character ${i + 1} of \"$text\"")
			while (i < text.length) {
				when (text[i]) {
					'.' -> {
						val start = ++i
						while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '-' || text[i] == ':'))
							i++
						if (i == start)
							fail("a field name")
						steps += Step.Field(text.substring(start, i))
					}
					'[' -> {
						val close = closing(text, i) ?: fail("no closing ']'")
						val inner = text.substring(i + 1, close).trim()
						steps += when {
							inner == "*" -> Step.All
							inner.toIntOrNull() != null -> Step.Index(inner.toInt())
							inner.startsWith("'") && inner.endsWith("'") && inner.length >= 2 -> Step.Field(inner.substring(1, inner.length - 1))
							inner.startsWith("?(") && inner.endsWith(")") -> filter(inner.substring(2, inner.length - 1).trim()) ?: fail("a filter like ?(@.Type == 'x')")
							else -> fail("[*], [n], ['name'] or [?(…)]")
						}
						i = close + 1
					}
					else -> fail("'.' or '['")
				}
			}
			return JsonPath(text, steps)
		}

		private fun closing(text: String, open: Int): Int? {
			var quoted = false
			for (j in open + 1 until text.length) {
				when {
					text[j] == '\'' -> quoted = !quoted
					!quoted && text[j] == ']' -> return j
				}
			}
			return null
		}

		private val FILTER = Regex("@((?:\\.[A-Za-z0-9_:-]+)+)\\s*(==|!=)\\s*(.+)")

		private fun filter(s: String): Step.Filter? {
			val m = FILTER.matchEntire(s) ?: return null
			val field = m.groupValues[1].split('.').filter { it.isNotEmpty() }
			val raw = m.groupValues[3].trim()
			val value: JsonElement = when {
				raw == "null" -> JsonNull
				raw == "true" || raw == "false" -> JsonPrimitive(raw.toBoolean())
				raw.length >= 2 && raw.startsWith("'") && raw.endsWith("'") -> JsonPrimitive(raw.substring(1, raw.length - 1))
				raw.toDoubleOrNull() != null -> JsonPrimitive(raw.toDouble())
				else -> return null
			}
			return Step.Filter(field, m.groupValues[2] == "==", value)
		}
	}
}

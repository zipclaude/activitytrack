/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.plugin

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * A value in a plugin descriptor (docs/PLUGINS.md §5): a literal, a
 * selector on the remote record ("$.Name"), a reference to app data or
 * context ("@entry.activity"), a string with "${…}" references inside, or
 * a transform from the closed list. No scripts.
 */
sealed interface Expr {
	data class Literal(val value: JsonElement) : Expr
	data class Select(val path: JsonPath) : Expr
	/* @root.a.b: [root] one of [Ref.ROOTS] */
	data class Ref(val root: String, val path: List<String>) : Expr {
		override fun toString() = "@" + (listOf(root) + path).joinToString(".")
	}
	/* "text ${root.a} text": literal parts and references */
	data class Template(val parts: List<Any>) : Expr
	data class Op(val name: String, val args: List<Expr>, val params: Map<String, Expr> = emptyMap()) : Expr

	companion object {
		val NULL = Literal(JsonNull)

		val ROOTS = setOf("option", "identity", "lookup", "parent", "entry", "todo", "block", "field", "list", "remote")

		/*
		 * Transforms: name → (number of arguments, -1 = a list of any
		 * length; extra keys allowed beside the name).
		 */
		val OPS: Map<String, Pair<Int, Set<String>>> = mapOf(
			"coalesce" to (-1 to emptySet()),
			"concat" to (-1 to setOf("separator")),
			"and" to (-1 to emptySet()),
			"or" to (-1 to emptySet()),
			"eq" to (2 to emptySet()),
			"ne" to (2 to emptySet()),
			"in" to (2 to emptySet()),
			"notIn" to (2 to emptySet()),
			"not" to (1 to emptySet()),
			"lower" to (1 to emptySet()),
			"upper" to (1 to emptySet()),
			"trim" to (1 to emptySet()),
			"plainText" to (1 to emptySet()),
			"number" to (1 to emptySet()),
			"bool" to (1 to emptySet()),
			"string" to (1 to emptySet()),
			"if" to (1 to setOf("then", "else")),
			"map" to (1 to setOf("cases", "default")),
			"date" to (1 to setOf("format")),
			"minutes" to (1 to setOf("round")),
			"materials" to (1 to emptySet()),
		)

		val DATE_FORMATS = setOf("isoOffset", "isoLocal", "isoDate", "epochMs")
		val ROUNDINGS = setOf("work", "travel", "pause", "none")
		private val REF = Regex("@([A-Za-z][A-Za-z0-9]*)((?:\\.[A-Za-z0-9_:-]+)*)")
		private val TEMPLATE_REF = Regex("\\$\\{([A-Za-z][A-Za-z0-9]*)((?:\\.[A-Za-z0-9_:-]+)*)\\}")

		/*
		 * Parses [e]; problems go to [error] with a sub-path ("" = here).
		 * Returns null when [e] cannot become an expression at all.
		 */
		fun parse(e: JsonElement, error: (String, String) -> Unit): Expr? = when (e) {
			is JsonNull -> NULL
			is JsonArray -> Literal(e)
			is JsonPrimitive -> if (!e.isString) Literal(e) else text(e.content, error)
			is JsonObject -> op(e, error)
		}

		private fun text(s: String, error: (String, String) -> Unit): Expr? = when {
			s.startsWith("$") && !s.startsWith("\${") -> try {
				Select(JsonPath.parse(s))
			} catch (x: IllegalArgumentException) {
				null.also { error("", "selector: ${x.message}") }
			}
			s.startsWith("@") -> {
				val m = REF.matchEntire(s)
				if (m == null)
					null.also { error("", "a reference like \"@entry.activity\"") }
				else
					ref(m.groupValues[1], m.groupValues[2], error)
			}
			"\${" in s -> {
				val parts = ArrayList<Any>()
				var at = 0
				for (m in TEMPLATE_REF.findAll(s)) {
					if (m.range.first > at)
						parts += s.substring(at, m.range.first)
					ref(m.groupValues[1], m.groupValues[2], error)?.let { parts += it }
					at = m.range.last + 1
				}
				if (at < s.length)
					parts += s.substring(at)
				if (parts.any { it is String && "\${" in it })
					error("", "a \"\${…}\" reference must be like \"\${remote.id}\"")
				Template(parts)
			}
			else -> Literal(JsonPrimitive(s))
		}

		private fun ref(root: String, rest: String, error: (String, String) -> Unit): Ref? =
			if (root !in ROOTS)
				null.also { error("", "unknown reference \"@$root\" (known: ${ROOTS.joinToString()})") }
			else
				Ref(root, rest.split('.').filter { it.isNotEmpty() })

		private fun op(o: JsonObject, error: (String, String) -> Unit): Expr? {
			val names = o.keys.filter { it in OPS }
			if (names.size != 1)
				return null.also { error("", if (names.isEmpty()) "unknown transform (known: ${OPS.keys.joinToString()})" else "one transform per object: ${names.joinToString()}") }
			val name = names.single()
			val (arity, extra) = OPS.getValue(name)
			for (k in o.keys - name - extra)
				error(k, "unknown key for \"$name\" (allowed: ${extra.joinToString().ifEmpty { "none" }})")
			val raw = o.getValue(name)
			val args: List<JsonElement> = when {
				arity == -1 -> (raw as? JsonArray) ?: return null.also { error(name, "must be a list") }
				arity == 1 -> listOf(raw)
				else -> (raw as? JsonArray)?.takeIf { it.size == arity } ?: return null.also { error(name, "must be a list of $arity values") }
			}
			val parsed = args.mapIndexed { i, a -> parse(a) { p, m -> error(sub(name, if (arity == 1) "" else "$i", p), m) } }
			if (parsed.any { it == null })
				return null
			val params = HashMap<String, Expr>()
			for (k in extra) {
				val v = o[k] ?: continue
				when {
					name == "map" && k == "cases" -> {
						val cases = v as? JsonObject ?: return null.also { error(k, "must be an object of value → result") }
						cases.forEach { (case, r) -> parse(r) { p, m -> error(sub(k, case, p), m) }?.let { params["case:$case"] = it } }
					}
					else -> parse(v) { p, m -> error(sub(k, p), m) }?.let { params[k] = it }
				}
			}
			when (name) {
				"if" -> if ("then" !in o) error("", "\"if\" needs \"then\"")
				"map" -> if ("cases" !in o) error("", "\"map\" needs \"cases\"")
				"date" -> (o["format"] as? JsonPrimitive)?.content.let {
					if (it == null || (it !in DATE_FORMATS && !it.any(Char::isLetter)))
						error("format", "one of ${DATE_FORMATS.joinToString()} or a date pattern")
				}
				"minutes" -> (o["round"] as? JsonPrimitive)?.content.let {
					if (it != null && it !in ROUNDINGS)
						error("round", "one of ${ROUNDINGS.joinToString()}")
				}
				"materials" -> (raw as? JsonPrimitive)?.content.let {
					if (it != "text" && it != "lines")
						error(name, "\"text\" or \"lines\"")
				}
			}
			return Op(name, parsed.map { it!! }, params)
		}

		private fun sub(vararg parts: String) = parts.filter { it.isNotEmpty() }.joinToString(".")
	}
}

/* Every reference in [e], for the checks on the roots and names a section may use. */
fun Expr.refs(): List<Expr.Ref> = when (this) {
	is Expr.Literal, is Expr.Select -> emptyList()
	is Expr.Ref -> listOf(this)
	is Expr.Template -> parts.filterIsInstance<Expr.Ref>()
	is Expr.Op -> args.flatMap { it.refs() } + params.values.flatMap { it.refs() }
}

fun Expr.usesSelector(): Boolean = when (this) {
	is Expr.Literal, is Expr.Ref, is Expr.Template -> false
	is Expr.Select -> true
	is Expr.Op -> args.any { it.usesSelector() } || params.values.any { it.usesSelector() }
}

/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.plugin

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/* What an expression may ask of the app while it is evaluated. */
interface EvalEnv {
	/* the value of @root.a.b; JsonNull when absent */
	fun ref(r: Expr.Ref): JsonElement

	val zone: ZoneId

	/* minutes rounded by the report rule "work", "travel" or "pause" */
	fun round(rule: String, minutes: Long): Long = minutes

	/* the entry's materials, "text" or "lines" */
	fun materials(mode: String): JsonElement = JsonNull
}

/*
 * Evaluates [this] on the remote record [item] (for selectors). Never
 * throws on data: a value of the wrong kind gives null, like a missing one.
 */
fun Expr.eval(item: JsonElement?, env: EvalEnv): JsonElement = when (this) {
	is Expr.Literal -> value
	is Expr.Select -> if (path.isList) JsonArray(path.all(item)) else path.first(item)
	is Expr.Ref -> env.ref(this)
	is Expr.Template -> JsonPrimitive(parts.joinToString("") { p -> if (p is Expr.Ref) text(env.ref(p)).orEmpty() else p as String })
	is Expr.Op -> Ops.run(this, item, env)
}

/* Truth of a value: null, false, 0, "" and [] are false. */
fun truthy(v: JsonElement): Boolean = when (v) {
	is JsonNull -> false
	is JsonArray -> v.isNotEmpty()
	is JsonObject -> v.isNotEmpty()
	is JsonPrimitive -> when {
		v.isString -> v.content.isNotEmpty()
		v.booleanOrNull != null -> v.booleanOrNull!!
		else -> (v.doubleOrNull ?: 0.0) != 0.0
	}
}

/* Text of a scalar; null for null, lists and objects. */
fun text(v: JsonElement): String? = when (v) {
	is JsonPrimitive -> if (v is JsonNull) null else v.content
	else -> null
}

private fun isEmpty(v: JsonElement) = v is JsonNull || (v is JsonPrimitive && v.isString && v.content.isEmpty()) || (v is JsonArray && v.isEmpty())

private object Ops {
	private val ISO_OFFSET = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
	private val ISO_LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
	private val ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE

	fun run(op: Expr.Op, item: JsonElement?, env: EvalEnv): JsonElement {
		fun arg(i: Int) = op.args[i].eval(item, env)
		fun param(k: String) = op.params[k]?.eval(item, env)
		return when (op.name) {
			"coalesce" -> op.args.asSequence().map { it.eval(item, env) }.firstOrNull { !isEmpty(it) } ?: JsonNull
			"concat" -> {
				val sep = param("separator")?.let(::text).orEmpty()
				JsonPrimitive(op.args.mapNotNull { text(it.eval(item, env))?.takeIf(String::isNotEmpty) }.joinToString(sep))
			}
			"and" -> JsonPrimitive(op.args.all { truthy(it.eval(item, env)) })
			"or" -> JsonPrimitive(op.args.any { truthy(it.eval(item, env)) })
			"not" -> JsonPrimitive(!truthy(arg(0)))
			"eq" -> JsonPrimitive(JsonPath.sameValue(arg(0), arg(1)))
			"ne" -> JsonPrimitive(!JsonPath.sameValue(arg(0), arg(1)))
			"in", "notIn" -> {
				val v = arg(0)
				val set = arg(1) as? JsonArray ?: JsonArray(emptyList())
				JsonPrimitive(set.any { JsonPath.sameValue(it, v) } == (op.name == "in"))
			}
			"lower" -> text(arg(0))?.let { JsonPrimitive(it.lowercase()) } ?: JsonNull
			"upper" -> text(arg(0))?.let { JsonPrimitive(it.uppercase()) } ?: JsonNull
			"trim" -> text(arg(0))?.let { JsonPrimitive(it.trim()) } ?: JsonNull
			"plainText" -> text(arg(0))?.let { JsonPrimitive(plainText(it)) } ?: JsonNull
			"string" -> text(arg(0))?.let { JsonPrimitive(it) } ?: JsonNull
			"number" -> text(arg(0))?.trim()?.toBigDecimalOrNull()?.let { JsonPrimitive(it.stripTrailingZeros().let { n -> if (n.scale() <= 0) n.toLong() as Number else n.toDouble() }) } ?: JsonNull
			"bool" -> when (val v = arg(0)) {
				is JsonPrimitive -> if (v.isString) when (v.content.lowercase()) {
					"true", "1", "yes" -> JsonPrimitive(true)
					"false", "0", "no", "" -> JsonPrimitive(false)
					else -> JsonNull
				} else JsonPrimitive(truthy(v))
				else -> JsonNull
			}
			"if" -> if (truthy(arg(0))) param("then") ?: JsonNull else param("else") ?: JsonNull
			"object" -> JsonObject(
				op.params.entries.mapNotNull { (k, x) -> x.eval(item, env).takeIf { it !is JsonNull }?.let { k.removePrefix("key:") to it } }.toMap(LinkedHashMap()),
			)
			"map" -> {
				val key = text(arg(0))
				(key?.let { op.params["case:$it"] }?.eval(item, env)) ?: param("default") ?: JsonNull
			}
			"date" -> date(arg(0), text(param("format") ?: JsonNull) ?: "isoOffset", env.zone)
			"minutes" -> {
				val m = (arg(0) as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.toLong() ?: return JsonNull
				val rule = text(param("round") ?: JsonNull) ?: "none"
				JsonPrimitive(if (rule == "none") m else env.round(rule, m))
			}
			"materials" -> env.materials(text(arg(0)) ?: "text")
			else -> JsonNull
		}
	}

	/* An instant from epoch milliseconds or an ISO date-time, written in [zone]. */
	private fun date(v: JsonElement, format: String, zone: ZoneId): JsonElement {
		val p = v as? JsonPrimitive ?: return JsonNull
		val instant = when {
			p is JsonNull -> return JsonNull
			!p.isString -> p.longOrNull?.let(Instant::ofEpochMilli)
			/* milliseconds also as text (ServiceDesk Plus: "value": "1482208200805") */
			p.content.all(Char::isDigit) && p.content.isNotEmpty() -> p.content.toLongOrNull()?.let(Instant::ofEpochMilli)
			else -> runCatching { OffsetDateTime.parse(p.content).toInstant() }.getOrNull()
				?: runCatching { Instant.parse(p.content) }.getOrNull()
		} ?: return JsonNull
		val t = instant.atZone(zone)
		return when (format) {
			"epochMs" -> JsonPrimitive(instant.toEpochMilli())
			"isoOffset" -> JsonPrimitive(ISO_OFFSET.format(t))
			"isoLocal" -> JsonPrimitive(ISO_LOCAL.format(t))
			"isoDate" -> JsonPrimitive(ISO_DATE.format(t))
			else -> runCatching { JsonPrimitive(DateTimeFormatter.ofPattern(format).format(t)) }.getOrDefault(JsonNull)
		}
	}
}

/*
 * Text of an HTML fragment (ticket descriptions of management systems):
 * line breaks for <br> and block ends, tags dropped, the common entities
 * decoded, blank lines collapsed. Nothing of it is ever rendered as HTML.
 */
fun plainText(html: String): String {
	var s = html.replace(Regex("(?i)<br\\s*/?>|</(p|div|li|tr|h[1-6])>"), "\n")
	s = s.replace(Regex("(?s)<!--.*?-->"), "").replace(Regex("<[^>]*>"), "")
	s = Regex("&(#\\d+|#x[0-9A-Fa-f]+|[A-Za-z]+);").replace(s) { m ->
		val e = m.groupValues[1]
		when {
			e.startsWith("#x") -> e.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
			e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) }
			else -> ENTITIES[e]
		} ?: m.value
	}
	return s.lines().map { it.replace(Regex("[ \\t]+"), " ").trim() }.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
}

private val ENTITIES = mapOf(
	"amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
	"agrave" to "à", "egrave" to "è", "eacute" to "é", "igrave" to "ì", "ograve" to "ò", "ugrave" to "ù",
	"Agrave" to "À", "Egrave" to "È", "Eacute" to "É", "Igrave" to "Ì", "Ograve" to "Ò", "Ugrave" to "Ù",
	"euro" to "€", "laquo" to "«", "raquo" to "»", "hellip" to "…", "ndash" to "–", "mdash" to "—",
)

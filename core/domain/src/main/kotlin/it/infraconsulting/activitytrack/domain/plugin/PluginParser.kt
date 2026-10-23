/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.plugin

import it.infraconsulting.activitytrack.domain.policy.CheckedReader
import it.infraconsulting.activitytrack.domain.policy.PolicyError
import it.infraconsulting.activitytrack.domain.policy.PolicyParser
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

sealed interface PluginResult {
	data class Valid(val plugin: Plugin) : PluginResult
	data class Invalid(val errors: List<PolicyError>) : PluginResult
}

/*
 * Reads and checks a plugin descriptor (docs/PLUGINS.md): syntax, schema,
 * expressions, and the references each section may use (an export body
 * may read @block, an import mapping may not; @option.x must be an option
 * of the file, @lookup.x a lookup, @field.x an entry field…). Every error
 * is collected with its path and line, as for policy.json.
 */
object PluginParser {
	const val FORMAT = "activitytrack-plugin"
	const val SCHEMA = 1

	private val ID = Regex("[a-z][a-z0-9_-]{0,39}")
	private val KEY = Regex("[A-Za-z][A-Za-z0-9_]{0,39}")
	private val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE")
	private val LIST_PROPS = setOf("id", "name", "clientCode", "kind", "active", "order", "parentId")

	/* the roots each section may reference */
	private val R_CONFIG = setOf("option")
	private val R_REQUEST = setOf("option", "identity", "lookup")
	private val R_RECORD = setOf("option", "identity", "lookup")
	private val R_ENTRY_FORM = setOf("option", "entry", "todo", "field", "list")
	private val R_BODY = setOf("option", "identity", "lookup", "entry", "todo", "block", "field", "list")

	fun parse(text: String, fileId: String? = null): PluginResult {
		val root = try {
			Json.parseToJsonElement(text)
		} catch (e: SerializationException) {
			val offset = Regex("offset (\\d+)").find(e.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
			val line = offset?.let { 1 + text.subSequence(0, it.coerceIn(0, text.length)).count { c -> c == '\n' } }
			return PluginResult.Invalid(listOf(PolicyError("", line, "syntax error: " + e.message.orEmpty().lineSequence().first())))
		}
		val r = Reader(text)
		val plugin = r.plugin(root, fileId)
		return if (plugin != null && r.errors.isEmpty()) PluginResult.Valid(plugin) else PluginResult.Invalid(r.errors.distinct())
	}

	private class Reader(text: String) : CheckedReader(text) {
		/* names declared so far, for the reference checks */
		val options = HashSet<String>()
		val lookups = HashSet<String>()
		val identity = HashSet<String>()
		val lists = HashSet<String>()
		val fields = HashSet<String>()

		/* (path, expression, roots allowed, selector allowed, parent allowed): checked once everything is declared */
		private class Pending(val path: String, val expr: Expr, val roots: Set<String>, val selector: Boolean)

		private val pending = ArrayList<Pending>()

		fun expr(e: JsonElement?, path: String, roots: Set<String>, selector: Boolean = false, required: Boolean = false): Expr? {
			if (e == null)
				return null.also { if (required) error(path, "required") }
			val x = Expr.parse(e) { sub, msg -> error(join(path, sub), msg) } ?: return null
			pending += Pending(path, x, roots, selector)
			return x
		}

		private fun exprMap(e: JsonElement?, path: String, roots: Set<String>, selector: Boolean = false): Map<String, Expr> {
			val o = obj(e, path) ?: return emptyMap()
			return o.mapNotNull { (k, v) -> expr(v, join(path, k), roots, selector)?.let { k to it } }.toMap()
		}

		fun plugin(root: JsonElement, fileId: String?): Plugin? {
			val o = obj(root, "") ?: return null.also { error("", "the plugin must be a JSON object") }
			keys(o, "", setOf("format", "schema", "id", "name", "version", "options", "auth", "http", "identity", "lookups", "lists", "import", "entryFields", "export", "schedule"))
			if (string(o["format"], "format") != FORMAT)
				error("format", "must be \"$FORMAT\"")
			val schema = int(o["schema"], "schema", 1..Int.MAX_VALUE)
			if (schema == null)
				error("schema", "required, $SCHEMA")
			else if (schema > SCHEMA)
				error("schema", "schema $schema is newer than this app ($SCHEMA): update the app")
			val id = string(o["id"], "id")
			when {
				id == null -> error("id", "required")
				!ID.matches(id) -> error("id", "lower-case letters, digits, '-' and '_'")
				fileId != null && id != fileId -> error("id", "must be \"$fileId\", the file name")
			}
			val name = string(o["name"], "name") ?: id.orEmpty().also { error("name", "required") }

			val opts = options(o["options"])
			val identity = identity(o["identity"])
			val lookups = lookups(o["lookups"])
			val listMap = lists(o["lists"])
			val entryFields = entryFields(o["entryFields"])
			val plugin = Plugin(
				id = id.orEmpty(),
				name = name,
				version = string(o["version"], "version").orEmpty(),
				options = opts,
				auth = auth(o["auth"]),
				http = http(o["http"]),
				identity = identity,
				lookups = lookups,
				lists = listMap,
				imports = imports(o["import"]),
				entryFields = entryFields,
				export = export(o["export"]),
				schedule = schedule(o["schedule"]),
			)
			checkReferences(listMap)
			if (plugin.imports.isEmpty() && plugin.export == null && plugin.lists.isEmpty())
				error("", "the plugin neither imports, lists nor exports anything")
			return plugin
		}

		private fun options(e: JsonElement?): List<PluginOption> {
			val a = e?.let { it as? JsonArray ?: null.also { _ -> error("options", "must be a list") } } ?: return emptyList()
			return a.mapIndexedNotNull { i, v ->
				val p = "options.$i"
				val o = obj(v, p) ?: return@mapIndexedNotNull null
				keys(o, p, setOf("key", "type", "label", "required", "default", "min", "max", "choices"))
				val key = string(o["key"], "$p.key")
				if (key == null || !KEY.matches(key))
					return@mapIndexedNotNull null.also { error("$p.key", "required, letters and digits") }
				if (!options.add(key))
					error("$p.key", "\"$key\" is already an option")
				val type = choice(o["type"], "$p.type", OptionType.byKey) ?: return@mapIndexedNotNull null.also { if (o["type"] == null) error("$p.type", "required") }
				val choices = obj(o["choices"], "$p.choices")?.mapValues { (c, l) -> labels(l, "$p.choices.$c") }.orEmpty()
				if (type == OptionType.CHOICE && choices.isEmpty())
					error("$p.choices", "a choice option needs its choices")
				val default = o["default"]?.let { d ->
					(d as? JsonPrimitive)?.takeIf { type != OptionType.SECRET } ?: null.also { error("$p.default", if (type == OptionType.SECRET) "a secret has no default" else "must be a value") }
				}
				if (default != null && type == OptionType.CHOICE && default.content !in choices)
					error("$p.default", "must be one of the choices")
				if (default != null && type == OptionType.URL && !default.content.startsWith("https://"))
					error("$p.default", "an https:// address")
				PluginOption(
					key, type, labels(o["label"], "$p.label", 60), bool(o["required"], "$p.required") ?: false, default,
					int(o["min"], "$p.min", Int.MIN_VALUE..Int.MAX_VALUE), int(o["max"], "$p.max", Int.MIN_VALUE..Int.MAX_VALUE), choices,
				)
			}
		}

		private fun auth(e: JsonElement?): Auth {
			val o = obj(e, "auth") ?: return Auth.None
			val type = string(o["type"], "auth.type") ?: return Auth.None.also { error("auth.type", "required") }
			fun x(k: String, required: Boolean = true) = expr(o[k], "auth.$k", R_CONFIG, required = required)
			return when (type) {
				"none" -> Auth.None.also { keys(o, "auth", setOf("type")) }
				"basic" -> {
					keys(o, "auth", setOf("type", "user", "password"))
					Auth.Basic(x("user") ?: Expr.NULL, x("password") ?: Expr.NULL)
				}
				"apiKey" -> {
					keys(o, "auth", setOf("type", "header", "query", "key"))
					val header = string(o["header"], "auth.header")
					val query = string(o["query"], "auth.query")
					if ((header == null) == (query == null))
						error("auth", "\"header\" or \"query\", one of the two")
					Auth.ApiKey(header, query, x("key") ?: Expr.NULL)
				}
				"bearer" -> {
					keys(o, "auth", setOf("type", "token"))
					Auth.Bearer(x("token") ?: Expr.NULL)
				}
				"oauth2Pkce" -> {
					keys(o, "auth", setOf("type", "issuer", "authorizeUrl", "tokenUrl", "clientId", "scopes"))
					val issuer = x("issuer", required = false)
					val authorize = x("authorizeUrl", required = false)
					val token = x("tokenUrl", required = false)
					if (issuer == null && (authorize == null || token == null))
						error("auth", "\"issuer\", or both \"authorizeUrl\" and \"tokenUrl\"")
					Auth.OAuth2Pkce(issuer, authorize, token, x("clientId") ?: Expr.NULL, string(o["scopes"], "auth.scopes").orEmpty())
				}
				else -> Auth.None.also { error("auth.type", "one of none, basic, apiKey, bearer, oauth2Pkce") }
			}
		}

		private fun http(e: JsonElement?): Http {
			val o = obj(e, "http")
			if (o == null) {
				error("http", "required, with \"baseUrl\"")
				return Http(Expr.NULL, emptyMap(), 60, Paging.None)
			}
			keys(o, "http", setOf("baseUrl", "headers", "timeoutSec", "paging"))
			val paging = obj(o["paging"], "http.paging")?.let { p ->
				val path = "http.paging"
				when (string(p["style"], "$path.style")) {
					null, "none" -> Paging.None.also { keys(p, path, setOf("style")) }
					"odata" -> {
						keys(p, path, setOf("style", "pageSize"))
						Paging.OData(int(p["pageSize"], "$path.pageSize", 1..10_000) ?: 100)
					}
					"page" -> {
						keys(p, path, setOf("style", "pageParam", "sizeParam", "firstPage", "pageSize"))
						Paging.Page(
							string(p["pageParam"], "$path.pageParam") ?: "page", string(p["sizeParam"], "$path.sizeParam") ?: "size",
							int(p["firstPage"], "$path.firstPage", 0..1) ?: 1, int(p["pageSize"], "$path.pageSize", 1..10_000) ?: 100,
						)
					}
					"cursor" -> {
						keys(p, path, setOf("style", "cursorParam", "next"))
						Paging.Cursor(
							string(p["cursorParam"], "$path.cursorParam") ?: "cursor",
							expr(p["next"], "$path.next", emptySet(), selector = true, required = true) ?: Expr.NULL,
						)
					}
					"link" -> Paging.Link.also { keys(p, path, setOf("style")) }
					else -> Paging.None.also { error("$path.style", "one of none, odata, page, cursor, link") }
				}
			} ?: Paging.None
			return Http(
				expr(o["baseUrl"], "http.baseUrl", R_CONFIG, required = true) ?: Expr.NULL,
				exprMap(o["headers"], "http.headers", R_CONFIG),
				int(o["timeoutSec"], "http.timeoutSec", 5..300) ?: 60,
				paging,
			)
		}

		private fun request(e: JsonElement?, path: String, roots: Set<String>): Request? {
			val o = obj(e, path) ?: return null.also { if (e == null) error(path, "required") }
			keys(o, path, setOf("method", "path", "query", "paged", "items"))
			val method = string(o["method"], "$path.method")?.uppercase() ?: "GET"
			if (method !in METHODS)
				error("$path.method", "one of ${METHODS.joinToString()}")
			val p = expr(o["path"], "$path.path", roots, required = true) ?: return null
			return Request(method, p, exprMap(o["query"], "$path.query", roots), bool(o["paged"], "$path.paged") ?: false, expr(o["items"], "$path.items", emptySet(), selector = true))
		}

		private fun identity(e: JsonElement?): Identity? {
			val o = obj(e, "identity") ?: return null
			keys(o, "identity", setOf("request", "values"))
			val values = exprMap(o["values"], "identity.values", emptySet(), selector = true)
			identity += values.keys
			return Identity(request(o["request"], "identity.request", R_CONFIG) ?: return null, values)
		}

		private fun lookups(e: JsonElement?): Map<String, Lookup> {
			val o = obj(e, "lookups") ?: return emptyMap()
			lookups += o.keys
			return o.mapNotNull { (name, v) ->
				val p = "lookups.$name"
				val lo = obj(v, p) ?: return@mapNotNull null
				keys(lo, p, setOf("request", "select"))
				val req = request(lo["request"], "$p.request", setOf("option", "identity")) ?: return@mapNotNull null
				name to Lookup(req, expr(lo["select"], "$p.select", emptySet(), selector = true, required = true) ?: return@mapNotNull null)
			}.toMap()
		}

		private fun lists(e: JsonElement?): Map<String, RemoteList> {
			val o = obj(e, "lists") ?: return emptyMap()
			lists += o.keys
			return o.mapNotNull { (name, v) ->
				val p = "lists.$name"
				val lo = obj(v, p) ?: return@mapNotNull null
				keys(lo, p, setOf("parent", "sources"))
				val parent = string(lo["parent"], "$p.parent")
				if (parent != null && (parent !in o.keys || parent == name))
					error("$p.parent", "another list of this file")
				val roots = if (parent != null) R_REQUEST + "parent" else R_REQUEST
				val sources = (lo["sources"] as? JsonArray)?.mapIndexedNotNull { i, s ->
					val sp = "$p.sources.$i"
					val so = obj(s, sp) ?: return@mapIndexedNotNull null
					keys(so, sp, setOf("request", "map", "when"))
					val req = request(so["request"], "$sp.request", roots) ?: return@mapIndexedNotNull null
					val mo = obj(so["map"], "$sp.map") ?: return@mapIndexedNotNull null.also { error("$sp.map", "required") }
					keys(mo, "$sp.map", LIST_PROPS + "extra")
					if ("id" !in mo || "name" !in mo)
						error("$sp.map", "needs \"id\" and \"name\"")
					val map = mo.filterKeys { it != "extra" }.mapNotNull { (k, x) -> expr(x, "$sp.map.$k", R_RECORD, selector = true)?.let { k to it } }.toMap() +
						exprMap(mo["extra"], "$sp.map.extra", R_RECORD, selector = true).mapKeys { "extra.${it.key}" }
					ListSource(req, map, so["when"]?.let { expr(it, "$sp.when", roots) })
				} ?: emptyList<ListSource>().also { error("$p.sources", "required, a list") }
				name to RemoteList(parent, sources)
			}.toMap()
		}

		private fun imports(e: JsonElement?): Map<ImportTarget, ImportSpec> {
			val o = obj(e, "import") ?: return emptyMap()
			keys(o, "import", ImportTarget.byKey.keys)
			return o.mapNotNull { (k, v) ->
				val target = ImportTarget.byKey[k] ?: return@mapNotNull null
				val p = "import.$k"
				val io = obj(v, p) ?: return@mapNotNull null
				keys(io, p, setOf("request", "key", "skipNew", "map"))
				val req = request(io["request"], "$p.request", R_REQUEST) ?: return@mapNotNull null
				val mo = obj(io["map"], "$p.map") ?: return@mapNotNull null.also { error("$p.map", "required") }
				val allowed = target.fields + if (target == ImportTarget.TODOS) setOf("fields") else emptySet()
				keys(mo, "$p.map", allowed)
				val first = if (target == ImportTarget.TODOS) "title" else "name"
				if (first !in mo)
					error("$p.map", "needs \"$first\"")
				target to ImportSpec(
					req,
					expr(io["key"], "$p.key", R_RECORD, selector = true, required = true) ?: return@mapNotNull null,
					expr(io["skipNew"], "$p.skipNew", R_RECORD, selector = true),
					mo.filterKeys { it != "fields" }.mapNotNull { (f, x) -> expr(x, "$p.map.$f", R_RECORD, selector = true)?.let { f to it } }.toMap(),
					exprMap(mo["fields"], "$p.map.fields", R_RECORD, selector = true),
				)
			}.toMap()
		}

		private fun entryFields(e: JsonElement?): List<EntryField> {
			val a = e?.let { it as? JsonArray ?: null.also { _ -> error("entryFields", "must be a list") } } ?: return emptyList()
			return a.mapIndexedNotNull { i, v ->
				val p = "entryFields.$i"
				val o = obj(v, p) ?: return@mapIndexedNotNull null
				keys(o, p, setOf("key", "type", "list", "parent", "label", "required", "hidden", "filter", "default"))
				val key = string(o["key"], "$p.key")
				if (key == null || !KEY.matches(key))
					return@mapIndexedNotNull null.also { error("$p.key", "required, letters and digits") }
				if (!fields.add(key))
					error("$p.key", "\"$key\" is already a field")
				val list = string(o["list"], "$p.list")
				val type = o["type"]?.let { choice(it, "$p.type", FieldType.byKey) } ?: if (list != null) FieldType.LIST else FieldType.STRING
				if (type == FieldType.LIST && list == null)
					error("$p.list", "a list field names its list")
				if (list != null && list !in lists)
					error("$p.list", "no list \"$list\" in \"lists\"")
				val filter = obj(o["filter"], "$p.filter")?.let { fo ->
					keys(fo, "$p.filter", LIST_PROPS)
					fo.mapNotNull { (k, x) -> expr(x, "$p.filter.$k", R_ENTRY_FORM)?.let { k to it } }.toMap()
				}.orEmpty()
				EntryField(
					key, type, list,
					parent = expr(o["parent"], "$p.parent", R_ENTRY_FORM),
					label = labels(o["label"], "$p.label", 60),
					required = expr(o["required"], "$p.required", R_ENTRY_FORM) ?: Expr.Literal(JsonPrimitive(false)),
					hidden = expr(o["hidden"], "$p.hidden", R_ENTRY_FORM) ?: Expr.Literal(JsonPrimitive(false)),
					filter = filter,
					default = expr(o["default"], "$p.default", R_ENTRY_FORM),
				)
			}
		}

		private fun export(e: JsonElement?): ExportSpec? {
			val o = obj(e, "export") ?: return null
			keys(o, "export", setOf("entries", "group", "body", "travelBody", "create", "update", "delete"))
			string(o["entries"], "export.entries")?.let { if (it != "closed") error("export.entries", "only \"closed\" for now") }
			val g = obj(o["group"], "export.group")
			val group = if (g == null) {
				Group(GroupBy.ENTRY, null, TravelMode.NEAREST_BLOCK, "work")
			} else {
				keys(g, "export.group", setOf("by", "splitPauseMin", "travel", "round"))
				val round = string(g["round"], "export.group.round") ?: "work"
				if (round !in Expr.ROUNDINGS)
					error("export.group.round", "one of ${Expr.ROUNDINGS.joinToString()}")
				val by = choice(g["by"], "export.group.by", GroupBy.byKey) ?: GroupBy.ENTRY
				val split = expr(g["splitPauseMin"], "export.group.splitPauseMin", R_CONFIG)
				if (split != null && by != GroupBy.DAY_BLOCK)
					error("export.group.splitPauseMin", "only with \"by\": \"dayBlock\"")
				Group(by, split, choice(g["travel"], "export.group.travel", TravelMode.byKey) ?: TravelMode.NEAREST_BLOCK, round)
			}
			val body = exprMap(o["body"], "export.body", R_BODY)
			if (body.isEmpty())
				error("export.body", "required")
			val travelBody = o["travelBody"]?.let { exprMap(it, "export.travelBody", R_BODY) }
			if (group.travel == TravelMode.SEPARATE && travelBody == null)
				error("export.travelBody", "required with \"travel\": \"separate\"")
			val create = call(o["create"], "export.create", R_CONFIG, idSelector = true) ?: return null.also { if (o["create"] == null) error("export.create", "required") }
			if (create.id == null)
				error("export.create.id", "required: where the response gives the new record's id")
			return ExportSpec(
				group, body, travelBody, create,
				call(o["update"], "export.update", R_CONFIG + "remote", addRoots = R_BODY + "remote"),
				call(o["delete"], "export.delete", R_CONFIG + "remote"),
			)
		}

		private fun call(e: JsonElement?, path: String, roots: Set<String>, idSelector: Boolean = false, addRoots: Set<String>? = null): Call? {
			val o = obj(e, path) ?: return null
			keys(o, path, setOfNotNull("method", "path", if (idSelector) "id" else null, if (addRoots != null) "fetchFirst" else null, if (addRoots != null) "add" else null))
			val method = string(o["method"], "$path.method")?.uppercase() ?: return null.also { error("$path.method", "required") }
			if (method !in METHODS)
				error("$path.method", "one of ${METHODS.joinToString()}")
			return Call(
				method,
				expr(o["path"], "$path.path", roots, required = true) ?: return null,
				if (idSelector) expr(o["id"], "$path.id", emptySet(), selector = true) else null,
				addRoots != null && (bool(o["fetchFirst"], "$path.fetchFirst") ?: false),
				addRoots?.let { exprMap(o["add"], "$path.add", it) }.orEmpty(),
			)
		}

		private fun schedule(e: JsonElement?): Schedule? {
			val o = obj(e, "schedule") ?: return null
			keys(o, "schedule", setOf("onClose", "fetchEveryMin"))
			val c = obj(o["onClose"], "schedule.onClose")?.also { keys(it, "schedule.onClose", setOf("mode", "scope", "sync")) }
			return Schedule(
				c?.let { expr(it["mode"], "schedule.onClose.mode", R_CONFIG) },
				c?.let { expr(it["scope"], "schedule.onClose.scope", R_CONFIG) },
				c?.let { expr(it["sync"], "schedule.onClose.sync", R_CONFIG) },
				expr(o["fetchEveryMin"], "schedule.fetchEveryMin", R_CONFIG),
			)
		}

		/* Once everything is declared: the roots each expression uses, and the names behind them. */
		private fun checkReferences(lists: Map<String, RemoteList>) {
			for (p in pending) {
				if (!p.selector && p.expr.usesSelector())
					error(p.path, "a \"$\" selector cannot be used here: there is no remote record")
				for (r in p.expr.refs()) {
					val name = r.path.firstOrNull()
					when {
						r.root !in p.roots -> error(p.path, "\"$r\" cannot be used here (allowed: ${p.roots.sorted().joinToString { "@$it" }.ifEmpty { "none" }})")
						r.root == "option" && name !in options -> error(p.path, "\"$r\": no option \"$name\"")
						r.root == "lookup" && name !in lookups -> error(p.path, "\"$r\": no lookup \"$name\"")
						r.root == "identity" && name !in identity -> error(p.path, "\"$r\": \"identity.values\" has no \"$name\"")
						r.root == "field" && name !in fields -> error(p.path, "\"$r\": no entry field \"$name\"")
						r.root == "list" && name !in this.lists -> error(p.path, "\"$r\": no list \"$name\"")
					}
				}
			}
			/* lists form no cycle through their parents */
			for (start in lists.keys) {
				val seen = HashSet<String>()
				var cur: String? = start
				while (cur != null && seen.add(cur))
					cur = lists[cur]?.parent
				if (cur != null)
					error("lists.$start.parent", "the parents form a cycle")
			}
		}
	}

	/* the line of a path, as for policy.json */
	fun lineOf(text: String, path: String): Int? = PolicyParser.lineOf(text, path)
}

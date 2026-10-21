/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.plugin

import kotlinx.serialization.json.JsonPrimitive

/*
 * An integration plugin (issue #48 part B): the parsed and checked form of
 * plugins/<id>.json, the format of docs/PLUGINS.md. Behaviour lives in the
 * app's building blocks; this is data only.
 */
data class Plugin(
	val id: String,
	val name: String,
	val version: String,
	val options: List<PluginOption>,
	val auth: Auth,
	val http: Http,
	val identity: Identity?,
	val lookups: Map<String, Lookup>,
	val lists: Map<String, RemoteList>,
	val imports: Map<ImportTarget, ImportSpec>,
	val entryFields: List<EntryField>,
	val export: ExportSpec?,
	val schedule: Schedule?,
) {
	fun option(key: String): PluginOption? = options.firstOrNull { it.key == key }
}

enum class OptionType(val key: String) {
	STRING("string"), URL("url"), INT("int"), BOOL("bool"), CHOICE("choice"), SECRET("secret");

	companion object {
		val byKey by lazy { entries.associateBy { it.key } }
	}
}

data class PluginOption(
	val key: String,
	val type: OptionType,
	val label: Map<String, String> = emptyMap(),
	val required: Boolean = false,
	val default: JsonPrimitive? = null,
	val min: Int? = null,
	val max: Int? = null,
	/* choice value → label per language */
	val choices: Map<String, Map<String, String>> = emptyMap(),
)

sealed interface Auth {
	data object None : Auth
	data class Basic(val user: Expr, val password: Expr) : Auth
	data class ApiKey(val header: String?, val query: String?, val key: Expr) : Auth
	data class Bearer(val token: Expr) : Auth
	/* public client with PKCE; [issuer] for OpenID discovery, else the two endpoints */
	data class OAuth2Pkce(val issuer: Expr?, val authorizeUrl: Expr?, val tokenUrl: Expr?, val clientId: Expr, val scopes: String) : Auth
}

sealed interface Paging {
	data object None : Paging
	data class OData(val pageSize: Int) : Paging
	data class Page(val pageParam: String, val sizeParam: String, val firstPage: Int, val pageSize: Int) : Paging
	data class Cursor(val cursorParam: String, val next: Expr) : Paging
	data object Link : Paging
}

data class Http(
	val baseUrl: Expr,
	val headers: Map<String, Expr>,
	val timeoutSec: Int,
	val paging: Paging,
)

/* One call; [items] selects the list in a wrapped response, null = the response is the list. */
data class Request(
	val method: String = "GET",
	val path: Expr,
	val query: Map<String, Expr> = emptyMap(),
	val paged: Boolean = false,
	val items: Expr? = null,
)

data class Identity(val request: Request, val values: Map<String, Expr>)

data class Lookup(val request: Request, val select: Expr)

/* A remote list kept for the entry form; the map gives id, name, clientCode, kind, active, order, extra.* */
data class RemoteList(val parent: String?, val sources: List<ListSource>)

data class ListSource(val request: Request, val map: Map<String, Expr>)

enum class ImportTarget(val key: String, val fields: Set<String>) {
	CLIENTS("clients", setOf("name", "code")),
	CONTACTS("contacts", setOf("name", "email", "phone", "clientCode")),
	TODOS("todos", setOf("title", "notes", "refs", "clientCode", "priority", "done", "due", "planStart", "planEnd"));

	companion object {
		val byKey by lazy { entries.associateBy { it.key } }
	}
}

/* [fields] pre-fill the entry fields (§8) of entries started from an imported to-do. */
data class ImportSpec(
	val request: Request,
	val key: Expr,
	val skipNew: Expr?,
	val map: Map<String, Expr>,
	val fields: Map<String, Expr>,
)

enum class FieldType(val key: String) {
	LIST("list"), BOOL("bool"), STRING("string"), INT("int");

	companion object {
		val byKey by lazy { entries.associateBy { it.key } }
	}
}

data class EntryField(
	val key: String,
	val type: FieldType,
	val list: String? = null,
	val parent: Expr? = null,
	val label: Map<String, String> = emptyMap(),
	val required: Expr = Expr.Literal(JsonPrimitive(false)),
	val hidden: Expr = Expr.Literal(JsonPrimitive(false)),
	/* list item property → value it must have */
	val filter: Map<String, Expr> = emptyMap(),
	val default: Expr? = null,
)

enum class GroupBy(val key: String) {
	ENTRY("entry"), DAY("day"), DAY_BLOCK("dayBlock"), SEGMENT("segment");

	companion object {
		val byKey by lazy { entries.associateBy { it.key } }
	}
}

enum class TravelMode(val key: String) {
	NEAREST_BLOCK("nearestBlock"), SEPARATE("separate"), IGNORE("ignore");

	companion object {
		val byKey by lazy { entries.associateBy { it.key } }
	}
}

data class Group(val by: GroupBy, val splitPauseMin: Expr?, val travel: TravelMode, val round: String)

/* A call of the export: [id] selects the remote id in the create response; [add] joins the body of an update. */
data class Call(
	val method: String,
	val path: Expr,
	val id: Expr? = null,
	val fetchFirst: Boolean = false,
	val add: Map<String, Expr> = emptyMap(),
)

data class ExportSpec(
	val group: Group,
	val body: Map<String, Expr>,
	val travelBody: Map<String, Expr>?,
	val create: Call,
	val update: Call?,
	val delete: Call?,
)

data class Schedule(val onCloseMode: Expr?, val onCloseScope: Expr?, val onCloseSync: Expr?, val fetchEveryMin: Expr?)

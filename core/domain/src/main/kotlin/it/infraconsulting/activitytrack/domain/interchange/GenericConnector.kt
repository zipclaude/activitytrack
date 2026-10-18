/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.interchange

import java.io.InputStream
import java.io.OutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/*
 * Generic plugin (M4): the interchange model as JSON or XML files, the
 * format documented in docs/INTERCHANGE.md. It is the default connector,
 * the full backup/transfer format, and the template for specific plugins.
 *
 * XML is derived mechanically from the JSON tree, so the two cannot drift:
 * an object becomes child elements, a list becomes a plural container with
 * one singular element per item, values become text. On reading, text is
 * converted back by the JSON decoder (numbers and booleans included).
 */
object GenericConnector : Connector {
	override val id = "generic"
	override val name = "Generic XML / JSON"
	override val capabilities = setOf(Capability.FILE_IMPORT, Capability.FILE_EXPORT)
	override val fileFormats = listOf(FileFormat.JSON, FileFormat.XML)

	private val json = Json {
		prettyPrint = true
		ignoreUnknownKeys = true
		explicitNulls = false
		encodeDefaults = false
		isLenient = true
	}

	override fun read(format: FileFormat, input: InputStream): Interchange {
		val tree = when (format) {
			FileFormat.JSON -> json.parseToJsonElement(input.bufferedReader(Charsets.UTF_8).readText())
			FileFormat.XML -> xmlToJson(input)
		}
		val data = json.decodeFromJsonElement<Interchange>(tree)
		require(data.format == Interchange.FORMAT) { "not an ActivityTrack interchange file: ${data.format}" }
		require(data.version <= Interchange.VERSION) { "interchange version ${data.version} is newer than this app" }
		return data
	}

	override fun write(format: FileFormat, data: Interchange, output: OutputStream) {
		/* format and version are written even though they have default values */
		val tree = JsonObject(
			mapOf("format" to JsonPrimitive(data.format), "version" to JsonPrimitive(data.version)) +
				(json.encodeToJsonElement(data) as JsonObject),
		)
		when (format) {
			FileFormat.JSON -> output.writer(Charsets.UTF_8).apply { write(json.encodeToString(JsonElement.serializer(), tree)); flush() }
			FileFormat.XML -> jsonToXml(tree, output)
		}
	}

	/* list containers and the element name of their items */
	private val ITEMS = mapOf(
		"companies" to "company", "clients" to "client", "sites" to "site", "roles" to "role",
		"presets" to "preset", "todos" to "todo", "entries" to "entry", "segments" to "segment",
		"expenses" to "expense", "refs" to "ref", "absences" to "absence", "daysOff" to "dayOff",
	)
	private const val ROOT = "activitytrack"

	private fun jsonToXml(tree: JsonElement, output: OutputStream) {
		val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
		val root = doc.createElement(ROOT)
		doc.appendChild(root)
		appendObject(doc, root, tree as JsonObject)
		TransformerFactory.newInstance().newTransformer().apply {
			setOutputProperty(OutputKeys.ENCODING, "UTF-8")
			setOutputProperty(OutputKeys.INDENT, "yes")
			/* Xalan-specific (JVM); Android's transformer rejects unknown keys */
			runCatching { setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2") }
		}.transform(DOMSource(doc), StreamResult(output))
	}

	private fun appendObject(doc: Document, parent: Element, obj: JsonObject) {
		for ((name, value) in obj)
			appendValue(doc, parent, name, value)
	}

	private fun appendValue(doc: Document, parent: Element, name: String, value: JsonElement) {
		when (value) {
			JsonNull -> {}
			is JsonArray -> {
				val container = doc.createElement(name)
				val item = ITEMS[name] ?: error("no item name for list '$name'")
				for (v in value)
					appendValue(doc, container, item, v)
				parent.appendChild(container)
			}
			is JsonObject -> parent.appendChild(doc.createElement(name).also { appendObject(doc, it, value) })
			is JsonPrimitive -> parent.appendChild(doc.createElement(name).also { it.textContent = value.content })
		}
	}

	private fun xmlToJson(input: InputStream): JsonElement {
		val factory = DocumentBuilderFactory.newInstance().apply {
			/*
			 * Untrusted files: no DTD, no external entities. Android's parser
			 * rejects the Xerces feature but never resolves external entities.
			 */
			runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
			isExpandEntityReferences = false
		}
		val root = factory.newDocumentBuilder().parse(input).documentElement
		require(root.tagName == ROOT) { "root element must be <$ROOT>" }
		return elementToJson(root)
	}

	private fun childElements(e: Element): List<Element> =
		(0 until e.childNodes.length).map { e.childNodes.item(it) }.filter { it.nodeType == Node.ELEMENT_NODE }.map { it as Element }

	private fun elementToJson(e: Element): JsonElement {
		val item = ITEMS[e.tagName]
		val children = childElements(e)
		return when {
			item != null -> JsonArray(children.filter { it.tagName == item }.map(::elementToJson))
			children.isEmpty() -> JsonPrimitive(e.textContent.trim())
			else -> JsonObject(children.associate { it.tagName to elementToJson(it) })
		}
	}
}

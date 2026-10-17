/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.interchange

import java.io.InputStream
import java.io.OutputStream

enum class FileFormat(val extension: String, val mimeType: String) {
	JSON("json", "application/json"),
	XML("xml", "application/xml"),
}

/* What a connector can do; the UI offers only these. */
enum class Capability { FILE_IMPORT, FILE_EXPORT, REMOTE_PULL, REMOTE_PUSH }

/*
 * Per-company connector configuration: plain [values] (endpoint, company
 * code in the remote system...) and [secrets] (tokens, passwords) kept
 * encrypted by the app with the Android keystore.
 */
data class ConnectorSettings(
	val values: Map<String, String> = emptyMap(),
	val secrets: Map<String, String> = emptyMap(),
)

/* A field a connector needs in its settings ([secret] ones are encrypted at rest). */
data class SettingField(val key: String, val label: String, val secret: Boolean = false)

class UnsupportedOperation(what: String) : UnsupportedOperationException(what)

/*
 * Plugin towards a management system (M4, owner decision 2026-10-17): one per
 * company, compiled into the app (no dynamic code loading, SPEC §8). File
 * exchange first; remote pull/push for API or web-service systems later.
 */
interface Connector {
	/* stable id stored with the company */
	val id: String
	val name: String
	val capabilities: Set<Capability>
	val settingFields: List<SettingField> get() = emptyList()
	val fileFormats: List<FileFormat> get() = emptyList()

	fun read(format: FileFormat, input: InputStream): Interchange = throw UnsupportedOperation("$id: read")
	fun write(format: FileFormat, data: Interchange, output: OutputStream): Unit = throw UnsupportedOperation("$id: write")

	/* data from the management system (clients, to-dos...) */
	suspend fun pull(settings: ConnectorSettings): Interchange = throw UnsupportedOperation("$id: pull")

	/* entries and done to-dos towards the management system */
	suspend fun push(settings: ConnectorSettings, data: Interchange): Unit = throw UnsupportedOperation("$id: push")
}

/* Every plugin built into the app. */
object Connectors {
	val all: List<Connector> = listOf(GenericConnector)

	fun byId(id: String?): Connector = all.firstOrNull { it.id == id } ?: GenericConnector
}

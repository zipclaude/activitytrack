/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.geo

import java.nio.ByteBuffer
import java.nio.ByteOrder

/*
 * The 127-byte header of a PMTiles v3 archive (github.com/protomaps/PMTiles):
 * what the app needs to accept a map file copied into the data folder by hand,
 * the area it covers and the zooms it holds. The tiles themselves are read by
 * MapLibre; here only the header is read and the file's size checked.
 */
data class PmtilesHeader(
	val dataOffset: Long,
	val dataLength: Long,
	val tileCompression: Int,
	val tileType: Int,
	val minZoom: Int,
	val maxZoom: Int,
	val minLon: Double,
	val minLat: Double,
	val maxLon: Double,
	val maxLat: Double,
) {
	/* null when [b] is valid for the app, else why not */
	fun problem(fileSize: Long): String? = when {
		tileType != VECTOR -> "not a vector tile archive"
		tileCompression != NONE && tileCompression != GZIP -> "unsupported tile compression"
		minZoom > maxZoom || maxZoom > 22 -> "bad zoom range"
		minLat >= maxLat || minLon >= maxLon || minLat < -90 || maxLat > 90 || minLon < -180 || maxLon > 180 -> "bad bounds"
		dataOffset < SIZE || dataLength < 0 || dataOffset + dataLength > fileSize -> "the file is truncated"
		else -> null
	}

	companion object {
		const val SIZE = 127
		const val NONE = 1
		const val GZIP = 2
		const val VECTOR = 1

		/* The header at the start of [b], or null when it is not a PMTiles v3 archive. */
		fun parse(b: ByteArray): PmtilesHeader? {
			if (b.size < SIZE || String(b, 0, 7, Charsets.ISO_8859_1) != "PMTiles" || b[7].toInt() != 3)
				return null
			val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
			fun e7(at: Int) = buf.getInt(at) / 1e7
			return PmtilesHeader(
				dataOffset = buf.getLong(56),
				dataLength = buf.getLong(64),
				tileCompression = b[98].toInt() and 0xFF,
				tileType = b[99].toInt() and 0xFF,
				minZoom = b[100].toInt() and 0xFF,
				maxZoom = b[101].toInt() and 0xFF,
				minLon = e7(102), minLat = e7(106), maxLon = e7(110), maxLat = e7(114),
			)
		}
	}
}

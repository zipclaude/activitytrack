/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.storage

import java.io.InputStream
import java.io.OutputStream

/* A copy that stops past a size limit (a backup's database, security review S2). */
object BoundedCopy {
	/* The bytes copied, or -1 when [input] holds more than [max] (the output then has max bytes or fewer). */
	fun copy(input: InputStream, output: OutputStream, max: Long): Long {
		val buf = ByteArray(64 * 1024)
		var total = 0L
		while (true) {
			val n = input.read(buf)
			if (n < 0)
				return total
			if (total + n > max)
				return -1
			output.write(buf, 0, n)
			total += n
		}
	}
}

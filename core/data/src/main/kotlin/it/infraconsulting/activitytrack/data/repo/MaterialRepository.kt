/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.repo

import it.infraconsulting.activitytrack.data.db.ActivityTrackDatabase
import it.infraconsulting.activitytrack.data.db.EntryMaterialEntity
import it.infraconsulting.activitytrack.data.db.MaterialEntity
import it.infraconsulting.activitytrack.data.repo.ValidationException.Reason
import java.time.Clock

/* Consumables left at clients (issue #41). */
class MaterialRepository(private val db: ActivityTrackDatabase, private val clock: Clock) {
	private val dao = db.materialDao()

	val materials = dao.observeMaterials()

	fun lines(entryId: Long) = dao.observeLines(entryId)

	suspend fun linesOf(entryIds: List<Long>): List<EntryMaterialEntity> = if (entryIds.isEmpty()) emptyList() else dao.linesOf(entryIds)

	/* description required; unit cost not negative */
	suspend fun saveMaterial(m: MaterialEntity): Long {
		val name = m.name.trim().ifEmpty { throw ValidationException(Reason.BLANK_NAME) }
		if ((m.unitCostCents ?: 0) < 0)
			throw ValidationException(Reason.INVALID_AMOUNT)
		val clean = m.copy(name = name, unit = m.unit?.trim()?.ifEmpty { null })
		return if (m.id == 0L) dao.insertMaterial(clean) else m.id.also { dao.updateMaterial(clean) }
	}

	/* the lines already recorded keep their copy */
	suspend fun deleteMaterial(id: Long) = dao.deleteMaterial(id)

	/* description required; quantity positive, unit cost not negative when given */
	suspend fun saveLine(l: EntryMaterialEntity): Long {
		val description = l.description.trim().ifEmpty { throw ValidationException(Reason.BLANK_VALUE) }
		if ((l.quantity?.let { it <= 0 || it.isNaN() } == true) || (l.unitCostCents ?: 0) < 0)
			throw ValidationException(Reason.INVALID_AMOUNT)
		val clean = l.copy(description = description, unit = l.unit?.trim()?.ifEmpty { null })
		return if (l.id == 0L) dao.insertLine(clean.copy(createdUtcMs = clock.millis())) else l.id.also { dao.updateLine(clean) }
	}

	suspend fun deleteLine(id: Long) = dao.deleteLine(id)
}

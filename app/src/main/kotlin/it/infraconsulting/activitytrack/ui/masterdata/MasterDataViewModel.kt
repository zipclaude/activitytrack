/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.ui.masterdata

import kotlinx.coroutines.flow.map
import it.infraconsulting.activitytrack.domain.settings.OrderedList
import it.infraconsulting.activitytrack.domain.settings.ListOrders
import it.infraconsulting.activitytrack.domain.settings.ListOrder
import it.infraconsulting.activitytrack.data.settings.SettingsRepository
import it.infraconsulting.activitytrack.data.db.ActivityPresetEntity
import it.infraconsulting.activitytrack.data.db.ClientEntity
import it.infraconsulting.activitytrack.data.db.ClientSiteEntity
import it.infraconsulting.activitytrack.data.db.CompanyEntity
import it.infraconsulting.activitytrack.data.db.CompanySiteEntity
import it.infraconsulting.activitytrack.data.db.DayOffEntity
import it.infraconsulting.activitytrack.data.db.PlaceEntity
import it.infraconsulting.activitytrack.data.db.RoleEntity
import it.infraconsulting.activitytrack.data.db.WorkScheduleEntity
import it.infraconsulting.activitytrack.data.repo.MasterDataRepository
import it.infraconsulting.activitytrack.data.repo.ValidationException
import it.infraconsulting.activitytrack.ui.ActionViewModel
import it.infraconsulting.activitytrack.ui.common.parseDate
import kotlinx.coroutines.flow.StateFlow

/*
 * Shared by the master-data screens: they are small CRUD lists over the same
 * repository, so one ViewModel per feature instead of one per screen.
 */
class MasterDataViewModel(private val repo: MasterDataRepository, private val settings: SettingsRepository) : ActionViewModel() {
	/* list orders (M16) */
	val orders = settings.settings.map { it.listOrders }.state(ListOrders())

	fun setOrder(list: OrderedList, order: ListOrder) = run { settings.update { it.copy(listOrders = it.listOrders.with(list, order)) } }
	fun reorder(list: OrderedList, ids: List<Long>) = run { repo.reorder(list, ids) }

	val companies = repo.companies.state(emptyList())
	val clients = repo.clients.state(emptyList())
	val roles = repo.roles.state(emptyList())
	val tags = repo.tags.state(emptyList())
	val presets = repo.presets.state(emptyList())
	val places = repo.places.state(emptyList())
	val schedule = repo.schedule.state(emptyList())
	val lunchBreaks = repo.lunchBreaks.state(emptyList())
	val daysOff = repo.daysOff.state(emptyList())

	private val companySiteFlows = HashMap<Long, StateFlow<List<CompanySiteEntity>>>()
	private val clientSiteFlows = HashMap<Long, StateFlow<List<ClientSiteEntity>>>()

	fun companySites(companyId: Long) =
		companySiteFlows.getOrPut(companyId) { repo.companySites(companyId).state(emptyList()) }

	fun clientSites(clientId: Long) =
		clientSiteFlows.getOrPut(clientId) { repo.clientSites(clientId).state(emptyList()) }

	fun save(c: CompanyEntity, done: () -> Unit) = run(done) { repo.saveCompany(c) }
	fun delete(c: CompanyEntity, done: () -> Unit) = run(done) { repo.deleteCompany(c) }
	fun save(s: CompanySiteEntity, done: () -> Unit) = run(done) { repo.saveCompanySite(s) }
	fun delete(s: CompanySiteEntity, done: () -> Unit) = run(done) { repo.deleteCompanySite(s) }
	fun save(c: ClientEntity, done: () -> Unit) = run(done) { repo.saveClient(c) }
	fun delete(c: ClientEntity, done: () -> Unit) = run(done) { repo.deleteClient(c) }
	fun save(s: ClientSiteEntity, done: () -> Unit) = run(done) { repo.saveClientSite(s) }
	fun delete(s: ClientSiteEntity, done: () -> Unit) = run(done) { repo.deleteClientSite(s) }
	fun save(r: RoleEntity, done: () -> Unit) = run(done) { repo.saveRole(r) }
	fun delete(r: RoleEntity, done: () -> Unit) = run(done) { repo.deleteRole(r) }
	fun save(t: it.infraconsulting.activitytrack.data.db.TagEntity, done: () -> Unit) = run(done) { repo.saveTag(t) }
	fun delete(t: it.infraconsulting.activitytrack.data.db.TagEntity, done: () -> Unit) = run(done) { repo.deleteTag(t) }
	fun save(p: ActivityPresetEntity, done: () -> Unit) = run(done) { repo.savePreset(p) }
	fun delete(p: ActivityPresetEntity, done: () -> Unit) = run(done) { repo.deletePreset(p) }
	fun save(p: PlaceEntity, done: () -> Unit) = run(done) { repo.savePlace(p) }
	fun delete(p: PlaceEntity, done: () -> Unit) = run(done) { repo.deletePlace(p) }
	fun save(s: WorkScheduleEntity, done: () -> Unit) = run(done) { repo.saveSchedule(s) }
	fun delete(s: WorkScheduleEntity, done: () -> Unit) = run(done) { repo.deleteSchedule(s) }
	fun setLunch(day: Int, start: Int, end: Int, done: () -> Unit) = run(done) { repo.setLunchBreak(day, start, end) }
	fun clearLunch(day: Int, done: () -> Unit) = run(done) { repo.clearLunchBreak(day) }
	fun saveDayOff(date: String, note: String, done: () -> Unit) = run(done) {
		repo.saveDayOff(parseDate(date) ?: throw ValidationException(ValidationException.Reason.INVALID_DATE), note)
	}
	fun delete(d: DayOffEntity, done: () -> Unit) = run(done) { repo.deleteDayOff(d) }
}

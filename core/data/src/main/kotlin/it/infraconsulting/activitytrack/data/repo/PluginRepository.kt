/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.data.repo

import it.infraconsulting.activitytrack.data.db.ActivityTrackDatabase
import it.infraconsulting.activitytrack.data.db.PluginDao

/* The plugins' data (issue #48): remote lists and the records their imports match by external code. */
class PluginRepository(db: ActivityTrackDatabase) : PluginDao by db.pluginDao()

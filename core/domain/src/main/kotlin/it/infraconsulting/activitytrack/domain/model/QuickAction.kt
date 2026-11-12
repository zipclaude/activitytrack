/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.model

/*
 * The buttons of a notification, a widget or a swipe on a running activity.
 * EntryRepository.quick maps them onto the commands of the activity model
 * (domain.activity.ActivityCommand) from the activity's state.
 */
enum class QuickAction {
	WORK,		/* start, resume, switch or reopen in WORK */
	TRAVEL,		/* start, resume, switch or reopen in TRAVEL */
	PAUSE,		/* switch to PAUSE */
	STOP,		/* close the activity */
}

/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.domain.model

/*
 * The buttons of the 0.9 screens (work, travel, pause, suspend, stop,
 * reopen), mapped by EntryRepository.apply onto the commands of the activity
 * model 1.0 (domain.activity.ActivityCommand). TODO(activity-1.0-ui): the
 * new screens call the repository's activity commands; remove this then.
 */
enum class EntryCommand {
	WORK,		/* switch, resume or reopen in WORK */
	TRAVEL,		/* switch, resume or reopen in TRAVEL */
	PAUSE,		/* switch to PAUSE */
	SUSPEND,	/* close the open segment, the activity keeps running */
	STOP,		/* close the activity */
	REOPEN,		/* a closed activity runs again (in WORK) */
}

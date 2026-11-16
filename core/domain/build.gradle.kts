/* SPDX-License-Identifier: GPL-3.0-or-later */
/* SPDX-FileCopyrightText: 2026 Flavio Visentin <flavio-activitytrack@infraconsulting.it> */
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * Pure Kotlin/JVM module: business rules only, no Android dependencies.
 * Unit tests run on the host JVM with `./gradlew :core:domain:test`.
 */
plugins {
	alias(libs.plugins.kotlin.jvm)
	alias(libs.plugins.kotlin.serialization)
}

java {
	sourceCompatibility = JavaVersion.VERSION_17
	targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
	compilerOptions {
		jvmTarget = JvmTarget.JVM_17
	}
}

dependencies {
	/*
	 * interchange JSON (M4); multiplatform library, no Android dependency.
	 * api: the plugin engine (issue #48) has JsonElement in its public types.
	 */
	api(libs.kotlinx.serialization.json)
	testImplementation(libs.kotlin.test.junit)
	testImplementation(libs.junit4)
}

/*
 * tests read the published plugins and the docs (issue #48), and the app's resources and sources
 * (StringResourcesTest, issue #196): changes there run them again
 */
tasks.test {
	inputs.dir(rootProject.file("plugins"))
	inputs.dir(rootProject.file("docs"))
	inputs.dir(rootProject.file("app/src"))
	inputs.dir(rootProject.file("core/data/src"))
}

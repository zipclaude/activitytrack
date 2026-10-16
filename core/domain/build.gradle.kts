import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * Pure Kotlin/JVM module: business rules only, no Android dependencies.
 * Unit tests run on the host JVM with `./gradlew :core:domain:test`.
 */
plugins {
	alias(libs.plugins.kotlin.jvm)
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
	testImplementation(libs.kotlin.test.junit)
	testImplementation(libs.junit4)
}

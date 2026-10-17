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
	/* interchange JSON (M4); multiplatform library, no Android dependency */
	implementation(libs.kotlinx.serialization.json)
	testImplementation(libs.kotlin.test.junit)
	testImplementation(libs.junit4)
}

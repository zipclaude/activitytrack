plugins {
	alias(libs.plugins.android.library)
	alias(libs.plugins.ksp)
	alias(libs.plugins.room)
}

android {
	namespace = "it.infraconsulting.activitytrack.core.data"
	compileSdk = 36

	defaultConfig {
		minSdk = 33
		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}
}

room {
	schemaDirectory("$projectDir/schemas")
}

dependencies {
	api(project(":core:domain"))
	implementation(libs.androidx.room.runtime)
	implementation(libs.androidx.room.ktx)
	ksp(libs.androidx.room.compiler)

	testImplementation(libs.kotlin.test.junit)
}

plugins {
	alias(libs.plugins.android.library)
	alias(libs.plugins.ksp)
	alias(libs.plugins.room)
}

android {
	namespace = "it.infraconsulting.activitytrack.core.data"
	compileSdk = 37

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
	implementation(libs.androidx.datastore.preferences)
	ksp(libs.androidx.room.compiler)

	testImplementation(libs.kotlin.test.junit)
	androidTestImplementation(libs.kotlin.test.junit)
	androidTestImplementation(libs.androidx.room.testing)
	androidTestImplementation(libs.androidx.test.runner)
	androidTestImplementation(libs.androidx.test.ext.junit)
}

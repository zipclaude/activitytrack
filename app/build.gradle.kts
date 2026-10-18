import java.util.Properties

plugins {
	alias(libs.plugins.android.application)
	alias(libs.plugins.kotlin.compose)
}

/*
 * Release signing (owner, 2026-10-18): one fixed key, so a release from
 * GitHub updates the installed one. CI passes it in RELEASE_* variables
 * (repository secrets); locally a properties file outside the repository
 * (-Pactivitytrack.signing=<file>, default ~/src/activitytrack-keys/release.properties).
 * Without either, the release is built unsigned.
 */
val releaseSigning: Map<String, String>? = run {
	System.getenv("RELEASE_STORE_FILE")?.let { file ->
		return@run mapOf(
			"storeFile" to file,
			"storePassword" to System.getenv("RELEASE_STORE_PASSWORD").orEmpty(),
			"keyAlias" to System.getenv("RELEASE_KEY_ALIAS").orEmpty(),
			"keyPassword" to System.getenv("RELEASE_KEY_PASSWORD").orEmpty(),
		)
	}
	val path = (findProperty("activitytrack.signing") as String?)
		?: "${System.getProperty("user.home")}/src/activitytrack-keys/release.properties"
	file(path).takeIf { it.isFile }?.let { f ->
		Properties().apply { f.inputStream().use(::load) }.let { p -> p.stringPropertyNames().associateWith(p::getProperty) }
	}
}

android {
	namespace = "it.infraconsulting.activitytrack"
	compileSdk = 37

	defaultConfig {
		applicationId = "it.infraconsulting.activitytrack"
		minSdk = 33
		targetSdk = 36
		/* CI passes the tag (v0.2.0 -> 0.2.0) and the run number */
		versionCode = (findProperty("activitytrack.versionCode") as String?)?.toInt() ?: 1
		versionName = (findProperty("activitytrack.versionName") as String?) ?: "0.1.0"
		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
		/* personal APK for the owner's phone: native libraries (MapLibre) only for arm64 */
		ndk {
			abiFilters += "arm64-v8a"
		}
	}

	signingConfigs {
		releaseSigning?.let { s ->
			create("release") {
				storeFile = file(s.getValue("storeFile"))
				storePassword = s.getValue("storePassword")
				keyAlias = s.getValue("keyAlias")
				keyPassword = s.getValue("keyPassword")
			}
		}
	}

	buildTypes {
		/* adb installs for device tests: "ActivityTrackDbg", its own package and data folder */
		debug {
			applicationIdSuffix = ".debug"
			versionNameSuffix = "-debug"
		}
		/* the app in use, from GitHub releases; R8 off until a minified build is device-tested */
		release {
			isMinifyEnabled = false
			isShrinkResources = false
			proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
			signingConfig = signingConfigs.findByName("release")
		}
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}

	buildFeatures {
		compose = true
	}

	packaging {
		resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
	}
}

dependencies {
	implementation(project(":core:domain"))
	implementation(project(":core:data"))

	implementation(libs.androidx.core.ktx)
	implementation(libs.androidx.activity.compose)
	implementation(libs.androidx.lifecycle.runtime.compose)
	implementation(libs.androidx.lifecycle.viewmodel.compose)
	implementation(platform(libs.androidx.compose.bom))
	implementation(libs.androidx.compose.ui)
	implementation(libs.androidx.compose.ui.tooling.preview)
	implementation(libs.androidx.compose.material3)
	implementation(libs.androidx.work.runtime.ktx)
	implementation(libs.androidx.datastore.preferences)
	implementation(libs.maplibre.android)
	implementation(libs.androidx.glance.appwidget)
	implementation(libs.androidx.glance.material3)
	debugImplementation(libs.androidx.compose.ui.tooling)

	testImplementation(libs.kotlin.test.junit)
}

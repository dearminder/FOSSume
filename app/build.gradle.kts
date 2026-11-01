plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.dearminder.fossume"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.dearminder.fossume"
        minSdk = 26
        targetSdk = 36
        // Set by the release workflow (.github/workflows/release.yml) from the tag or the commit count.
        versionCode = (findProperty("appVersionCode") as String?)?.toInt() ?: 1
        versionName = findProperty("appVersionName") as String? ?: "dev"
    }

    androidResources {
        // Optimize resources - only bundle English and Polish
        localeFilters += listOf("en", "pl")
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        // Provided by the release workflow (.github/workflows/release.yml) - without them the release APK is unsigned.
        System.getenv("SIGNING_KEYSTORE_FILE")?.let { keystore ->
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
        }
    }
}

// Print each test's result - by default Gradle shows nothing for passing tests, so a CI log
// is just "Starting a Gradle Daemon" and "BUILD SUCCESSFUL".
tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    // No other libraries: the two screens are switched without Navigation, settings use
    // org.json, and media control goes straight through the framework's MediaSession API.

    // The gestures, the settings format and the player rules have no Android dependencies, so
    // they're tested on a plain JVM - with the real org.json, as android.jar only has stubs of it.
    testImplementation(libs.junit)
    testImplementation(libs.json)
}

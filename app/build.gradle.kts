plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.jetbrains.kotlin.compose)
}

/**
 * intervals.icu API key, read from the git-ignored `.env` at the repo root
 * (`INTERVALS_ICU_API_KEY=...`). Only debug and `personal` builds get it, so a release APK that
 * might be shared never contains the key. Empty → intervals.icu sync is disabled.
 */
val intervalsApiKey: String = rootProject.file(".env").takeIf { it.exists() }
    ?.readLines()
    ?.map { it.trim().removePrefix("export ").trim() }
    ?.firstOrNull { it.startsWith("INTERVALS_ICU_API_KEY=") }
    ?.substringAfter("=")?.trim()?.trim('"', '\'')
    ?: ""

android {
    namespace = "com.karoo_rpe"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.karoo_rpe"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    buildTypes {
        debug {
            buildConfigField("String", "INTERVALS_API_KEY", "\"$intervalsApiKey\"")
        }
        release {
            buildConfigField("String", "INTERVALS_API_KEY", "\"\"")
            isMinifyEnabled = false
            // Signed with the debug keystore so the release APK is directly installable
            // (same approach as other community Karoo extensions).
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // Release build for your own Karoo: not debuggable (no simulator), but with the API key
        // from .env built in so it works without on-device setup. Never share this APK.
        create("personal") {
            initWith(getByName("release"))
            buildConfigField("String", "INTERVALS_API_KEY", "\"$intervalsApiKey\"")
            matchingFallbacks += "release"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
        getByName("test") {
            java.srcDirs("src/test/kotlin")
        }
    }

    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "karoo_rpe-${defaultConfig.versionName}-${buildType.name}.apk"
        }
    }
}

dependencies {
    implementation(libs.hammerhead.karoo.ext)
    implementation(libs.androidx.core.ktx)

    // Settings UI (Compose) + persistence (DataStore)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

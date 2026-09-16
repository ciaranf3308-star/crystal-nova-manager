plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.crystalnova.manager"
    compileSdk = 34

    buildFeatures {
        // Needed for BuildConfig.VERSION_NAME (the self-updater compares
        // the installed version against the latest GitHub release).
        buildConfig = true
    }

    defaultConfig {
        applicationId = "io.crystalnova.manager"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "1.2.2-u2"

        // Retroid Pocket Nova ships Android 13; minSdk 26 keeps SAF
        // (persistable tree permissions) working on older handhelds too.
    }

    signingConfigs {
        create("release") {
            // CI decodes the ANDROID_KEYSTORE_BASE64 secret into the path
            // named by CNM_KEYSTORE_PATH. Absent locally, the release
            // build stays unsigned (debug builds are unaffected) — the
            // persistent key only ever lives in GitHub Secrets.
            System.getenv("CNM_KEYSTORE_PATH")?.let { path ->
                storeFile = file(path)
                storePassword = System.getenv("CNM_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CNM_KEY_ALIAS")
                keyPassword = System.getenv("CNM_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            // Kotlin Multiplatform compiler metadata shipped inside the
            // Android AARs of our KMP-published dependencies (Compose UI,
            // coroutines, androidx.core). When several of those AARs carry
            // the same metadata paths, AGP fails the APK merge with
            // "duplicate files". This metadata is never loaded at runtime,
            // so excluding it is safe; entries that match nothing are no-ops.
            excludes += "nonJvmMain/**/linkdata/**"
            excludes += "**/commonMain/**/manifest"
            excludes += "**/linkdata/**"
            excludes += "nonJvmMain/default/manifest"
            excludes += "commonMain/default/manifest"
            excludes += "**/default/manifest"
            excludes += "META-INF/kotlin-project-structure-metadata.json"
            excludes += "**/*.knm"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // collection-ktx is superseded by collection/collection-jvm; exclude the
    // old artifact to avoid duplicate classes with the BOM's collection 1.4.0.
    configurations.all {
        exclude(group = "androidx.collection", module = "collection-ktx")
    }

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // android.jar's org.json is stubbed ("Stub!"); unit tests need the real
    // implementation. Test-scoped only — on device the framework provides it.
    testImplementation("org.json:json:20240303")
}

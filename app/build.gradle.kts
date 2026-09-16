plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.crystalnova.manager"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.crystalnova.manager"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.1-u1"

        // Retroid Pocket Nova ships Android 13; minSdk 26 keeps SAF
        // (persistable tree permissions) working on older handhelds too.
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
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

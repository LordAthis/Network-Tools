// Verzio: v0.4.0 - 2026-09-21
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// EGYETLEN kozponti verzio-forras (ebbol lesz a BuildConfig.VERSION_NAME is, azt olvassa a
// fejlec/Nevjegy/naplok). A versionCode a verziobol szamolodik (0.1.5555 -> 15555), igy minden
// magasabb verzio automatikusan frissiteskent telepitheto a regire (in-place update).
val appVersionName = "0.1.0"
val appVersionParts = appVersionName.split(".").map { it.toInt() }
val appVersionCode = appVersionParts[0] * 100_000_000 + appVersionParts[1] * 10_000 + appVersionParts[2]

android {
    namespace = "hu.lordathis.networktools"
    compileSdk = 34

    defaultConfig {
        applicationId = "hu.lordathis.networktools"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
    }

    // Stabil, a repoban tarolt DEBUG alairo kulcs: enelkul a GitHub runner minden futasnal uj debug
    // kulcsot generalna, es az uj APK nem tudna "rafrissiteni" a telepitettet (alairas-utkozes).
    // Ez NEM titok (debug kulcs); release kulcsot kesobb GitHub Secretsben tartunk.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

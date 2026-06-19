import org.gradle.api.tasks.Copy
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val wearLeafletAssetsDir = layout.buildDirectory.dir("generated/assets/wearLeaflet").get().asFile
val syncWearLeafletAssets by tasks.registering(Copy::class) {
    from(rootProject.file("app/src/main/assets")) {
        include("leaflet.css", "leaflet.js")
        include("images/**")
    }
    into(wearLeafletAssetsDir)
}

android {
    namespace = "com.safepulse.wear"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.safepulse"
        minSdk = 30 // Required by health-services-client
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Load API key from local.properties
        val properties = Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            properties.load(localPropertiesFile.inputStream())
        }
        val mapsApiKey = properties.getProperty("MAPS_API_KEY", "")
        buildConfigField("String", "MAPS_API_KEY", "\"$mapsApiKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(wearLeafletAssetsDir)
        }
    }
}

tasks.named("preBuild") {
    dependsOn(syncWearLeafletAssets)
}

dependencies {
    // Wear OS
    implementation("com.google.android.gms:play-services-wearable:18.1.0")
    implementation("androidx.wear:wear:1.3.0")

    // Compose for Wear OS
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.wear.compose:compose-material:1.3.0")
    implementation("androidx.wear.compose:compose-foundation:1.3.0")
    implementation("androidx.wear.compose:compose-navigation:1.3.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")

    // Wear OS Tiles & Complications
    implementation("androidx.wear.tiles:tiles:1.3.0")
    implementation("androidx.wear.tiles:tiles-material:1.3.0")
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.2.1")

    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")

    // DataStore for preferences
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Location
    implementation("com.google.android.gms:play-services-location:21.1.0")

    // Health Services for heart rate
    implementation("androidx.health:health-services-client:1.0.0-rc02")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.7.3")

    // JSON
    implementation("com.google.code.gson:gson:2.10.1")

    // Vibration / haptics
    implementation("androidx.core:core-ktx:1.12.0")

    // Debugging
    debugImplementation("androidx.compose.ui:ui-tooling")
}

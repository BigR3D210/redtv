import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePropsFile = rootProject.file("keystore.properties")
val hasKeystore = keystorePropsFile.exists()
val keystoreProps = Properties().apply {
    if (hasKeystore) FileInputStream(keystorePropsFile).use { load(it) }
}

// Build number is injected by CI (-PbuildNumber=<run>) so the in-app updater can compare versions.
val ciBuildNumber = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 0

android {
    namespace = "com.redtv.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.redtv.app"
        minSdk = 21
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"

        buildConfigField("int", "BUILD_NUMBER", "$ciBuildNumber")
        buildConfigField("String", "GH_OWNER", "\"BigR3D210\"")
        buildConfigField("String", "GH_REPO", "\"redtv\"")
    }

    signingConfigs {
        // Fixed debug key committed to the repo so every CI build shares one signature
        // (lets adb -r and the in-app updater upgrade in place).
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (hasKeystore) {
            create("release") {
                storeFile = file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.leanback:leanback:1.0.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-ktx:1.9.1")

    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("io.coil-kt:coil:2.7.0")

    // Tiny embedded web server for the "edit from laptop" pairing screen
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // QR code generation for pairing
    implementation("com.google.zxing:core:3.5.3")
}

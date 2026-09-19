import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// Git utilities for version info
fun String.runCommand(): String {
    return try {
        val parts = this.split(" ")
        val process = ProcessBuilder(parts)
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().readText().trim()
    } catch (e: Exception) {
        ""
    }
}

val appVersionCode = 55
val appVersionName = "1.0.15"
val commitHash = "git rev-parse --short=8 HEAD".runCommand().ifEmpty { "unknown" }
val fullVersionName = "v${appVersionName}(${appVersionCode})(commit:${commitHash})"

// Release signing credentials: CI injects env vars; local builds read the
// gitignored keystore.properties next to the keystore. No plaintext fallback —
// the old fallback password is permanently public in this repo's history.
val keystoreProps = Properties().apply {
    file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "cn.net.rms.chatroom"
    compileSdk = 35

    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: keystoreProps.getProperty("storePassword")
            keyAlias = System.getenv("KEY_ALIAS") ?: keystoreProps.getProperty("keyAlias")
            keyPassword = System.getenv("KEY_PASSWORD") ?: keystoreProps.getProperty("keyPassword")
        }
    }

    defaultConfig {
        applicationId = "cn.net.rms.chatroom"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = fullVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Ship only arm64 native libs. The WebRTC .so from LiveKit is ~12 MB per
        // ABI; x86/x86_64 only served emulators and were bloating the APK.
        // Debug builds additionally pack x86_64 for the local emulator (pure
        // x86_64, no ARM translation layer).
        ndk {
            abiFilters += "arm64-v8a"
            if (gradle.startParameter.taskNames.any { it.contains("Debug", ignoreCase = true) }) {
                abiFilters += "x86_64"
            }
        }
    }

    // Compress native libs inside the APK (~5 MB smaller download at the
    // cost of an extra extracted copy on device at install time).
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            // protobuf-javalite ships schema sources it never reads at runtime
            excludes += setOf(
                "google/protobuf/*.proto",
                "kotlin-tooling-metadata.json",
            )
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "API_BASE_URL", "\"https://chatroom.rms.net.cn\"")
            buildConfigField("String", "WS_BASE_URL", "\"wss://chatroom.rms.net.cn\"")
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "API_BASE_URL", "\"https://chatroom.rms.net.cn\"")
            buildConfigField("String", "WS_BASE_URL", "\"wss://chatroom.rms.net.cn\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
                "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
                "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        disable += "NullSafeMutableLiveData"
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    // Browser (Custom Tabs for SSO)
    implementation(libs.androidx.browser)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Network
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // DataStore
    implementation(libs.datastore.preferences)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // LiveKit. NOTE: jain-sip (android.gov.nist.*) and klaxon (com.beust.*)
    // look like dead weight kept by LiveKit's consumer rules, but they are NOT
    // removable: Participant.updateFromInfo() unconditionally parses
    // AgentAttributes via klaxon, and the peer-connection SDP munging path
    // (PeerConnectionTransport.createAndSendOffer) requires the jain-sdp
    // stack. Both are on the mandatory voice-call path.
    implementation(libs.livekit.android)

    // Image loading
    implementation(libs.coil.compose)

    // Splash screen
    implementation(libs.splashscreen)

    // Colorful Sliders
    implementation(libs.colorful.sliders)

    // Telephoto (zoomable image preview)
    implementation(libs.telephoto.zoomable.image.coil)

    // Media3 (video preview)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    // Markdown (chat message rendering, GFM parity with the web client)
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.autolink)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.task.list.items)

    // Testing - Unit tests
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)

    // Testing - Instrumented tests
    androidTestImplementation(libs.junit.ext)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.compose.ui.test)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.hilt.testing)
    kspAndroidTest(libs.hilt.compiler)
    debugImplementation(libs.compose.ui.test.manifest)
}

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.sportscc.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sportscc.tv"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"

        // GitHub Pages address of the phone website in web/ (owner.github.io/repo/).
        val repo = (System.getenv("GITHUB_REPOSITORY") ?: "brian-comerford/Google-TV-Command-Center").split("/")
        buildConfigField("String", "REMOTE_URL", "\"https://${repo[0].lowercase()}.github.io/${repo[1]}/\"")
        // Always points at the newest build (see .github/workflows/build-apk.yml).
        buildConfigField(
            "String", "APK_URL",
            "\"https://github.com/${repo[0]}/${repo[1]}/releases/latest/download/SportsCommandCenter.apk\"",
        )
    }

    signingConfigs {
        // A fixed key checked into the repo so every build can be installed over the previous one.
        // It only protects a personal sideload, not a store listing; don't reuse it for anything else.
        create("sideload") {
            storeFile = file("sideload.jks")
            storePassword = "sportscc"
            keyAlias = "sideload"
            keyPassword = "sportscc"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("sideload")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.google.zxing:core:3.5.3")

    // Built-in player for stream links and playlist channels (HLS .m3u8, DASH, MP4, MPEG-TS).
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")
}

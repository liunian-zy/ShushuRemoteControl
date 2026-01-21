plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.shushu.remote"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.shushu.remote"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.1"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    val releaseServerUrl = "wss://rc.photo.sqaigc.com/ws/device"
    val debugServerUrl = (project.findProperty("DEBUG_SERVER_URL") as String?)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: releaseServerUrl

    // 系统签名配置
    signingConfigs {
        create("platform") {
            storeFile = file("platform.jks")
            storePassword = "android"
            keyAlias = "platform"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("platform")
            buildConfigField("String", "DEFAULT_SERVER_URL", "\"$debugServerUrl\"")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("platform")
            buildConfigField("String", "DEFAULT_SERVER_URL", "\"$releaseServerUrl\"")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}

dependencies {
    // 定昌主板 API
    implementation(files("libs/ZtlApi.jar"))

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // OkHttp for WebSocket
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Gson for JSON
    implementation("com.google.code.gson:gson:2.10.1")

    // WebRTC
    implementation("io.getstream:stream-webrtc-android:1.1.1")
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
    id("com.google.firebase.firebase-perf")
}

android {
    namespace = "com.tgwrist.app"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        buildConfigField("int", "TG_API_ID", "23575686")
        buildConfigField("String", "TG_API_HASH", "\"cacabd4ddbac435f1ee1da64ada4966e\"")
        applicationId = "com.tgwrist.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 26
        versionName = "3.7"
    }

    signingConfigs {
        create("release") {
            keyAlias = System.getenv("ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
            storeFile = file(System.getenv("KEYSTORE_PATH") ?: "keystore.jks")
            storePassword = System.getenv("KEY_STORE_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (System.getenv("ALIAS") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    useLibrary("wear-sdk")
    buildFeatures {
        buildConfig = true
        compose = true
    }
    sourceSets {
        getByName("main") {
            java.directories.add("jni/third_party/webrtc/rtc_base/java/src")
            java.directories.add("jni/third_party/webrtc/modules/audio_device/android/java/src")
            java.directories.add("jni/third_party/webrtc/sdk/android/api")
            java.directories.add("jni/third_party/webrtc/sdk/android/src/java")
            java.directories.add("thirdparty/WebRTC/src/java")

            for (extension in arrayOf(
                "decoder_ffmpeg",
                "decoder_flac",
                "decoder_opus",
                "decoder_vp9"
            )) {
                java.directories.add("thirdparty/androidx-media/libraries/$extension/src/main/java")
            }
        }
    }
}

dependencies {
    implementation(project(":tdlib"))
    implementation(platform(libs.firebase.bom))
    implementation(libs.androidx.ui.text)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.perf)
    implementation(libs.play.services.wearable)
    implementation(platform(libs.compose.bom))
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling)
    implementation(libs.wear.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.core.splashscreen)
    implementation(libs.tiles)
    implementation(libs.protolayout)
    implementation(libs.protolayout.material3)
    implementation(libs.material.icons.extended)
    //implementation(libs.icons.material.icons.filled.android)
    //implementation(libs.icons.material.icons.outlined.android)
    //implementation(libs.icons.material.icons.rounded.android)
    //implementation(libs.icons.material.icons.sharp.android)
    //implementation(libs.icons.material.icons.twotone.android)
    implementation(libs.guava)
    implementation(libs.tiles.tooling.preview)
    implementation(libs.watchface.complications.data.source.ktx)
    implementation(libs.compose.navigation)
    implementation(libs.gson)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.horologist)
    implementation(libs.androidx.wear.remote.interactions)
    implementation(libs.androidx.ongoing)
    //implementation(libs.zoomable)
    implementation(libs.zoomimage.compose.coil2)
    implementation(libs.accompanist.drawablepainter)
    implementation(libs.androidx.security.crypto)
    implementation(libs.firebase.messaging)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.androidx.graphics.shapes)
    implementation(libs.androidx.media3.common.ktx)
    implementation(libs.lottie.compose)
    implementation(libs.relinker)
    implementation(libs.checker.qual)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
    debugImplementation(libs.ui.tooling)
    debugImplementation(libs.ui.test.manifest)
    debugImplementation(libs.tiles.renderer)
    debugImplementation(libs.tiles.tooling)
}

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.views1"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.views1"
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName =
            "2026.8.23-try-vlc-7.52"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(files("libs/lib-decoder-ffmpeg-release-media3-1.11.0.aar"))
    // Source: https://mvnrepository.com/artifact/org.videolan.android/libvlc-all
    implementation("org.videolan.android:libvlc-all:3.7.5")
}

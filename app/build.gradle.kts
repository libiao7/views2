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
        versionCode = 2
        versionName =
            "2026.8.25-try-vlc-sub-LANDSCAPE-3.39"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    splits {

        // Configures multiple APKs based on ABI.
        abi {

            // Enables building multiple APKs per ABI.
            isEnable = true

            // By default all ABIs are included, so use reset() and include to specify that you only
            // want APKs for x86 and x86_64.

            // Resets the list of ABIs for Gradle to create APKs for to none.
            reset()

            // Specifies a list of ABIs for Gradle to create APKs for.
            include("arm64-v8a")

            // Specifies that you don't want to also generate a universal APK that includes all ABIs.
            isUniversalApk = false
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        getByName("debug") {
            applicationIdSuffix = ".debug"
            isDebuggable = true
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

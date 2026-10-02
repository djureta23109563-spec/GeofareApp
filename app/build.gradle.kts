plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

apply(plugin = "com.google.gms.google-services")

android {
    namespace = "com.example.geofare"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.geofare"

        minSdk = 24
        targetSdk = 37

        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }
}

dependencies {

    // =====================================================
    // ANDROID / COMPOSE
    // =====================================================

    implementation(
        platform(
            libs.androidx.compose.bom
        )
    )

    implementation(
        libs.androidx.activity.compose
    )

    implementation(
        libs.androidx.compose.material3
    )

    implementation(
        libs.androidx.compose.ui
    )

    implementation(
        libs.androidx.compose.ui.graphics
    )

    implementation(
        libs.androidx.compose.ui.tooling.preview
    )

    implementation(
        libs.androidx.core.ktx
    )

    implementation(
        libs.androidx.lifecycle.runtime.ktx
    )

    implementation(
        "androidx.lifecycle:lifecycle-runtime-compose:2.11.0"
    )


    // =====================================================
    // CAMERA
    // =====================================================

    implementation(
        "androidx.camera:camera-camera2:1.6.2"
    )

    implementation(
        "androidx.camera:camera-lifecycle:1.6.2"
    )

    implementation(
        "androidx.camera:camera-view:1.6.2"
    )


    // =====================================================
    // ML KIT OCR
    // =====================================================

    implementation(
        "com.google.mlkit:text-recognition:16.0.1"
    )


    // =====================================================
    // LOCATION
    // =====================================================

    implementation(
        "com.google.android.gms:play-services-location:21.4.0"
    )


    // =====================================================
    // OPENSTREETMAP
    // =====================================================

    implementation(
        "org.osmdroid:osmdroid-android:6.1.20"
    )


    // =====================================================
    // QR GENERATION
    // =====================================================

    implementation(
        "com.google.zxing:core:3.5.4"
    )


    // =====================================================
    // FIREBASE
    // =====================================================

    implementation(
        platform(
            "com.google.firebase:firebase-bom:34.19.0"
        )
    )

    implementation(
        "com.google.firebase:firebase-auth"
    )

    implementation(
        "com.google.firebase:firebase-firestore"
    )


    // =====================================================
    // TESTING
    // =====================================================

    testImplementation(
        libs.junit
    )

    androidTestImplementation(
        platform(
            libs.androidx.compose.bom
        )
    )

    androidTestImplementation(
        libs.androidx.compose.ui.test.junit4
    )

    androidTestImplementation(
        libs.androidx.espresso.core
    )

    androidTestImplementation(
        libs.androidx.junit
    )

    debugImplementation(
        libs.androidx.compose.ui.test.manifest
    )

    debugImplementation(
        libs.androidx.compose.ui.tooling
    )
}
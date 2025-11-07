plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "kit.developers.kitar"
    compileSdk = 36

    defaultConfig {
        applicationId = "kit.developers.kitar"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

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

    implementation ("androidx.appcompat:appcompat:1.6.1")
    implementation ("com.google.android.material:material:1.10.0")
    implementation ("androidx.constraintlayout:constraintlayout:2.1.4")


    implementation ("androidx.camera:camera-core:1.5.1")
    implementation ("androidx.camera:camera-camera2:1.5.1")
    implementation ("androidx.camera:camera-lifecycle:1.5.1")
    implementation ("androidx.camera:camera-view:1.5.1")

    // ML Kit для QR сканирования
    implementation ("com.google.mlkit:barcode-scanning:17.2.0")

    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta4")


    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.assets)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
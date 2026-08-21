plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.smartphonekey.photoframe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.smartphonekey.photoframe"
        // Android 6.0 — the oldest hardware we support (old photo frames).
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // Ship only the locales we actually translate — smaller APK.
        resourceConfigurations += listOf("en", "uk")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.exifinterface)
    implementation(libs.glide)
    implementation(libs.play.services.auth)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    // Real org.json for JVM tests (android.jar ships non-functional stubs).
    testImplementation(libs.org.json)
}

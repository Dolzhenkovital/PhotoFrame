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
        versionCode = 2
        versionName = "0.3.0"

        // Ship only the locales we actually translate — smaller APK.
        resourceConfigurations += listOf("en", "uk")
    }

    // Provided by the release workflow (secrets → env). Local builds and CI
    // debug builds have no keystore: the config simply isn't created and
    // the release build stays unsigned.
    val releaseKeystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
        ?.takeIf { it.isNotBlank() }
    if (releaseKeystorePath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseKeystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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

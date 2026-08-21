import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Desktop-app OAuth client for the loopback sign-in (README "Google Photos"
// section). Not confidential for installed apps (RFC 8252 §8.5), but kept
// out of the repo so forks don't burn this project's quota: env vars first
// (CI), then local.properties (developer machines).
fun oauthConfig(name: String, localPropertiesKey: String): String {
    val env: String? = System.getenv(name)
    if (!env.isNullOrBlank()) return env
    val file = rootProject.file("local.properties")
    if (file.isFile) {
        val props = Properties()
        file.inputStream().use { stream -> props.load(stream) }
        val value: String? = props.getProperty(localPropertiesKey)
        if (!value.isNullOrBlank()) return value
    }
    return ""
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

        buildConfigField(
            "String", "GP_OAUTH_CLIENT_ID",
            "\"${oauthConfig("GP_OAUTH_CLIENT_ID", "gp.oauthClientId")}\""
        )
        buildConfigField(
            "String", "GP_OAUTH_CLIENT_SECRET",
            "\"${oauthConfig("GP_OAUTH_CLIENT_SECRET", "gp.oauthClientSecret")}\""
        )
    }

    buildFeatures {
        buildConfig = true
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

    // Optional: CI signs DEBUG builds with the developer's own debug
    // keystore. Google matches the Photos OAuth client by package + signing
    // SHA-1, and every ephemeral CI runner otherwise invents a random debug
    // key — artifact APKs would install fine but fail Google sign-in.
    // Standard AOSP debug-keystore credentials by definition.
    val debugKeystorePath = System.getenv("DEBUG_KEYSTORE_PATH")
        ?.takeIf { it.isNotBlank() }
    if (debugKeystorePath != null) {
        signingConfigs {
            getByName("debug") {
                storeFile = file(debugKeystorePath)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
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
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    // Real org.json for JVM tests (android.jar ships non-functional stubs).
    testImplementation(libs.org.json)
}

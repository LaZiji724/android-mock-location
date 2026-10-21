plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.locationprobe"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "com.example.locationprobe"
        minSdk = 29
        targetSdk = 36
        versionCode = 3
        versionName = "0.2.1-diag"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // Optional shared key so each published APK installs over the previous one. Pass
    // -PmockSigningStore=<jks> -PmockSigningPassword=<password>; without them the machine's debug key is used.
    val sharedStore = providers.gradleProperty("mockSigningStore").orNull
    if (sharedStore != null) {
        val password = providers.gradleProperty("mockSigningPassword").get()
        signingConfigs.create("shared") {
            storeFile = file(sharedStore); storePassword = password
            keyAlias = "mocklocation"; keyPassword = password
        }
        buildTypes.getByName("debug").signingConfig = signingConfigs.getByName("shared")
    }
}

dependencies {
    implementation("androidx.core:core:1.16.0")
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.webdata.explorer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.webdata.explorer"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.android.tools.smali:smali-dexlib2:3.0.9")
    testImplementation("junit:junit:4.13.2")
}

plugins {
    id("com.android.application")
}

android {
    namespace = "com.saltchang.whisalt"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.saltchang.whisalt"
        minSdk = 30
        targetSdk = 36
        versionCode = 2
        versionName = "0.3.0"

        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
    // Official sherpa-onnx AAR (Kotlin API + native libs), fetched by `make deps`
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("org.apache.commons:commons-compress:1.28.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
}

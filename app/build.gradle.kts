plugins {
    id("com.android.application")
}

android {
    namespace = "com.saltchang.whisalt"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.saltchang.whisalt"
        minSdk = 30
        targetSdk = 36
        versionCode = 2
        versionName = "0.3.0"

        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                // whisper.cpp at -O0 (AGP's default for debug builds) is several times slower
                arguments += listOf("-DANDROID_STL=c++_shared", "-DCMAKE_BUILD_TYPE=Release")
                // Our JNI lib, plus the ggml CPU variants it dlopen()s at runtime (nothing links them,
                // so they must be listed): baseline, dotprod+fp16 (most 2019+ SoCs) and i8mm
                // (Snapdragon 8 Gen 1 and newer). The other variants need SVE, which Snapdragons don't expose.
                targets += listOf(
                    "whisper_jni",
                    "ggml-cpu-android_armv8.0_1",
                    "ggml-cpu-android_armv8.2_2",
                    "ggml-cpu-android_armv8.6_1",
                )
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions { unitTests { isIncludeAndroidResources = true } }

    // whisper.cpp; its source is fetched by `make deps`
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    // ggml dlopen()s its CPU variants (libggml-cpu-*.so) by scanning nativeLibraryDir, so native
    // libraries must be extracted to disk rather than read straight from the APK
    packaging { jniLibs { useLegacyPackaging = true } }
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

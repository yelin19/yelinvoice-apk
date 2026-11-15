plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "com.yelinvoice.offline"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yelinvoice.offline"
        minSdk = 24
        targetSdk = 34
        versionCode = 16
        versionName = "0.16.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DWHISPER_BUILD_TESTS=OFF",
                    "-DWHISPER_BUILD_EXAMPLES=OFF",
                    "-DWHISPER_BUILD_SERVER=OFF",
                    "-DGGML_OPENMP=OFF",
                    "-DGGML_NATIVE=OFF",
                    "-DCMAKE_BUILD_TYPE=Release"
                )
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

    ndkVersion = "25.2.9519653"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/whisper-android/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

chaquopy {
    defaultConfig {
        // This is the embedded Python version, not the Chaquopy Gradle plugin version.
        version = "3.10"
        pip {
            install("numpy==1.26.2")
            install("nltk==3.8.1")
        }
    }
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.core:core:1.12.0")
}

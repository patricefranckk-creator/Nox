import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Moteur d'images local de Lia (stable-diffusion.cpp), repris pour l'agrandissement HD (Real-ESRGAN)
// des images créées par NVIDIA. Module et projet CMake à part : voir src/main/cpp/CMakeLists.txt.
plugins {
    id("com.android.library")
}

android {
    namespace = "com.localia.imagegen"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 31
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                if (System.getenv("NOX_CCACHE") == "1") {
                    arguments += listOf("-DCMAKE_C_COMPILER_LAUNCHER=ccache", "-DCMAKE_CXX_COMPILER_LAUNCHER=ccache")
                }
            }
        }
        consumerProguardFiles("consumer-rules.pro")
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

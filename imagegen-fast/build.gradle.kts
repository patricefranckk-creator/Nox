// Le même moteur compilé pour les puces ARM récentes (dot-product + demi-précision) : libliasd_fast.so,
// choisie au lancement si le processeur la gère (Snapdragon 6 Gen 4 : oui). Voir LIA_SD_FAST dans CMakeLists.
plugins {
    id("com.android.library")
}

android {
    namespace = "com.localia.imagegen.fast"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 31
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DLIA_SD_FAST=ON")
                if (System.getenv("NOX_CCACHE") == "1") {
                    arguments += listOf("-DCMAKE_C_COMPILER_LAUNCHER=ccache", "-DCMAKE_CXX_COMPILER_LAUNCHER=ccache")
                }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path("../imagegen/src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

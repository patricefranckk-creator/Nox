import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
}

android {
    namespace = "com.application.nox"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.application.nox"
        minSdk = 31          // reconnaissance vocale 100 % sur l'appareil (API 31+)
        targetSdk = 36
        // Numéro de version automatique : chaque build GitHub Actions incrémente le dernier chiffre.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "2.1.$build"
        val sha = (System.getenv("GITHUB_SHA") ?: "local").take(7)
        val date = java.time.LocalDateTime.now(java.time.ZoneId.of("Europe/Paris"))
            .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
        resValue("string", "nox_commit", sha)
        resValue("string", "nox_build_date", date)
        ndk { abiFilters += listOf("arm64-v8a") }   // ton Snapdragon = ARM64 uniquement
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        resValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts.add("**/liblitertlm_jni.so")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll("-Xskip-metadata-version-check")
    }
}

dependencies {
    // Moteur LLM local de Google (GPU / CPU, MTP, Gemma 4)
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.1")
}

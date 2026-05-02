plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.screenshare.client"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.screenshare.client"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // ═══════════════════════════════════════════════════════════
    // libVLC — Lecteur vidéo avec support RTP/UDP natif
    // ═══════════════════════════════════════════════════════════
    // ExoPlayer ne supporte PAS le RTP H.264 brut (sans RTSP).
    // libVLC gère nativement les URI rtp://@:port et offre un
    // contrôle fin du network-caching pour la basse latence.
    implementation("org.videolan.android:libvlc-all:3.6.0")

    // JSON parsing (inclus dans Android, pas de dépendance extra)
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ca.tilex.lexnav"
    compileSdk = 34

    defaultConfig {
        applicationId = "ca.tilex.lexnav"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }
    signingConfigs {
        create("lexnav") {
            storeFile = file("lexnav.keystore")
            storePassword = "lexnav2026"
            keyAlias = "lexnav"
            keyPassword = "lexnav2026"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("lexnav") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("lexnav")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

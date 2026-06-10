plugins {
    id("com.android.application")
}

android {
    namespace = "com.zui.perfctl"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zui.zuiperfctl"
        minSdk = 29
        targetSdk = 35
        versionCode = 10
        versionName = "0.10.0"
    }

    signingConfigs {
        create("release") {
            val storeFilePath = providers.gradleProperty("zuiperf.storeFile").orNull
                ?: System.getenv("KEYSTORE_FILE")
            if (!storeFilePath.isNullOrBlank()) {
                storeFile = file(storeFilePath)
            }
            storePassword = providers.gradleProperty("zuiperf.storePassword").orNull
                ?: System.getenv("KEYSTORE_PASSWORD")
            keyAlias = providers.gradleProperty("zuiperf.keyAlias").orNull
                ?: System.getenv("KEY_ALIAS")
            keyPassword = providers.gradleProperty("zuiperf.keyPassword").orNull
                ?: System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        getByName("debug") {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

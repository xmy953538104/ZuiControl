plugins {
    id("com.android.application")
}

android {
    namespace = "com.zui.zuicontrol"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zui.zuicontrol"
        minSdk = 29
        targetSdk = 35
        versionCode = 22
        versionName = "0.19.3"
    }

    signingConfigs {
        create("release") {
            val storeFilePath = providers.gradleProperty("zuicontrol.storeFile").orNull
                ?: System.getenv("KEYSTORE_FILE")
            if (!storeFilePath.isNullOrBlank()) {
                storeFile = file(storeFilePath)
            }
            storePassword = providers.gradleProperty("zuicontrol.storePassword").orNull
                ?: System.getenv("KEYSTORE_PASSWORD")
            keyAlias = providers.gradleProperty("zuicontrol.keyAlias").orNull
                ?: System.getenv("KEY_ALIAS")
            keyPassword = providers.gradleProperty("zuicontrol.keyPassword").orNull
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

dependencies {
    compileOnly(project(":framework-stubs"))
}

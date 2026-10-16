plugins {
    id("com.android.application")
}

android {
    namespace = "com.zui.zuicontrol"
    compileSdk = 35
    buildFeatures.buildConfig = true
    testBuildType = if(providers.gradleProperty("frontendProbe").isPresent) "release" else "debug"

    defaultConfig {
        applicationId = "com.zui.zuicontrol"
        minSdk = 29
        targetSdk = 30
        versionCode = 84
        versionName = "0.21.47"
        testInstrumentationRunner = "com.zui.zuicontrol.probe.ResourceProbe"
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
            isProfileable = false
            isMinifyEnabled = false
            // The baseline receiver compiles unsigned and verifies an authorized CI signature separately.
            if (!providers.gradleProperty("zuicontrol.unsignedBaseline").isPresent) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        getByName("debug") {
            isDebuggable = true
        }
        create("nativeProfile") {
            initWith(getByName("release"))
            isProfileable = true
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly(project(":framework-stubs"))
    androidTestCompileOnly(project(":framework-stubs"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20180813")
}

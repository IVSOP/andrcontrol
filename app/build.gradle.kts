plugins {
    id("com.android.application")
}

android {
    namespace = "dev.andrcontrol"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.andrcontrol"
        minSdk = 28
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
        testInstrumentationRunner = "dev.andrcontrol.DeviceSmokeInstrumentation"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}


plugins {
    id("com.android.application")
}

android {
    namespace = "com.yagay.aihub"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.yagay.aihub"
        minSdk = 28
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.activity:activity:1.13.0")
    implementation("androidx.webkit:webkit:1.17.0")
}

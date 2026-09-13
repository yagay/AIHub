plugins {
    id("com.android.application")
}

val aihubAbi = providers.gradleProperty("aihubAbi").orNull
val aihubVersionCode = providers.gradleProperty("aihubVersionCode").orNull?.toIntOrNull()
val aihubVersionName = providers.gradleProperty("aihubVersionName").orNull

android {
    namespace = "com.yagay.aihub"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.yagay.aihub"
        minSdk = 28
        targetSdk = 36
        versionCode = aihubVersionCode ?: 4
        versionName = aihubVersionName ?: "0.4.0"

        aihubAbi?.takeIf { it.isNotBlank() }?.let { abi ->
            ndk {
                abiFilters += abi
            }
        }
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
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

plugins {
    id("com.android.application")
}

val aihubAbi = providers.gradleProperty("aihubAbi").orNull
val aihubVersionCode = providers.gradleProperty("aihubVersionCode").orNull?.toIntOrNull()
val aihubVersionName = providers.gradleProperty("aihubVersionName").orNull

android {
    namespace = "com.yagay.aihub"
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

    defaultConfig {
        applicationId = "com.yagay.aihub"
        minSdk = 28
        targetSdk = 36
        versionCode = aihubVersionCode ?: 500
        versionName = aihubVersionName ?: "0.5.0"

        aihubAbi?.takeIf { it.isNotBlank() }?.let { abi ->
            ndk { abiFilters += abi }
        }
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // aapt's default ignore list contains <dir>_*, which silently drops
    // Next.js' required assets/ui/_next directory and produces a blank WebView.
    // Keep the normal junk-file filters, but deliberately allow underscore dirs.
    androidResources {
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:!CVS:!thumbs.db:!picasa.ini:!*~"
    }
}

dependencies {
    implementation("androidx.activity:activity:1.13.0")
    implementation("androidx.webkit:webkit:1.14.0")
    compileOnly("io.github.libxposed:api:102.0.0")
}

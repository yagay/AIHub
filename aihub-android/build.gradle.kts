plugins {
    id("com.android.library")
}

android {
    namespace = "com.yagay.aihub.android"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
    }

    sourceSets {
        getByName("main") {
            java.srcDir("java")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":aihub-core"))
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.webkit:webkit:1.17.0")
}

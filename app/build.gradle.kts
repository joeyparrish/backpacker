plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun gitVersionName(): String = try {
    ProcessBuilder("git", "describe", "--tags", "--always")
        .directory(rootDir)
        .start()
        .inputStream.bufferedReader().readLine()
        ?.trim()?.removePrefix("v") ?: "0.0.0"
} catch (e: Exception) { "0.0.0" }

fun gitVersionCode(): Int = try {
    ProcessBuilder("git", "rev-list", "--count", "HEAD")
        .directory(rootDir)
        .start()
        .inputStream.bufferedReader().readLine()
        ?.trim()?.toInt() ?: 1
} catch (e: Exception) { 1 }

android {
    namespace = "io.github.joeyparrish.backpacker"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.joeyparrish.backpacker"
        minSdk = 26
        targetSdk = 35
        versionCode = gitVersionCode()
        versionName = gitVersionName()
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

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // OpenCV for Android — official Maven Central release (includes native .so for all ABIs)
    // https://central.sonatype.com/artifact/org.opencv/opencv
    // 4.12.0 ships with 16KB-aligned LOAD segments required by Android 15 devices.
    implementation("org.opencv:opencv:4.12.0")
}

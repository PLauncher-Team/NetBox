plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.netbox"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.netbox"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Produce a separate APK for each ABI. Native libraries from both the
    // LibXray AAR and src/main/jniLibs are packaged only for the matching ABI.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = false
        }
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
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // 26.7.28 is the newest version currently published under this Maven
    // coordinate. Its LibXray API uses apiVersion 1 (configPath payload).
    // Keep Java bindings and libgojni.so together in this single AAR.
    implementation("io.github.wanliyunyan:libxray:26.7.28")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

/**
 * Builds all ABI-specific debug APKs and copies them to the repository-level
 * build/apks/debug folder so they are easy to find on Windows and CI.
 *
 * Run: .\\gradlew.bat :app:collectDebugApks
 */
tasks.register<Sync>("collectDebugApks") {
    group = "build"
    description = "Builds and collects one debug APK per Android ABI"
    dependsOn("assembleDebug")
    from(layout.buildDirectory.dir("outputs/apk/debug")) {
        include("*.apk")
    }
    into(rootProject.layout.buildDirectory.dir("apks/debug"))
}

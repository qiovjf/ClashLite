plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.clashlite"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.clashlite"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "1.3.0"
        // 只打包 arm64 内核，减小体积
        ndk { abiFilters.add("arm64-v8a") }
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("clashlite-release.jks")
            storePassword = System.getenv("KS_PASS") ?: "clashlite2026"
            keyAlias = "clashlite"
            keyPassword = System.getenv("KS_PASS") ?: "clashlite2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        // 内核二进制必须解包到 nativeLibraryDir 才能被 exec（Android 10+ 要求）
        jniLibs { useLegacyPackaging = true }
    }
    testOptions { unitTests.isIncludeAndroidResources = false }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.8.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.yaml:snakeyaml:2.2")

    // iOS 风格实时模糊玻璃（RenderEffect，Android 12+，旧设备自动退化为半透明）
    implementation("dev.chrisbanes.haze:haze:1.6.6")
    implementation("dev.chrisbanes.haze:haze-materials:1.6.6")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.json:json:20240303")
}

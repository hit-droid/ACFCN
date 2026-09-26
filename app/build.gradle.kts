plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.selfmod.agent"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.selfmod.agent"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signing is injected from env (CI) or gradle properties (local).
            // Falls back to unsigned/debug so local builds never break.
            val storePath = System.getenv("ACFCN_KEYSTORE") ?: (project.findProperty("acfcn.keystore") as String?)
            if (!storePath.isNullOrBlank() && file(storePath).exists()) {
                signingConfig = signingConfigs.create("release") {
                    storeFile = file(storePath)
                    storePassword = System.getenv("ACFCN_STORE_PASSWORD") ?: (project.findProperty("acfcn.storePassword") as String?) ?: ""
                    keyAlias = System.getenv("ACFCN_KEY_ALIAS") ?: (project.findProperty("acfcn.keyAlias") as String?) ?: ""
                    keyPassword = System.getenv("ACFCN_KEY_PASSWORD") ?: (project.findProperty("acfcn.keyPassword") as String?) ?: ""
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    ndkVersion = "27.0.12077973"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation("com.google.android.material:material:1.12.0")
    implementation(libs.rhino)
    implementation(libs.okhttp)
    implementation(libs.coroutines.android)
    implementation(libs.security.crypto)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.test.ext:junit:1.1.5")
    testImplementation("org.robolectric:robolectric:4.12.1")
    // Compose UI 测试需要
    testImplementation("androidx.compose.ui:ui-test-junit4")

    debugImplementation(libs.compose.ui.tooling)
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------
// 签名配置：凭据由环境变量注入（GitHub Actions 从 Secrets 提供）
// 本地没有这些环境变量时，release 构建将不签名（不会报错）
// ---------------------------------------------------------------
val keystorePath = System.getenv("SIGNING_KEYSTORE_PATH")
val hasSigningConfig = !keystorePath.isNullOrBlank() && file(keystorePath).exists()

android {
    namespace = "com.drcom.autologin"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.drcom.autologin"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "1.6"
    }

    buildFeatures {
        viewBinding = true
    }

    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
                storeType = "PKCS12"
                // 说明：AGP 在 minSdk >= 24 时会忽略 enableV1Signing，
                // 因此 CI 不依赖这里的签名配置，而是由 workflow 里的 apksigner
                // 显式以 --v1-signing-enabled true 签名（v1 + v2 + v3）。
                // 这里的配置只用于「本地带环境变量构建时」的兜底。
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}

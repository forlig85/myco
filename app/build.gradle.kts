plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// CI에서 -PversionCode=… -PversionName=… 으로 넘김. 로컬(Android Studio) 빌드는 기본값.
val ciVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
val ciVersionName = (project.findProperty("versionName") as String?) ?: "2.0.0-local"

android {
    namespace = "io.github.forlig85.memoauto"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.forlig85.memoauto"
        minSdk = 31
        targetSdk = 35
        versionCode = ciVersionCode
        versionName = ciVersionName
    }

    // 개인 사이드로드용 고정 서명 키. 모든 빌드(debug 포함)를 같은 키로 서명해
    // 업데이트 시 기존 앱을 지우지 않아도 되게 한다.
    signingConfigs {
        create("personal") {
            storeFile = rootProject.file("keystore/memoauto-release.jks")
            storePassword = "memoauto-personal"
            keyAlias = "memoauto"
            keyPassword = "memoauto-personal"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("personal")
        }
        debug {
            signingConfig = signingConfigs.getByName("personal")
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
        buildConfig = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

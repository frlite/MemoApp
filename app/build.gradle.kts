plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Room 注解处理需要 kapt，版本跟随 Kotlin 插件
    id("org.jetbrains.kotlin.kapt")
}

// 在脚本顶层读取签名信息，避免在 android {} 内读取时机不确定。
// 本地构建可在 gradle.properties 中配置；CI 通过环境变量注入。
val releaseStoreFile: String? =
    (project.findProperty("RELEASE_STORE_FILE") as String?) ?: System.getenv("RELEASE_STORE_FILE")
val releaseStorePassword: String? =
    (project.findProperty("RELEASE_STORE_PASSWORD") as String?) ?: System.getenv("RELEASE_STORE_PASSWORD")
val releaseKeyAlias: String? =
    (project.findProperty("RELEASE_KEY_ALIAS") as String?) ?: System.getenv("RELEASE_KEY_ALIAS")
val releaseKeyPassword: String? =
    (project.findProperty("RELEASE_KEY_PASSWORD") as String?) ?: System.getenv("RELEASE_KEY_PASSWORD")

val hasReleaseSigning = listOf(
    releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.example.memoapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.memoapp"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
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
            if (hasReleaseSigning) {
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

    buildFeatures {
        viewBinding = true
    }
}

tasks.register("printReleaseSigningStatus") {
    doLast {
        println("签名信息完整: $hasReleaseSigning")
        println("keystore 路径: ${releaseStoreFile ?: "(未配置)"}")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Room 数据库
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
}

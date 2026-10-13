plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Room 注解处理需要 kapt，版本跟随 Kotlin 插件
    id("org.jetbrains.kotlin.kapt")
}

// 签名信息优先读 -P 参数，其次读环境变量。
// 全部通过 Gradle Provider 延迟求值，避免配置阶段读取时机问题。
fun secret(name: String): org.gradle.api.provider.Provider<String> =
    providers.gradleProperty(name).orElse(providers.environmentVariable(name))

val releaseStoreFile = secret("RELEASE_STORE_FILE")
val releaseStorePassword = secret("RELEASE_STORE_PASSWORD")
val releaseKeyAlias = secret("RELEASE_KEY_ALIAS")
val releaseKeyPassword = secret("RELEASE_KEY_PASSWORD")

// 有任意一项配置了 release 签名（调试包构建时通常都没有）
val hasReleaseSigning = releaseStoreFile.isPresent

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
                storeFile = file(releaseStoreFile.get())
                storePassword = releaseStorePassword.orNull
                keyAlias = releaseKeyAlias.orNull
                keyPassword = releaseKeyPassword.orNull
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

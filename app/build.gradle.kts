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

// 密钥必须真实存在，否则签名会静默失败并产出未签名包
val signingConfigValid = hasReleaseSigning &&
    File(releaseStoreFile.get()).absoluteFile.exists()

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
                // 必须用绝对路径：file() 遇相对路径会解析到当前模块目录，导致找不到密钥
                storeFile = File(releaseStoreFile.get()).absoluteFile
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
        println("hasReleaseSigning = $hasReleaseSigning")
        println("signingConfigValid = $signingConfigValid")
        if (hasReleaseSigning) {
            val f = File(releaseStoreFile.get()).absoluteFile
            println("keystore 路径 = ${f.absolutePath}")
            println("keystore 存在 = ${f.exists()}")
            println("keyAlias = ${releaseKeyAlias.orNull}")
            println("storePassword 已设置 = ${!releaseStorePassword.orNull.isNullOrBlank()}")
            println("keyPassword 已设置 = ${!releaseKeyPassword.orNull.isNullOrBlank()}")
        }
    }
}


// 签名配置无效时直接让构建失败，避免产出"看起来成功"的未签名包
tasks.matching { it.name == "validateSigningRelease" }.configureEach {
    doFirst {
        if (!signingConfigValid) {
            throw GradleException(
                "Release 签名不可用：keystore 不存在或路径无效。" +
                    "hasReleaseSigning=$hasReleaseSigning, path=${releaseStoreFile.orNull}"
            )
        }
    }
}

tasks.configureEach {
    if (name == "preReleaseBuild" && !signingConfigValid && hasReleaseSigning) {
        doFirst {
            throw GradleException("Release 签名不可用，已中止构建以防产出未签名包")
        }
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

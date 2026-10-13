plugins {
    id("com.android.application") version "8.2.0" apply false
    // kapt 随 Kotlin 插件一同解析，无需单独声明版本
    id("org.jetbrains.kotlin.android") version "1.9.20" apply false
}

import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * 签名有两条通道，二选一，都指向同一份 keystore：
 *   1) 本地：android/keystore.properties（git 忽略）
 *   2) CI：环境变量 KEYSTORE_FILE / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD
 * 环境变量优先，方便 CI 覆盖。
 */
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

fun secret(envKey: String, propKey: String): String? {
    val fromEnv = System.getenv(envKey)?.takeIf { it.isNotBlank() }
    if (fromEnv != null) return fromEnv
    return keystoreProps.getProperty(propKey)?.takeIf { it.isNotBlank() }
}

val storeFileValue = secret("KEYSTORE_FILE", "storeFile")
val storePasswordValue = secret("KEYSTORE_PASSWORD", "storePassword")
val keyAliasValue = secret("KEY_ALIAS", "keyAlias")
val keyPasswordValue = secret("KEY_PASSWORD", "keyPassword")

val releaseKeystore: File? = storeFileValue?.let { rootProject.file(it) }
val canSignRelease = releaseKeystore != null &&
    releaseKeystore.exists() &&
    storePasswordValue != null &&
    keyAliasValue != null &&
    keyPasswordValue != null

// 版本号可以由 CI 传 -PversionName=1.2.3 -PversionCode=12
val appVersionName = (findProperty("versionName") as String?) ?: "1.0.0"
val appVersionCode = ((findProperty("versionCode") as String?) ?: "1").toInt()

android {
    namespace = "com.wallkiosk.console"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wallkiosk.console"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = storePasswordValue
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (canSignRelease) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // 规范要求编译零警告，这里直接把它变成硬约束：
        // 任何警告都会让构建失败，而不是被忽略。
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // 只用 Compose 的基础设施，不带 material3 —— 界面是自研的拟态组件
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime")

    testImplementation("junit:junit:4.13.2")
}

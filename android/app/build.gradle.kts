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
            // 不开 R8 的话 Compose 的 DEX 原样打进去，APK 会到 5MB 左右。
            // 开了之后通常能压到 2~3MB：没用到的组件、调试符号、coroutines-debug
            // 都会被裁掉。Compose 和 AndroidX 自带 consumer rules，不需要额外 keep。
            isMinifyEnabled = true
            isShrinkResources = true
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

    // 只在单元测试作用域，不进 APK。
    // android.jar 里的 org.json 是抛 "not mocked" 的桩，不引一份真实现的话
    // ReleaseParser 的解析测试根本跑不起来（那样就只能靠肉眼看代码，不算验证）。
    testImplementation("org.json:json:20231013")
}

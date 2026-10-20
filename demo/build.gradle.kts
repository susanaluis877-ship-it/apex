plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.apex.demo"
    compileSdk = 36
    enableKotlin = false

    defaultConfig {
        applicationId = "com.example.apex.demo"
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            // 模拟宿主开启 R8，验证 SDK 的 consumer rules
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // 仅 demo 用：借用 debug 签名，方便直接安装 release 包
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":apex"))
}

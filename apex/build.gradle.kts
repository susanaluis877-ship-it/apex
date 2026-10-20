plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

// 在 JitPack 上构建时，坐标用 JitPack 注入的环境变量（GROUP=com.github.<用户名>，VERSION=tag 名）；
// 本地构建用 gradle.properties 里的值
val onJitPack = providers.environmentVariable("JITPACK").orNull == "true"
val sdkGroup = if (onJitPack) providers.environmentVariable("GROUP").get()
               else providers.gradleProperty("GROUP").get()
val sdkVersion = if (onJitPack) providers.environmentVariable("VERSION").get()
                 else providers.gradleProperty("VERSION_NAME").get()

group = sdkGroup
version = sdkVersion

android {
    namespace = "com.example.apex"
    compileSdk = 36
    // 资源名必须带前缀，避免和宿主资源撞名
    resourcePrefix = "apex_"
    // AGP 9 默认内置 Kotlin，会给 POM 自动加上 kotlin-stdlib；纯 Java SDK 必须关掉
    enableKotlin = false

    defaultConfig {
        minSdk = 21
        // 随 AAR 一起交给宿主的 keep 规则（AAR 内的 proguard.txt）
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "SDK_VERSION", "\"$sdkVersion\"")
    }

    buildTypes {
        release {
            // 开源 SDK 不做自身混淆；宿主开启 R8 时，按 consumer-rules.pro 统一处理
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

dependencies {
    testImplementation(libs.junit)
}

// AGP 在 afterEvaluate 阶段才创建 release 组件
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = sdkGroup
                artifactId = "apex"
                version = sdkVersion
                pom {
                    name = "Apex"
                    description = "A pure Java Android SDK with no third-party dependencies."
                    url = "https://github.com/susanaluis877-ship-it/apex"
                    licenses {
                        license {
                            name = "MIT License"
                            url = "https://opensource.org/licenses/MIT"
                        }
                    }
                    scm {
                        url = "https://github.com/susanaluis877-ship-it/apex"
                        connection = "scm:git:https://github.com/susanaluis877-ship-it/apex.git"
                    }
                }
            }
        }
        repositories {
            maven {
                name = "local"
                url = uri(rootProject.layout.buildDirectory.dir("repo"))
            }
        }
    }
}

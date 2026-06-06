plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
}

android {
    namespace = "com.xinxin.xinlog"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    publishing { singleVariant("release") }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    // 仅编译期；运行时由宿主 app 自带协程库提供，不打进 SDK
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.github.daimhim"
                artifactId = "xinlog"
                version = "1.1"
            }
        }
    }
}

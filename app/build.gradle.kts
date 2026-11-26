plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.yg.comic"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.yg.comic"
        minSdk = 26
        targetSdk = 35
        versionCode = 226
        versionName = "0.1.226"
        vectorDrawables { useSupportLibrary = true }
        ndk {
            // 发布包只带真机 arm64；本地模拟器实测时传 -PincludeEmulatorAbi 加回 x86_64。
            abiFilters += if (project.hasProperty("includeEmulatorAbi")) {
                listOf("arm64-v8a", "x86_64")
            } else {
                listOf("arm64-v8a")
            }
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"
                cFlags += "-fopenmp"
                cppFlags += "-fopenmp -static-openmp"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    ndkVersion = "27.0.12077973"

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            applicationIdSuffix = ".debug"
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.material.kolor)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.json.okio)
    // Mihon/Tachiyomi 扩展通过 parentFirstPrefixes(kotlinx./rx.)向宿主要这两个家族的类；
    // 扩展 APK 自身不打包，宿主缺就 NoClassDefFoundError（mangamillion 缺 ProtoNumber 实测崩）。
    implementation(libs.kotlinx.serialization.protobuf)
    implementation(libs.rx.java)

    // Mihon/Tachiyomi 扩展运行环境（扩展 APK 运行时依赖这些真实实现，不能用编译期 stub）
    implementation(libs.okhttp)
    implementation(libs.okhttp.brotli)
    // okhttp-zstd 只保留 okhttp3.zstd.Zstd 这个类，Mihon 扩展按类名引用它；
    // 排除 zstd-kmp native：libzstd-kmp.so 在部分真机会在 createJniZstd 时 abort。
    implementation(libs.okhttp.zstd) {
        exclude(group = "com.squareup.zstd")
    }
    implementation(libs.jsoup)
    implementation(libs.androidx.preference)
    implementation(libs.injekt.core)

    implementation(libs.coil.compose)
    implementation(libs.coil.compose.core)
    implementation(libs.coil.network.okhttp)

    implementation(libs.zip4j)
    implementation(libs.junrar)
    implementation(libs.commons.compress)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
}





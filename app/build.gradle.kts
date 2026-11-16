plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ===== MỘT ẢNH PNG DUY NHẤT -> banner, icon, màn hình chào =====
// Mỗi lần build, tác vụ generateBrand chạy branding/BrandGen.java để sinh banner, icon và ảnh màn hình chào
// từ file app/branding/app_logo.png vào build/generated/brand/res. Muốn đổi tất cả: chỉ cần thay file PNG đó.
val brandSrc = file("branding/app_logo.png")
val brandGen = file("branding/BrandGen.java")
val brandOut = layout.buildDirectory.dir("generated/brand/res")

val generateBrand = tasks.register<Exec>("generateBrand") {
    inputs.file(brandSrc)
    inputs.file(brandGen)
    outputs.dir(brandOut)
    commandLine(
        File(System.getProperty("java.home"), "bin/java").path,
        "-Djava.awt.headless=true",
        brandGen.path, brandSrc.path, brandOut.get().asFile.path,
    )
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(generateBrand) }

android {
    namespace = "vn.xemtv.lite"
    compileSdk = 34

    defaultConfig {
        applicationId = "vn.xemtv.lite"
        minSdk = 26
        targetSdk = 34
        versionCode = 32
        versionName = "4.1"
        resourceConfigurations += listOf("vi", "en")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Ký bằng khóa debug để cài được ngay. Muốn đăng lên store thì tạo khóa riêng.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    sourceSets { getByName("main") { res.srcDir(brandOut) } }      // ảnh sinh tự động từ app_logo.png
    lint { checkReleaseBuilds = false; abortOnError = false }
    packaging { resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "DebugProbesKt.bin") }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-svg:2.7.0")      // đọc logo dạng SVG (nhiều logo Wikipedia là SVG)
}

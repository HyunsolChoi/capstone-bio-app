// ==========================
// app/build.gradle.kts  (updated with release signing config)
// ==========================
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-parcelize")
    id("kotlin-kapt")                       // Kapt 유지
    id("dagger.hilt.android.plugin")        // 모듈에서 Hilt 적용
    id("com.google.gms.google-services")
    id("androidx.navigation.safeargs.kotlin")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.jjangdol.biorhythm"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jjangdol.biorhythm"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    /**
     * ▶▶  릴리즈 키 설정 ◀◀
     */
    signingConfigs {
        create("release") {
            storeFile = file("C:/Users/vnvnd/Desktop/key/mykey.jks")
            storePassword = "111111"   // TODO: 교체
            keyAlias = "key0"                       // TODO: 별칭 확인 후 교체
            keyPassword = "111111"       // TODO: 교체
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 릴리즈 빌드에 서명 정보 적용
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        viewBinding = true
        dataBinding = true
    }

    /* Kotlin 2.1.x ↔ Compose Compiler 1.6.14 이상 호환 */
    composeOptions { kotlinCompilerExtensionVersion = "1.6.14" }

    /**
     * Gradle 8.0+ 에서는 packagingOptions 대신 packaging 블록 사용 권장.
     */
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// ==========================
// Dependencies (그대로 유지)
// ==========================

dependencies {
    /* --- AndroidX Core --- */
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0-alpha13")

    /* --- Lifecycle --- */
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.0")

    /* --- Jetpack Compose --- */
    implementation(platform("androidx.compose:compose-bom:2025.03.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    /* --- Navigation --- */
    val navVersion = "2.7.7"
    implementation("androidx.navigation:navigation-fragment-ktx:$navVersion")
    implementation("androidx.navigation:navigation-ui-ktx:$navVersion")

    /* --- Firebase --- */
    implementation(platform("com.google.firebase:firebase-bom:33.1.0"))
    implementation("com.google.firebase:firebase-analytics-ktx")
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.google.firebase:firebase-messaging-ktx")
    implementation("com.google.firebase:firebase-database-ktx")

    /* --- Hilt --- */
    implementation("com.google.dagger:hilt-android:2.56.2")
    kapt("com.google.dagger:hilt-compiler:2.56.2")

    /* --- CameraX --- */
    val cameraxVersion = "1.4.0-beta01"
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    /* --- ML Kit --- */
    implementation("com.google.mlkit:face-detection:16.1.7")

    /* --- Charts & Utils --- */
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")

    /* --- Coroutines --- */
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    /* --- WorkManager --- */
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    /* --- Testing --- */
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.03.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    implementation("com.github.wendykierp:JTransforms:3.1")
    implementation("com.google.code.gson:gson:2.10.1")

    implementation("com.google.android.gms:play-services-location:21.2.0")
}

kapt { correctErrorTypes = true }

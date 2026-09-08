plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // Заменяем kapt на ksp (убедитесь, что псевдоним плагина есть в gradle/libs.versions.toml)
    alias(libs.plugins.ksp) 
    // Если вы НЕ используете Version Catalogs для KSP, можно записать напрямую:
    // id("com.google.devtools.ksp") version "2.0.0-1.0.21"
}

android {
    namespace = "com.example.timetracker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.timetracker"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        compose = true
    }
    // При использовании Kotlin 2.0.0 встроен новый плагин Compose Compiler, 
    // поэтому блок composeOptions.kotlinCompilerExtensionVersion больше не нужен!
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Room Database
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    
    // Заменили kapt на ksp:
    ksp("androidx.room:room-compiler:$roomVersion")
}

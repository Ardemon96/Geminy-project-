buildscript {
    ext {
        // Актуальные стабильные версии
        agp_version = '8.4.2'
        kotlin_version = '2.0.0'
    }
    dependencies {
        classpath "com.android.tools.build:gradle:$agp_version"
        classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlin_version"
    }
}

plugins {
    id 'com.android.application' version '8.4.2' apply false
    id 'org.jetbrains.kotlin.android' version '2.0.0' apply false
}

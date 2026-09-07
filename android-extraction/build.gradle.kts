plugins {
    id("com.android.library")
}

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

android {
    namespace = "com.hugo.smartexpense.androidextraction"
    compileSdk = 35

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":"))
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.13.1")
}

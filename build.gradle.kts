plugins {
    kotlin("jvm") version "2.3.21"
    id("com.android.library") version "9.3.0" apply false
    id("com.android.application") version "9.3.0" apply false
    id("com.google.devtools.ksp") version "2.3.4" apply false
    kotlin("plugin.compose") version "2.3.21" apply false
}

group = "com.hugo.smartexpense"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.test {
    useJUnitPlatform()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

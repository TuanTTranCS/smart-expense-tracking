plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    kotlin("plugin.compose")
}

import java.io.FileInputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

fun configuredValue(gradleProperty: String, environmentVariable: String, default: String? = null): String? =
    providers.gradleProperty(gradleProperty).orNull
        ?: providers.environmentVariable(environmentVariable).orNull
        ?: default

val hostDebugKeystore = System.getenv("USERPROFILE")
    ?.let { file("$it/.android/debug.keystore") }
    ?.takeIf { it.isFile }
val debugKeystoreFile = configuredValue(
    gradleProperty = "smartExpense.debugKeystore",
    environmentVariable = "SMART_EXPENSE_DEBUG_KEYSTORE",
)?.let(::file) ?: hostDebugKeystore
val debugKeystorePassword = configuredValue(
    gradleProperty = "smartExpense.debugKeystorePassword",
    environmentVariable = "SMART_EXPENSE_DEBUG_KEYSTORE_PASSWORD",
    default = "android",
)
val debugKeyAlias = configuredValue(
    gradleProperty = "smartExpense.debugKeyAlias",
    environmentVariable = "SMART_EXPENSE_DEBUG_KEY_ALIAS",
    default = "androiddebugkey",
)
val debugKeyPassword = configuredValue(
    gradleProperty = "smartExpense.debugKeyPassword",
    environmentVariable = "SMART_EXPENSE_DEBUG_KEY_PASSWORD",
    default = "android",
)

android {
    namespace = "com.hugo.smartexpense.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hugo.smartexpense.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The debug artifact must be installable on physical ARM phones as well
        // as the x86_64 emulator used during the integration spike.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
    }

    signingConfigs {
        getByName("debug") {
            debugKeystoreFile?.let { storeFile = it }
            storePassword = debugKeystorePassword
            keyAlias = debugKeyAlias
            keyPassword = debugKeyPassword
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val verifyDebugMsalRedirectSignature = tasks.register("verifyDebugMsalRedirectSignature") {
    group = "verification"
    description = "Verifies that the debug signing certificate matches the configured MSAL redirects."

    val authConfigFile = layout.projectDirectory.file("src/main/res/raw/auth_config_single_account.json")
    val manifestFile = layout.projectDirectory.file("src/main/AndroidManifest.xml")
    inputs.file(authConfigFile)
    inputs.file(manifestFile)
    debugKeystoreFile?.let(inputs::file)
    outputs.upToDateWhen { false }

    doLast {
        val signingConfig = android.signingConfigs.getByName("debug")
        val keystore = signingConfig.storeFile
            ?: error("The debug signing keystore could not be resolved.")
        check(keystore.isFile) {
            "The debug signing keystore does not exist: ${keystore.absolutePath}. " +
                "Set smartExpense.debugKeystore or SMART_EXPENSE_DEBUG_KEYSTORE to the registered keystore."
        }

        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        FileInputStream(keystore).use { stream ->
            keyStore.load(stream, signingConfig.storePassword?.toCharArray())
        }
        val certificate = keyStore.getCertificate(signingConfig.keyAlias)
            ?: error("Certificate alias '${signingConfig.keyAlias}' was not found in ${keystore.absolutePath}.")
        val signatureHash = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest(certificate.encoded),
        )
        val encodedSignatureHash = URLEncoder.encode(signatureHash, StandardCharsets.UTF_8)
        val applicationId = android.defaultConfig.applicationId
            ?: error("The Android application ID is not configured.")
        val expectedRedirectUri = "msauth://$applicationId/$encodedSignatureHash"
        val expectedManifestPath = "/$signatureHash"

        val authConfig = authConfigFile.asFile.readText()
        val configuredRedirectUri = Regex("\"redirect_uri\"\\s*:\\s*\"([^\"]+)\"")
            .find(authConfig)?.groupValues?.get(1)
            ?: error("The MSAL redirect_uri is missing from ${authConfigFile.asFile}.")
        val manifest = manifestFile.asFile.readText()
        val configuredManifestPath = Regex("android:path=\"([^\"]+)\"")
            .find(manifest)?.groupValues?.get(1)
            ?: error("The MSAL BrowserTabActivity path is missing from ${manifestFile.asFile}.")

        check(configuredRedirectUri == expectedRedirectUri && configuredManifestPath == expectedManifestPath) {
            """
            The debug signing certificate does not match the configured MSAL redirect.
            Keystore: ${keystore.absolutePath}
            Expected JSON redirect: $expectedRedirectUri
            Configured JSON redirect: $configuredRedirectUri
            Expected manifest path: $expectedManifestPath
            Configured manifest path: $configuredManifestPath
            Use the Microsoft Entra-registered debug keystore or update all registered redirect values together.
            """.trimIndent()
        }
    }
}

tasks.matching { it.name == "preDebugBuild" }.configureEach {
    dependsOn(verifyDebugMsalRedirectSignature)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":"))
    implementation(project(":android-extraction"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.navigation:navigation-compose:2.9.1")
    implementation("androidx.room:room-ktx:2.8.4")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.microsoft.identity.client:msal:8.4.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    ksp("androidx.room:room-compiler:2.8.4")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.3.21")
    testImplementation("androidx.room:room-testing:2.8.4")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.robolectric:robolectric:4.15.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

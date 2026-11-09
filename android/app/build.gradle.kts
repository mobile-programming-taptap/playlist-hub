import java.util.Properties
import java.net.URI

plugins {
    alias(libs.plugins.android.application)
}

val localSettings = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val kakaoNativeAppKey = providers.environmentVariable("KAKAO_NATIVE_APP_KEY").orNull
    ?.trim().orEmpty().ifEmpty { localSettings.getProperty("kakao.nativeAppKey", "").trim() }
require(kakaoNativeAppKey.isEmpty() || kakaoNativeAppKey.matches(Regex("[0-9a-fA-F]{32}"))) {
    "KAKAO_NATIVE_APP_KEY must be the 32-character native app key from Kakao Developers."
}
val publicApiBase = providers.gradleProperty("taptap.apiBase").orElse(providers.environmentVariable("TAPTAP_API_BASE")).getOrElse(localSettings.getProperty("taptap.apiBase", "")).trim().trimEnd('/')
require(publicApiBase.isEmpty() || runCatching {
    val address = URI(publicApiBase)
    address.scheme == "https" && address.host != null && address.rawUserInfo == null && address.rawQuery == null && address.rawFragment == null
        && address.rawPath.isNullOrEmpty() && (address.port == -1 || address.port in 1..65535)
}.getOrDefault(false)) { "TAPTAP_API_BASE must be an HTTPS origin without credentials, path, query or fragment." }
val supportEmail = providers.gradleProperty("taptap.supportEmail").orElse(providers.environmentVariable("TAPTAP_SUPPORT_EMAIL")).getOrElse("").trim()
require(supportEmail.isEmpty() || supportEmail.matches(Regex("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+"))) { "TAPTAP_SUPPORT_EMAIL must be a valid support email address." }
val releaseVersionCode = providers.gradleProperty("taptap.versionCode").orElse(providers.environmentVariable("TAPTAP_VERSION_CODE")).getOrElse("1").toIntOrNull()
require(releaseVersionCode != null && releaseVersionCode > 0) { "TAPTAP_VERSION_CODE must be a positive integer." }
val releaseVersionName = providers.gradleProperty("taptap.versionName").orElse(providers.environmentVariable("TAPTAP_VERSION_NAME")).getOrElse("1.0").trim()
require(releaseVersionName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,49}"))) { "TAPTAP_VERSION_NAME must contain 1-50 version characters." }
val signingNames = listOf("TAPTAP_SIGNING_STORE_FILE", "TAPTAP_SIGNING_STORE_PASSWORD", "TAPTAP_SIGNING_KEY_ALIAS", "TAPTAP_SIGNING_KEY_PASSWORD")
val signingValues = signingNames.associateWith { providers.environmentVariable(it).getOrElse("") }
val signingConfigured = signingValues.values.all { it.isNotBlank() }

android {
    namespace = "com.mptaptap.playlisthub"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.mptaptap.playlisthub"
        minSdk = 24
        targetSdk = 37
        versionCode = releaseVersionCode
        versionName = releaseVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "KAKAO_NATIVE_APP_KEY", "\"$kakaoNativeAppKey\"")
        buildConfigField("String", "API_BASE", "\"$publicApiBase\"")
        buildConfigField("String", "SUPPORT_EMAIL", "\"$supportEmail\"")
        manifestPlaceholders["kakaoAppScheme"] = if (kakaoNativeAppKey.isEmpty()) "taptap-kakao-unconfigured" else "kakao$kakaoNativeAppKey"
    }

    if (signingConfigured) signingConfigs.create("upload") {
        storeFile = file(signingValues.getValue("TAPTAP_SIGNING_STORE_FILE"))
        storePassword = signingValues.getValue("TAPTAP_SIGNING_STORE_PASSWORD")
        keyAlias = signingValues.getValue("TAPTAP_SIGNING_KEY_ALIAS")
        keyPassword = signingValues.getValue("TAPTAP_SIGNING_KEY_PASSWORD")
    }
    buildTypes {
        release {
            if (signingConfigured) signingConfig = signingConfigs.getByName("upload")
            optimization {
                enable = false
            }
        }
    }
    testOptions { animationsDisabled = true }
    buildFeatures { buildConfig = true }
    sourceSets.getByName("main").assets.srcDir("../../catalog")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

tasks.register("checkReleaseReadiness") {
    group = "verification"
    description = "Checks public service configuration and privately supplied upload signing inputs. Does not upload anything."
    val missing = mutableListOf<String>()
    if (publicApiBase.isEmpty()) missing.add("TAPTAP_API_BASE")
    if (supportEmail.isEmpty()) missing.add("TAPTAP_SUPPORT_EMAIL")
    if (kakaoNativeAppKey.isEmpty()) missing.add("KAKAO_NATIVE_APP_KEY")
    missing.addAll(signingNames.filter { signingValues.getValue(it).isBlank() })
    val uploadKeyFile = signingValues.getValue("TAPTAP_SIGNING_STORE_FILE").takeIf { it.isNotBlank() }?.let { file(it) }
    doLast {
        check(missing.isEmpty()) { "Upload configuration is incomplete: ${missing.joinToString(", ")}. Debug and unsigned release builds remain available." }
        check(uploadKeyFile?.isFile == true) { "The configured upload keystore file does not exist." }
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.viewpager2:viewpager2:1.0.0")
    implementation("com.kakao.sdk:v2-user:2.25.0")
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}

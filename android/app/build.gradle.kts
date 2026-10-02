plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.mptaptap.playlisthub"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.mptaptap.playlisthub"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    sourceSets.getByName("main").assets.srcDir("../../catalog")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.viewpager2:viewpager2:1.0.0")
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}

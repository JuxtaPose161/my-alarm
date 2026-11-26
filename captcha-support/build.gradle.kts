// Vendored copy of https://github.com/urbandroid-team/sleep-captcha-support (Apache 2.0, see LICENSE).
// The original is published only to jcenter, so the sources live here instead.
// Only change: android.support.annotation -> androidx.annotation.
plugins {
    id("com.android.library")
}

android {
    namespace = "com.urbandroid.sleep.captcha"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
}

plugins {
    id("com.android.application")
}

android {
    namespace = "net.kb1jdx.chrissysdr"
    compileSdk = 37

    defaultConfig {
        applicationId = "net.kb1jdx.chrissysdr"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.3.0-dev.5"
    }

    buildFeatures {
        buildConfig = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

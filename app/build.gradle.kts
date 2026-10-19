plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.airshare.android"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.airshare.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }
}
dependencies { implementation("org.nanohttpd:nanohttpd:2.3.1") }

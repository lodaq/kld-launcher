plugins { id("com.android.application") }

android {
    namespace = "app.kld.launcher"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.kld.launcher"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug") // personal use: the debug key is fine
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = false }
}

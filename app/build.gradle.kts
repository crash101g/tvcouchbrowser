import java.util.Properties

plugins { id("com.android.application") }

// Release signing: a keystore outside the repo. Locally from ~/.tvcouchbrowser/keystore.properties,
// in CI from environment variables set from repository secrets.
val signing = Properties().apply {
    val f = File(System.getProperty("user.home"), ".tvcouchbrowser/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(key: String, env: String): String? = System.getenv(env) ?: signing.getProperty(key)

android {
    namespace = "app.tvcouchbrowser"
    compileSdk { version = release(37) { minorApiLevel = 2 } }

    defaultConfig {
        applicationId = "app.tvcouchbrowser"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "0.3.0"
    }

    signingConfigs {
        create("release") {
            val store = secret("storeFile", "TVCB_KEYSTORE_FILE")
            if (store != null) {
                storeFile = File(store)
                storePassword = secret("storePassword", "TVCB_KEYSTORE_PASSWORD")
                keyAlias = secret("keyAlias", "TVCB_KEY_ALIAS")
                keyPassword = secret("keyPassword", "TVCB_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
                ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures { buildConfig = true }
}

dependencies {
    implementation("androidx.webkit:webkit:1.18.0")
    implementation("com.google.zxing:core:3.5.4")
    testImplementation("junit:junit:4.13.2")
}

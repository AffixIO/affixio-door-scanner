import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Optional defaults for development builds. local.properties is git-ignored; never commit keys.
val local = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun localString(name: String) = "\"" + (local.getProperty(name) ?: "").replace("\"", "") + "\""

android {
    namespace = "com.affixio.doorscanner"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.affixio.doorscanner"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "DEFAULT_API_BASE", localString("affixio.apiBase").let { if (it == "\"\"") "\"https://api.affix-io.com\"" else it })
        buildConfigField("String", "DEFAULT_API_KEY", localString("affixio.apiKey"))
        buildConfigField("String", "DEFAULT_GATE_ID", localString("affixio.gateId"))
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    buildFeatures {
        buildConfig = true
        viewBinding = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/*.kotlin_module")
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // Camera scanning: ZXing Android Embedded (Apache-2.0), no Google Play services needed.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}

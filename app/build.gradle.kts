plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.watchlink"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.maimaiguanfan.watchlink"
        minSdk = 26
        targetSdk = 37
        versionCode = 92
        versionName = "0.52.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // Sign local releases with the existing signing key when requested.
            if (providers.gradleProperty("watchlinkUseExistingSigner").orNull == "true") {
                signingConfig = signingConfigs.getByName("debug")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

dependencies {
    // Inspect native Clock alarm action method bodies before allowing remote actions.
    implementation("com.android.tools.smali:smali-dexlib2:3.0.10")
    // Official miuix Android artifact for the settings & diagnostics UI.
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("androidx.activity:activity-compose:1.13.0")
    // Provided by the LSPosed framework at runtime. Do not package into the APK.
    compileOnly("io.github.libxposed:api:102.0.0")
    // Used by the settings app for scope, running-target diagnostics, and RemotePreferences.
    implementation("io.github.libxposed:service:102.0.0")
    // JVM-only regression tests; Java 17 compatible and excluded from the APK.
    testImplementation("junit:junit:4.13.2")
}

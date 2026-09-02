plugins {
    id("com.android.application") version "8.6.0"
    id("org.jetbrains.kotlin.android") version "1.9.24"
}

import java.util.Properties

android {
    namespace = "app.bildfang"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.bildfang"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Release signing via CI secrets:
            //   ./gradlew assembleRelease -PstoreFile=keystore.jks -PstoreProps=key.properties
            val storePath = project.findProperty("storeFile") as? String
            val propsPath = project.findProperty("storeProps") as? String
            if (storePath != null && propsPath != null) {
                val props = Properties().apply {
                    File(propsPath).inputStream().use { load(it) }
                }
                signingConfigs {
                    create("release") {
                        storeFile = File(storePath)
                        storePassword = props.getProperty("storePassword")
                        keyAlias = props.getProperty("keyAlias")
                        keyPassword = props.getProperty("keyPassword")
                    }
                }
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // ARCore "core" library (Google Play Services for AR). Note: the
    // artifact was renamed from `com.google.ar:arcore` to `com.google.ar:core`
    // (verified against the official samples, Apr 2026). The Java package
    // remains `com.google.ar.core.*`.
    implementation("com.google.ar:core:1.54.0")
    // P1.1: SAF (Storage Access Framework) session storage + browser.
    implementation("androidx.documentfile:documentfile:1.0.1")
    testImplementation("junit:junit:4.13.2")
}

import java.text.SimpleDateFormat
import java.util.Date

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// חותמת בנייה: משתנה בכל הרצת Gradle. נכתבת ללוג ב-WhatsApp ומוצגת במסך המודול,
// כך אפשר לדעת איזו גרסה באמת משובצת ב-WhatsApp אחרי LSPatch.
val buildStamp: String = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())

android {
    namespace = "com.eliav.wacompletemute"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.eliav.wacompletemute"
        minSdk = 26          // Android 8.0 — NotificationChannel API
        targetSdk = 34
        versionCode = 28
        versionName = "3.5.1"

        buildConfigField("String", "BUILD_STAMP", "\"$buildStamp\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    // Xposed API — compileOnly בלבד, לא תלות ב-runtime
    compileOnly(files("libs/XposedBridgeApi-82.jar"))

    // ממשק משתמש בסיסי בלבד (ורוד הסטטוס)
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
}

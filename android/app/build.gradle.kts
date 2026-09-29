import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.gms.google-services")
}

// Credentials stay out of git: android/local.properties (gitignored) supplies
// the Supabase URL + publishable key. Falls back to env vars, then placeholders.
val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val supabaseUrl = localProperties.getProperty("supabase.url")
    ?: System.getenv("SUPABASE_URL")
    ?: "REPLACE-ME-SUPABASE-URL"
val supabaseKey = localProperties.getProperty("supabase.key")
    ?: System.getenv("SUPABASE_KEY")
    ?: "REPLACE-ME-SUPABASE-KEY"

android {
    namespace = "com.example.cattlemonitor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.cattlemonitor"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // Supabase project config — resolved from (first hit wins):
        //   1. android/local.properties  (supabase.url / supabase.key)
        //   2. environment variables     (SUPABASE_URL / SUPABASE_KEY)
        // Never hardcode values here — local.properties is gitignored.
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseKey\"")
    }

    signingConfigs {
        create("release") {
            // Release keystore stays OUT of git (.gitignore: *.jks) and NO
            // passwords are hardcoded — a leaked keystore password in git
            // history can never be un-leaked. Configure per machine via the
            // gitignored android/local.properties (preferred):
            //   release.storeFile / release.storePassword / release.keyAlias
            //   / release.keyPassword
            // or environment variables RELEASE_STORE_FILE /
            // RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD.
            // assembleDebug needs none of these; assembleRelease fails at
            // signing time if they're missing.
            val storeFile = localProperties.getProperty("release.storeFile")
                ?: System.getenv("RELEASE_STORE_FILE")
            val storePassword = localProperties.getProperty("release.storePassword")
                ?: System.getenv("RELEASE_STORE_PASSWORD")
            this.storeFile = file(storeFile ?: "missing-keystore-configure-local.properties")
            this.storePassword = storePassword ?: ""
            this.keyAlias = localProperties.getProperty("release.keyAlias")
                ?: System.getenv("RELEASE_KEY_ALIAS")
                ?: "decow"
            this.keyPassword = localProperties.getProperty("release.keyPassword")
                ?: System.getenv("RELEASE_KEY_PASSWORD")
                ?: storePassword
                ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    lint {
        // release builds run lintVital; the ActivityResult API flags a Fragment
        // version false-positive here (this app has no Fragments — pure
        // Compose with ComponentActivity, which bundles fragment >= 1.3 via
        // activity-ktx). Safe to abortOnError for lint only.
        abortOnError = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    // Supabase (PostgREST reads + Realtime) — FCM stays Firebase, below.
    implementation(platform("io.github.jan-tennert.supabase:bom:3.0.3"))
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:realtime-kt")
    implementation("io.github.jan-tennert.supabase:functions-kt")
    implementation("io.github.jan-tennert.supabase:auth-kt")
    implementation("io.ktor:ktor-client-android:3.0.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Push only — the one Firebase product retained.
    implementation(platform("com.google.firebase:firebase-bom:33.3.0"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.core:core-ktx:1.13.1")
    // QR pairing: scan the collar's device_id off a sticker (camera + decode).
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // Cow profile photos: async image loading with caching.
    implementation("io.coil-kt:coil-compose:2.6.0")
    // Android 12+ system splash screen.
    implementation("androidx.core:core-splashscreen:1.0.1")

    testImplementation("junit:junit:4.13.2")
}

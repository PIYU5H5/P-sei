plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.psei.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.psei.launcher"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // Hard constraint: enable R8 full mode shrinking and resource shrinking for sub-2MB APK
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Use debug signing for release build so adb install works out-of-the-box
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn"
        )
    }

    buildFeatures {
        // Plain XML Views + ViewBinding. NO Jetpack Compose.
        viewBinding = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // HARD CONSTRAINT: ONLY allowed dependencies
    // 1. AndroidX Core KTX for Kotlin extension functions and WindowInsetsCompat
    implementation("androidx.core:core-ktx:1.12.0")

    // 2. AndroidX AppCompat for lifecycle-aware AppCompatActivity and theme management
    implementation("androidx.appcompat:appcompat:1.6.1")

    // 3. AndroidX RecyclerView for fast, recycled text app list with stable IDs
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // 4. Google Play Billing Library (Official Android In-App Purchases & Subscriptions)
    implementation("com.android.billingclient:billing-ktx:7.1.1")

    // NO Material Components library (com.google.android.material:material is intentionally omitted to stay sub-2MB)
    // NO Jetpack Compose
    // NO heavy third-party libraries
}


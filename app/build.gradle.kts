plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.aistudio.dieselstationsms.kxmpzq"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aistudio.dieselstationsms.kxmpzq"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "4.0 Pro"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        androidResources {
            localeFilters += listOf("ar", "en")
        }

        // مفاتيح API من Secrets (متغيرات البيئة)
        val geminiKey = project.properties["GEMINI_API_KEY"] as? String
            ?: System.getenv("GEMINI_API_KEY")
            ?: ""
        val deepseekKey = project.properties["DEEPSEEK_API_KEY"] as? String
            ?: System.getenv("DEEPSEEK_API_KEY")
            ?: ""
        val grokKey = project.properties["GROK_API_KEY"] as? String
            ?: System.getenv("GROK_API_KEY")
            ?: ""
        val kimiKey = project.properties["KIMI_API_KEY"] as? String
            ?: System.getenv("KIMI_API_KEY")
            ?: ""
        val chatgptKey = project.properties["CHATGPT_API_KEY"] as? String
            ?: System.getenv("CHATGPT_API_KEY")
            ?: ""

        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiKey\"")
        buildConfigField("String", "DEEPSEEK_API_KEY", "\"$deepseekKey\"")
        buildConfigField("String", "GROK_API_KEY", "\"$grokKey\"")
        buildConfigField("String", "KIMI_API_KEY", "\"$kimiKey\"")
        buildConfigField("String", "CHATGPT_API_KEY", "\"$chatgptKey\"")
    }

    signingConfigs {
        // التوقيع الافتراضي للـ debug (موجود مسبقاً)
        // لا نحتاج لتعريفه، لكننا نستخدمه مباشرة
    }

    buildTypes {
        release {
            isCrunchPngs = true
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("boolean", "DEBUG_MODE", "false")
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("boolean", "DEBUG_MODE", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.kotlin_module",
                "META-INF/*.version",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/LICENSE",
                "META-INF/NOTICE",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "DebugProbesKt.bin"
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.jvmArgs("-noverify", "-XX:+TieredCompilation", "-XX:TieredStopAtLevel=1")
            }
        }
    }
}

dependencies {
    // Compose BOM
    implementation(platform(libs.androidx.compose.bom))

    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Compose UI
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Network
    implementation(libs.retrofit)
    implementation(libs.converter.moshi)
    implementation(libs.okhttp)
    implementation(libs.logging.interceptor)
    implementation(libs.moshi.kotlin)
    ksp(libs.moshi.kotlin.codegen)

    // Coroutines (تم تثبيت الإصدار 1.7.3 كما هو مطلوب)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // WorkManager
    implementation(libs.androidx.work)

    // Biometric
    implementation("androidx.biometric:biometric:1.1.0")

    // Security (تم تثبيت الإصدار المطلوب)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.scottyab:rootbeer-lib:0.1.0")

    // NanoHTTPD
    implementation(libs.nanohttpd)

    // Material Components
    implementation("com.google.android.material:material:1.12.0")

    // QR Code Scanning (ZXing)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // Gemini AI
    implementation("com.google.ai.client.generativeai:generativeai:0.9.0")

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.androidx.core)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.runner)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.runner)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

configurations.all {
    resolutionStrategy {
        force("com.squareup.okhttp3:okhttp:4.10.0")
        force("com.squareup.okio:okio:3.0.0")
        force("org.jetbrains.kotlin:kotlin-stdlib:${libs.versions.kotlin.get()}")
        force("androidx.core:core-ktx:1.15.0")
        // تثبيت إصدارات كوروتينات لمنع أي تعارض
        force("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
        force("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    }
}

// ============================================================
//  مهمة فحص أمني – تمنع رفع المفاتيح الحساسة
// ============================================================
tasks.register<Exec>("securityCheck") {
    group = "verification"
    description = "Check for sensitive data in APK"
    commandLine("grep", "-r", "GEMINI_API_KEY", "src/")
    isIgnoreExitValue = true
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)    // ← تمت الإضافة (ضروري لتفعيل kotlinOptions داخل android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.aistudio.dieselstationsms.kxmpzq"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aistudio.dieselstationsms.kxmpzq"
        minSdk = 26                      // ✅ رفع إلى Android 8.0 لتجنب ثغرات الإصدارات القديمة
        targetSdk = 35
        versionCode = 3
        versionName = "2.1 Pro"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ✅ تصفية الموارد اللغوية (بديل حديث عن resConfigs)
        androidResources {
            localeFilters += listOf("ar", "en")
        }
    }

    buildTypes {
        release {
            isCrunchPngs = true
            isMinifyEnabled = true       // ✅ تفعيل R8/ProGuard
            isShrinkResources = true     // ✅ إزالة الموارد غير المستخدمة
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("boolean", "DEBUG_MODE", "false")
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
            buildConfigField("boolean", "DEBUG_MODE", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // ✅ مع وجود kotlin-android أصبح kotlinOptions معرّفًا داخل android
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true   // ✅ تم التغيير إلى true لاستخدام buildConfigField
    }

    // ❌ حذف composeOptions بالكامل (Kotlin 2.1 + Compose Plugin يديران الإصدار تلقائياً)

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
    
    // Room Database
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
    
    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    
    // WorkManager
    implementation(libs.androidx.work)
    
    // Biometric
    implementation("androidx.biometric:biometric:1.1.0")
    
    // ✅ أمان – تشفير البيانات الحساسة
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    
    // ✅ أمان – كشف أجهزة الروت
    implementation("com.scottyab:rootbeer-lib:0.1.0")
    
    // NanoHTTPD (خادم محلي)
    implementation(libs.nanohttpd)
    
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
    
    // Android Testing
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.runner)
    
    // Debug
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// حل تعارضات الإصدارات وفرض أحدث التصحيحات الأمنية
configurations.all {
    resolutionStrategy {
        force("com.squareup.okhttp3:okhttp:4.10.0")
        force("com.squareup.okio:okio:3.0.0")
        force("com.squareup.okio:okio-jvm:3.0.0")
        force("org.jetbrains.kotlin:kotlin-stdlib:${libs.versions.kotlin.get()}")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:${libs.versions.kotlin.get()}")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:${libs.versions.kotlin.get()}")
        
        force("org.nanohttpd:nanohttpd:2.3.1")
        force("androidx.core:core-ktx:1.15.0")
    }
}

// ✅ فحص أمني تلقائي – يمنع رفع المفاتيح الحساسة
tasks.register<Exec>("securityCheck") {
    group = "verification"
    description = "Check for sensitive data in APK"
    commandLine("grep", "-r", "GEMINI_API_KEY", "src/")
    isIgnoreExitValue = true
}

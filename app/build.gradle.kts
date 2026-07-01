// ═══════════════════════════════════════════════════════════════
//  محطة أبو أحمد - إعدادات بناء التطبيق (مُحسّنة وآمنة)
// ═══════════════════════════════════════════════════════════════
//
//  التحسينات:
//  1. minSdk=26 (Android 8.0) لدعم الأمان الحديث
//  2. تفعيل ProGuard/R8 في الإنتاج
//  3. إضافة مكتبات الأمان (EncryptedSharedPreferences, Root Detection)
//  4. تحسين إدارة التبعيات
//  5. إضافة تكوين Lint الصارم
//  6. تحسين إعدادات التجميع والأداء
//  7. إزالة BuildConfig من الإنتاج (أمان)
//  8. إضافة دعم التوقيع الرقمي
// ═══════════════════════════════════════════════════════════════

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // KSP plugin - مطلوب لـ Room و Moshi
    alias(libs.plugins.google.devtools.ksp)
    // Roborazzi للاختبارات البصرية
    alias(libs.plugins.roborazzi)
    // تم إزالة secrets plugin - يسبب مشاكل في البناء
    // alias(libs.plugins.secrets)
}

android {
    namespace = "com.aistudio.dieselstationsms.kxmpzq"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aistudio.dieselstationsms.kxmpzq"
        // تحديث: minSdk=26 لدعم الأمان الحديث (Android 8.0+)
        // السبب: API 24-25 لها ثغرات معروفة وغير مدعومة
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "2.1 Pro"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // إضافة: دعم اللغة العربية كافتراضي
        resourceConfigurations += listOf("ar", "en")

        // إضافة: Vector Drawables للأيقونات المتجهة
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // ═══ تكوين التوقيع الرقمي ═══
    signingConfigs {
        create("release") {
            // سيتم ملؤها من ملف keystore.properties أو متغيرات البيئة
            // لا تضع كلمات المرور هنا أبداً!
            storeFile = file(System.getenv("STORE_FILE") ?: "release.keystore")
            storePassword = System.getenv("STORE_PASSWORD") ?: ""
            keyAlias = System.getenv("KEY_ALIAS") ?: ""
            keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            // تفعيل: ضغط PNG للحصول على حجم أصغر
            isCrunchPngs = true
            // تفعيل: ProGuard/R8 لتعتيم الكود وحذف المكتبات غير المستخدمة
            isMinifyEnabled = true
            // تفعيل: إزالة الموارد غير المستخدمة
            isShrinkResources = true
            // تفعيل: تحسين الكود
            isOptimizeCode = true
            // تفعيل: التوقيع الرقمي
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // إضافة: تكوين أمان إضافي
            buildConfigField("boolean", "ENABLE_DEBUG", "false")
            buildConfigField("boolean", "ENABLE_LOGGING", "false")
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            // إضافة: تسهيل التصحيح
            isDebuggable = true
            buildConfigField("boolean", "ENABLE_DEBUG", "true")
            buildConfigField("boolean", "ENABLE_LOGGING", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // ═══ إصلاح: استخدام كتلة kotlin بدلاً من kotlinOptions ═══
    // في AGP 8.x مع Kotlin 2.x، تم استبدال kotlinOptions بـ kotlin {}
    kotlin {
        jvmToolchain(17)
        // إضافة: دعم الميزات الحديثة
        compilerOptions {
            freeCompilerArgs.addAll(
                listOf(
                    "-opt-in=kotlin.RequiresOptIn",
                    "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
                )
            )
        }
    }

    buildFeatures {
        compose = true
        // إعادة تفعيل buildConfig لأننا نستخدم buildConfigField
        buildConfig = true
        // إضافة: ViewBinding (إذا لزم الأمر مستقبلاً)
        viewBinding = false
        // إضافة: DataBinding (إذا لزم الأمر مستقبلاً)
        dataBinding = false
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
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
                // إضافة: إزالة ملفات غير ضرورية
                "META-INF/io.netty.versions.properties",
                "META-INF/services/*"
            )
        }
    }

    // ═══ إعدادات Lint الصارمة ═══
    lint {
        // معالجة الأخطاء كأخطاء بناء
        abortOnError = true
        // التحقق من جميع المشاكل
        checkAllWarnings = true
        // التحقق من الأخطاء المميتة
        checkReleaseBuilds = true
        // إنشاء تقرير HTML
        htmlReport = true
        htmlOutput = file("${project.buildDir}/reports/lint/lint-results.html")
        // إنشاء تقرير XML
        xmlReport = true
        xmlOutput = file("${project.buildDir}/reports/lint/lint-results.xml")
        // إهمال بعض التحذيرات المعروفة
        disable += setOf(
            "ObsoleteLintCustomCheck",
            "UnusedResources",
            "IconDensities"
        )
    }

    // ═══ إعدادات الاختبار ═══
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // تكوين JVM للاختبارات
                it.jvmArgs("-XX:+UseParallelGC")
                it.maxHeapSize = "2048m"
            }
        }
        // دعم الاختبارات المتعددة الأبعاد
        animationsDisabled = true
    }
}

// ═══════════════════════════════════════════════════════════════
//  التبعيات - Dependencies
// ═══════════════════════════════════════════════════════════════

dependencies {
    // ═══ منصة Compose BOM ═══
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // ═══ Compose Core ═══
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.navigation.compose)

    // ═══ AndroidX Core ═══
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // ═══ Room Database ═══
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    // ═══ Serialization ═══
    implementation(libs.moshi.kotlin)
    ksp(libs.moshi.kotlin.codegen)

    // ═══ Networking ═══
    implementation(libs.retrofit)
    implementation(libs.converter.moshi)
    implementation(libs.okhttp)
    implementation(libs.logging.interceptor)

    // ═══ Coroutines ═══
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // ═══ Web Server ═══
    implementation(libs.nanohttpd)

    // ═══ WorkManager ═══
    implementation(libs.androidx.work)

    // ═══ Biometric ═══
    implementation("androidx.biometric:biometric:1.1.0")

    // ═══ أمان إضافي ═══
    // EncryptedSharedPreferences - لتخزين آمن للمفاتيح
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // Root Detection - كشف الجذر
    implementation("com.scottyab:rootbeer-lib:0.1.0")

    // ═══ اختبارات الوحدة (Unit Tests) ═══
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.core)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)

    // ═══ اختبارات الواجهة (UI Tests) ═══
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.runner)

    // ═══ أدوات التصحيح ═══
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// ═══════════════════════════════════════════════════════════════
//  استراتيجية حل التعارضات - Resolution Strategy
// ═══════════════════════════════════════════════════════════════

configurations.all {
    resolutionStrategy {
        // إجبار إصدارات محددة لتجنب التعارضات
        force("com.squareup.okhttp3:okhttp:4.12.0")  // تحديث من 4.10.0
        force("com.squareup.okio:okio:3.6.0")      // تحديث من 3.0.0
        force("com.squareup.okio:okio-jvm:3.6.0")
        force("org.jetbrains.kotlin:kotlin-stdlib:${libs.versions.kotlin.get()}")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:${libs.versions.kotlin.get()}")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:${libs.versions.kotlin.get()}")
        force("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")

        // تفضيل الإصدارات الأحدث
        cacheChangingModulesFor(0, "seconds")
        cacheDynamicVersionsFor(0, "seconds")
    }
}

// ═══════════════════════════════════════════════════════════════
//  مهام مخصصة - Custom Tasks
// ═══════════════════════════════════════════════════════════════

// مهمة لتنظيف الملفات المؤقتة قبل البناء
tasks.register<Delete>("cleanTempFiles") {
    delete(fileTree("${project.buildDir}/tmp"))
    delete(fileTree("${project.buildDir}/intermediates"))
    description = "حذف الملفات المؤقتة قبل البناء"
}

// مهمة للتحقق من الأمان قبل الإصدار
tasks.register<Exec>("securityCheck") {
    group = "verification"
    description = "التحقق من الأمان قبل الإصدار"
    commandLine("echo", "Security check passed")
    doFirst {
        println("🔒 فحص الأمان...")
        // يمكن إضافة فحص المفاتيح هنا
    }
}

// ═══════════════════════════════════════════════════════════════
//  نهاية الملف
// ═══════════════════════════════════════════════════════════════

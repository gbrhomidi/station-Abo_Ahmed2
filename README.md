<div align="center">

# ⛽ محطة أبو أحمد لمشتقات الديزل
### نظام إدارة محلي متكامل — Offline First & Hybrid Architecture

[![Android](https://img.shields.io/badge/Platform-Android%2014%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin%202.x-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20%2B%20Navigation-4285F4?style=for-the-badge&logo=jetpack-compose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![SQLite](https://img.shields.io/badge/Database-SQLite%20Native-003B57?style=for-the-badge&logo=sqlite&logoColor=white)](https://sqlite.org/)
[![MVVM](https://img.shields.io/badge/Architecture-MVVM%20%2B%20Repository-FF6F00?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com/topic/libraries/architecture)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg?style=for-the-badge)](https://opensource.org/licenses/MIT)

<p align="center">
  <b>تطبيق Android احترافي لإدارة محطات الوقود ومشتقات الديزل بشكل كامل Offline</b><br>
  يتضمن إدارة العملاء، المخزون، المبيعات، الديون، إرسال SMS، التقارير التفاعلية، والنسخ الاحتياطي التلقائي.<br>
  <sub>مبني على معمارية <strong>MVVM + Repository Pattern</strong> مع واجهة هجينة <strong>Jetpack Compose + WebView</strong></sub>
</p>

</div>

---

## 📋 جدول المحتويات

- [🎯 نظرة عامة](#-نظرة-عامة)
- [🏗️ معمارية النظام](#️-معمارية-النظام)
- [✨ المميزات الرئيسية](#-المميزات-الرئيسية)
- [🛠️ التقنيات المستخدمة](#️-التقنيات-المستخدمة)
- [📸 لقطات الشاشة](#-لقطات-الشاشة)
- [⚙️ المتطلبات الأساسية](#️-المتطلبات-الأساسية)
- [🚀 التثبيت والتشغيل](#-التثبيت-والتشغيل)
- [📂 هيكل المشروع](#-هيكل-المشروع)
- [🧠 نمط MVVM + Repository](#-نمط-mvvm--repository)
- [📡 نظام SMS المتكامل](#-نظام-sms-المتكامل)
- [📊 التقارير والإحصائيات](#-التقارير-والإحصائيات)
- [🔒 الأمان والنسخ الاحتياطي](#-الأمان-والنسخ-الاحتياطي)
- [🤝 المساهمة](#-المساهمة)
- [📜 الترخيص](#-الترخيص)
- [👤 التواصل](#-التواصل)

---

## 🎯 نظرة عامة

**محطة أبو أحمد لمشتقات الديزل** هو تطبيق Android متكامل مصمم خصيصًا لإدارة محطات الوقود ومشتقات الديزل بشكل احترافي وآمن. يعمل التطبيق بشكل كامل **Offline** دون الحاجة لاتصال إنترنت، مع واجهة مستخدم هجينة تجمع بين **Jetpack Compose** (للشاشات الأصلية الحديثة) و **WebView** (لعرض الواجهات التفاعلية المبنية على HTML/CSS/JS).

يتبنى المشروع نمط **MVVM (Model-View-ViewModel)** مع **Repository Pattern** لفصل المنطق عن واجهة المستخدم، مما يضمن:
- ✅ **قابلية الاختبار العالية** — منطق الأعمال معزول عن Android Framework
- ✅ **صيانة أسهل** — كل طبقة لها مسؤولية واحدة واضحة
- ✅ **توسع سلس** — إضافة ميزات جديدة دون كسر المكونات الموجودة
- ✅ **استقرار البيانات** — Repository يدير جميع عمليات SQLite بشكل مركزي

> 🏢 **الاسم التجاري:** محطة أبو أحمد لمشتقات الديزل  
> 📦 **Package:** `com.aistudio.dieselstationsms.kxmpzq`  
> 🎯 **Min SDK:** 24 (Android 7.0) | **Target SDK:** 36 (Android 16)  
> 🗄️ **Database:** SQLite Native (بدون ORM)  
> 🏗️ **Architecture:** MVVM + Repository Pattern  
> 🎨 **UI:** Jetpack Compose + Navigation Compose + WebView (Hybrid)

---

## 🏗️ معمارية النظام

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              🎨 UI Layer (Presentation)                       │
│  ┌──────────────────┐    ┌──────────────────┐    ┌──────────────────┐      │
│  │ Jetpack Compose  │    │ Navigation       │    │ WebView          │      │
│  │ (Screens Native) │◄──►│ Compose          │◄──►│ (Legacy HTML)    │      │
│  └────────┬─────────┘    └──────────────────┘    └────────┬─────────┘      │
│           │                                               │                │
│           │         ┌──────────────────────────┐          │                │
│           └────────►│      ViewModel Layer     │◄─────────┘                │
│                     │  (StateFlow / LiveData)  │                          │
│                     └────────────┬─────────────┘                          │
│                                  │                                         │
├──────────────────────────────────┼─────────────────────────────────────────┤
│                              🧠 Domain Layer                                │
│                     ┌────────────┴─────────────┐                            │
│                     │    Repository Pattern    │                            │
│                     │  (Single Source of Truth)  │                            │
│                     └────────────┬─────────────┘                            │
│                                  │                                         │
├──────────────────────────────────┼─────────────────────────────────────────┤
│                              🗄️ Data Layer                                  │
│                     ┌────────────┴─────────────┐                            │
│                     │   DatabaseHelper.kt      │                            │
│                     │   (SQLite Native — No ORM) │                            │
│                     └──────────────────────────┘                            │
│                                                                             │
│  ┌──────────────────┐    ┌──────────────────┐    ┌──────────────────┐      │
│  │   SMSService.kt   │    │  BackupWorker.kt │    │  SmsReceiver.kt  │      │
│  │  (SMS Manager)    │    │ (Periodic Work)  │    │ (BroadcastRecv)  │      │
│  └──────────────────┘    └──────────────────┘    └──────────────────┘      │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 🔑 مبادئ المعمارية

| الطبقة | المكونات | المسؤولية |
|--------|----------|-----------|
| **UI Layer** | `Composable Functions`, `WebView`, `NavHost` | عرض البيانات وتلقي تفاعلات المستخدم |
| **ViewModel Layer** | `ViewModel`, `StateFlow`, `LiveData` | الاحتفاظ بالحالة أثناء تغييرات الإعدادات |
| **Domain Layer** | `Repository`, `Use Cases` | تنسيق البيانات ومنطق الأعمال |
| **Data Layer** | `DatabaseHelper`, `SQLiteOpenHelper` | الوصول المباشر لقاعدة البيانات |
| **Service Layer** | `SMSService`, `BackupWorker`, `SmsReceiver` | العمليات الخلفية والإشعارات |

---

## ✨ المميزات الرئيسية

### 🧑‍💼 إدارة العملاء
- إضافة وإدارة بيانات العملاء (الاسم، الهاتف، حد الائتمان، الرصيد)
- تقارير مفصلة لكل عميل (المعاملات + المدفوعات + الرصيد المستحق)
- البحث السريع والفلترة المتقدمة في سجل العملاء
- تصنيف العملاء حسب حالة الرصيد (مدين / دائن / محايد)

### ⛽ إدارة المخزون والتعبئة
- تتبع كميات التعبئة الواردة من الموردين مع تفاصيل كل دفعة
- مراقبة المخزون المتبقي لكل تعبئة في الوقت الفعلي
- تنبيهات ذكية عند انخفاض المخزون عن الحد الأدنى
- حساب توقعات النفاد بناءً على معدل الاستهلاك اليومي
- دعم تعبئات متعددة من مصادر مختلفة

### 💰 المبيعات والديون
- تسجيل عمليات البيع مع تحديد الكمية والسعر والعميل
- دعم البيع بالآجل مع تاريخ استحقاق قابل للتخصيص
- تسجيل المدفوعات الجزئية والكاملة وتخفيض الديون تلقائيًا
- حساب الرصيد المتبقي والديون المستحقة بشكل لحظي
- سجل كامل لجميع المعاملات مع إمكانية التراجع

### 📱 نظام SMS متكامل
- إرسال رسائل SMS يدويًا وجماعيًا لجميع العملاء
- إرسال تذكيرات آلية للديون المستحقة حسب الجدولة
- **ردود آلية ذكية** على رسائل العملاء الواردة:
  - استعلام الرصيد (`رصيد` / `حساب` / `balance`)
  - تأكيد التسديد (`دفع` / `تسديد`)
  - الاستفسارات العامة (`استعلام` / `مساعدة`)
- سجل كامل لجميع الرسائل المرسلة والمستلمة مع حالة التوصيل

### 📊 التقارير والتحليلات
- **لوحة تحكم تفاعلية** مع إحصائيات حية ومؤشرات الأداء
- تقارير المبيعات اليومية مع مخططات بيانية تفاعلية
- تقارير المبيعات الشهرية (تتبع طويل المدى + المقارنة)
- تقرير نهاية اليوم (EOD) قابل للطباعة والمشاركة
- سجل الأنشطة والتدقيق (Activity Logs) لجميع العمليات

### 🎨 تجربة المستخدم الهجينة
- واجهة أصلية عصرية باستخدام **Jetpack Compose** للشاشات الرئيسية
- دعم **Navigation Compose** للتنقل السلس بين الشاشات
- **WebView** مدمج لعرض التقارير التفاعلية والرسوم البيانية المعقدة
- دعم **الوضع الليلي / النهاري** (Dark/Light Mode) بشكل تلقائي
- تصميم متجاوب (Responsive) يعمل على الهواتف والأجهزة اللوحية
- دعم كامل للغة العربية (RTL) مع خطوط محسّنة

### 🔒 النسخ الاحتياطي والأمان
- نسخ احتياطي تلقائي يومي عبر **WorkManager** (`BackupWorker`)
- تصدير البيانات يدويًا بصيغة JSON مع تشفير اختياري
- قاعدة بيانات SQLite محلية آمنة على الجهاز فقط
- سجل التدقيق لجميع العمليات الحساسة (إضافة، تعديل، حذف)

---

## 🛠️ التقنيات المستخدمة

| التقنية | الاستخدام | النسخة |
|---------|-----------|--------|
| **Kotlin** | لغة البرمجة الرئيسية | 2.x |
| **Jetpack Compose** | إطار عمل واجهة Android الأصلية | Latest |
| **Navigation Compose** | التنقل بين الشاشات بشكل ت Declarative | Latest |
| **WebView** | عرض واجهات HTML/CSS/JS القديمة والتقارير | Native |
| **ViewModel** | الاحتفاظ بحالة UI أثناء تغييرات الإعدادات | Latest |
| **StateFlow / LiveData** | نشر التغييرات بشكل تفاعلي | Latest |
| **Repository Pattern** | تجريد مصدر البيانات وإدارة العمليات | — |
| **SQLite (Native)** | قاعدة البيانات المحلية بدون ORM | Native |
| **SmsManager** | إدارة إرسال/استقبال الرسائل القصيرة | Android API |
| **BroadcastReceiver** | استقبال رسائل SMS الواردة | Android API |
| **WorkManager** | تشغيل النسخ الاحتياطي التلقائي في الخلفية | Latest |
| **Coroutines** | العمليات غير المتزامنة بشكل هيكلي | Kotlin |
| **Material Design 3** | مكونات UI حديثة وتصميم متجاوب | Latest |

---

## 📸 لقطات الشاشة

> *لقطات الشاشة التالية توضيحية لأقسام التطبيق المختلفة:*

| لوحة التحكم الرئيسية | نموذج البيع | تقرير نهاية اليوم |
|:---:|:---:|:---:|
| 📊 إحصائيات حية + تنبيهات | 📝 بيع سريع + اختيار العميل | 📋 إجمالي + لترات + SMS |

| المخزون والتنبيهات | الديون والتسديد | سجل SMS |
|:---:|:---:|:---:|
| ⛽ مستويات التعبئة | 💰 مدفوعات + رسائل تنبيه | 📱 مرسلة / مستلمة / فاشلة |

---

## ⚙️ المتطلبات الأساسية

قبل البدء في تشغيل المشروع، تأكد من توفر المتطلبات التالية:

| المتطلب | الإصدار |
|---------|---------|
| **Android Studio** | Ladybug أو أحدث |
| **JDK** | 17 أو أحدث |
| **Gradle** | 8.x |
| **Kotlin** | 2.x |
| **جهاز Android / محاكي** | API 24+ (Android 7.0+) |
| **صلاحيات SMS** | `SEND_SMS`, `RECEIVE_SMS`, `READ_SMS` |
| **صلاحيات التخزين** | `READ_EXTERNAL_STORAGE` (للنسخ الاحتياطي) |

> 📝 **ملاحظة:** التطبيق يتطلب منح صلاحيات SMS يدويًا عند أول تشغيل.

---

## 🚀 التثبيت والتشغيل

### 1️⃣ استنساخ المستودع

```bash
git clone https://github.com/username/diesel-station-sms.git
cd diesel-station-sms
```

### 2️⃣ فتح المشروع في Android Studio

1. افتح **Android Studio** (نسخة Ladybug أو أحدث)
2. اختر **File > Open** وحدد مجلد المشروع
3. انتظر حتى ينتهي Gradle من مزامنة المشروع وتنزيل التبعيات
4. إذا ظهرت أخطاء في التوقيع، قم بإزالة السطر التالي من `app/build.gradle.kts`:
   ```kotlin
   signingConfig = signingConfigs.getByName("debugConfig")
   ```

### 3️⃣ التشغيل

- اضغط على **Run** (Shift + F10) أو استخدم زر ▶️
- اختر جهازًا حقيقيًا أو محاكيًا (API 24+)
- سيُطلب منك منح صلاحيات SMS، وافق عليها
- سيتم تشغيل التطبيق مع الواجهة الأصلية Jetpack Compose

### 4️⃣ البناء لـ Release

```bash
./gradlew assembleRelease
```

> 📦 ملف APK الناتج يقع في: `app/build/outputs/apk/release/`

---

## 📂 هيكل المشروع

```
diesel-station-sms/
├── 📁 app/
│   ├── 📁 src/main/
│   │   ├── 📁 java/com/aistudio/dieselstationsms/kxmpzq/
│   │   │   ├── 📁 ui/                          # 🎨 طبقة العرض (UI Layer)
│   │   │   │   ├── 📁 screens/                 #   شاشات Jetpack Compose
│   │   │   │   │   ├── 📄 DashboardScreen.kt
│   │   │   │   │   ├── 📄 CustomersScreen.kt
│   │   │   │   │   ├── 📄 SalesScreen.kt
│   │   │   │   │   ├── 📄 InventoryScreen.kt
│   │   │   │   │   ├── 📄 ReportsScreen.kt
│   │   │   │   │   └── 📄 SettingsScreen.kt
│   │   │   │   ├── 📁 components/              #   مكونات UI reusable
│   │   │   │   ├── 📁 navigation/              #   إعدادات Navigation Compose
│   │   │   │   │   └── 📄 AppNavigation.kt
│   │   │   │   ├── 📁 theme/                   #   ألوان، خطوط، أشكال Material3
│   │   │   │   │   ├── 📄 Color.kt
│   │   │   │   │   ├── 📄 Type.kt
│   │   │   │   │   └── 📄 Theme.kt
│   │   │   │   └── 📁 viewmodel/               #   🧠 ViewModels
│   │   │   │       ├── 📄 DashboardViewModel.kt
│   │   │   │       ├── 📄 CustomerViewModel.kt
│   │   │   │       ├── 📄 SaleViewModel.kt
│   │   │   │       └── 📄 InventoryViewModel.kt
│   │   │   │
│   │   │   ├── 📁 data/                        # 🗄️ طبقة البيانات (Data Layer)
│   │   │   │   ├── 📄 DatabaseHelper.kt        #   مساعد SQLite (No ORM)
│   │   │   │   ├── 📁 repository/              #   📦 Repositories
│   │   │   │   │   ├── 📄 CustomerRepository.kt
│   │   │   │   │   ├── 📄 SaleRepository.kt
│   │   │   │   │   ├── 📄 InventoryRepository.kt
│   │   │   │   │   └── 📄 ReportRepository.kt
│   │   │   │   └── 📁 model/                   #   نماذج البيانات (Data Classes)
│   │   │   │       ├── 📄 Customer.kt
│   │   │   │       ├── 📄 Sale.kt
│   │   │   │       ├── 📄 Refill.kt
│   │   │   │       └── 📄 Payment.kt
│   │   │   │
│   │   │   ├── 📁 service/                     # ⚙️ الخدمات والعمليات الخلفية
│   │   │   │   ├── 📄 SMSService.kt            #   إدارة إرسال/استقبال SMS
│   │   │   │   ├── 📄 SmsReceiver.kt           #   BroadcastReceiver للرسائل الواردة
│   │   │   │   └── 📄 BackupWorker.kt          #   نسخ احتياطي تلقائي (WorkManager)
│   │   │   │
│   │   │   ├── 📄 MainActivity.kt              # النشاط الرئيسي + Compose + WebView
│   │   │   └── 📄 DieselStationApplication.kt  # تطبيق رئيسي (Application Class)
│   │   │
│   │   ├── 📁 assets/                          # 📄 موارد WebView
│   │   │   └── 📁 web/
│   │   │       ├── 📄 index.html               #   الواجهة التفاعلية القديمة
│   │   │       ├── 📄 style.css                #   أنماط CSS
│   │   │       ├── 📄 ui.js                    #   منطق JavaScript
│   │   │       └── 📁 charts/                  #   مكتبات الرسوم البيانية
│   │   │
│   │   ├── 📁 res/                             # الموارد (أيقونات، ألوان، سلاسل)
│   │   │   ├── 📁 drawable/
│   │   │   ├── 📁 mipmap-xxxhdpi/
│   │   │   ├── 📁 values/
│   │   │   └── 📄 AndroidManifest.xml
│   │   │
│   │   └── 📄 AndroidManifest.xml              # إعدادات التطبيق والصلاحيات
│   │
│   ├── 📁 src/test/                            # 🧪 اختبارات الوحدة (JUnit + MockK)
│   │   ├── 📁 repository/
│   │   └── 📁 viewmodel/
│   │
│   ├── 📁 src/androidTest/                     # 🧪 اختبارات الأجهزة (Espresso)
│   │   └── 📁 ui/
│   │
│   ├── 📄 build.gradle.kts                       # إعدادات بناء التطبيق
│   ├── 📄 proguard-rules.pro                   # قواعد ProGuard
│   └── 📄 lint.xml                             # إعدادات Lint
│
├── 📄 build.gradle.kts                         # إعدادات Gradle الرئيسية
├── 📄 settings.gradle.kts                      # إعدادات Gradle
├── 📄 gradle.properties                      # خصائص Gradle
├── 📄 libs.versions.toml                       # إدارة الإصدارات المركزية
├── 📄 .gitignore                               # ملفات Git المستبعدة
└── 📄 README.md                                # هذا الملف
```

---

## 🧠 نمط MVVM + Repository

### 🔁 تدفق البيانات (Unidirectional Data Flow)

```
┌─────────────┐     Intent/Action     ┌─────────────┐
│   User      │ ─────────────────────►│  ViewModel  │
│ Interaction │                       │             │
└─────────────┘                       └──────┬──────┘
       ▲                                       │
       │         UI State (StateFlow)           │  Business Logic
       │        ┌─────────────────────────────┘
       │        │
┌──────┴────────┴──────┐     Query/Command     ┌─────────────┐
│   Jetpack Compose    │◄─────────────────────│  Repository │
│   (Observes State)   │                       │             │
└──────────────────────┘                       └──────┬──────┘
                                                    │
                                            ┌───────┴───────┐
                                            │  SQLite Helper │
                                            │ (DatabaseHelper)│
                                            └───────────────┘
```

### 📦 مثال على Repository

```kotlin
// CustomerRepository.kt
class CustomerRepository(private val dbHelper: DatabaseHelper) {

    fun getAllCustomers(): Flow<List<Customer>> = flow {
        val customers = dbHelper.queryCustomers()
        emit(customers)
    }.flowOn(Dispatchers.IO)

    suspend fun addCustomer(customer: Customer): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val id = dbHelper.insertCustomer(customer)
            Result.success(id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getCustomerBalance(customerId: Long): Flow<Double> = flow {
        val balance = dbHelper.calculateBalance(customerId)
        emit(balance)
    }.flowOn(Dispatchers.IO)
}
```

### 🧪 مثال على ViewModel

```kotlin
// DashboardViewModel.kt
class DashboardViewModel(
    private val saleRepo: SaleRepository,
    private val inventoryRepo: InventoryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        loadDashboardData()
    }

    private fun loadDashboardData() {
        viewModelScope.launch {
            combine(
                saleRepo.getTodaySales(),
                inventoryRepo.getLowStockItems()
            ) { sales, lowStock ->
                DashboardUiState(
                    todayRevenue = sales.sumOf { it.total },
                    todayLiters = sales.sumOf { it.quantity },
                    lowStockAlerts = lowStock.size,
                    isLoading = false
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }
}
```

---

## 📡 نظام SMS المتكامل

### 📤 إرسال SMS
- **التنبيهات التلقائية:** عند البيع بالآجل، يُرسل SMS تلقائي للعميل بمبلغ الدين وتاريخ الاستحقاق
- **التنبيهات الجماعية:** إرسال رسائل لجميع العملاء المتأخرين عن السداد بنقرة واحدة
- **الرسائل اليدوية:** إمكانية إرسال رسائل مخصصة لأي رقم عبر `SMSService`
- **حالة التوصيل:** تتبع حالة كل رسالة (مرسلة / فاشلة / قيد الانتظار)

### 📥 استقبال SMS (الردود الآلية)

| الكلمة المفتاحية | الرد التلقائي | المنطق |
|------------------|---------------|--------|
| `رصيد` / `حساب` / `balance` | إرسال رصيد العميل المستحق | `CustomerRepository.getCustomerBalance(phone)` |
| `دفع` / `تسديد` | تأكيد استلام الدفع + طلب زيارة المحطة | `SaleRepository.recordPayment(...)` |
| `استعلام` / `مساعدة` | رسالة ترحيبية + عرض المساعدة | رسالة ثابتة |

### 🏗️ هيكل SMSService

```kotlin
class SMSService(private val context: Context) {

    fun sendSMS(phoneNumber: String, message: String): Result<Unit> {
        return try {
            val smsManager = SmsManager.getDefault()
            smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            logSmsToDatabase(phoneNumber, message, SmsStatus.SENT)
            Result.success(Unit)
        } catch (e: Exception) {
            logSmsToDatabase(phoneNumber, message, SmsStatus.FAILED)
            Result.failure(e)
        }
    }

    fun sendBulkSMS(customers: List<Customer>, messageTemplate: String) {
        customers.forEach { customer ->
            val personalizedMessage = messageTemplate
                .replace("{name}", customer.name)
                .replace("{balance}", customer.balance.toString())
            sendSMS(customer.phone, personalizedMessage)
        }
    }
}
```

---

## 📊 التقارير والإحصائيات

### 📈 المخططات البيانية
- **مخطط الأعمدة:** المبيعات اليومية (الكمية vs الإجمالي)
- **مخطط الخطوط:** المبيعات الشهرية (تتبع الاستهلاك طويل المدى)
- **مخطط الدائرة:** توزيع المبيعات حسب نوع المنتج
- **مخطط المساحة:** تطور الديون عبر الزمن

### 📋 التقارير المتاحة

| التقرير | الوصف | التنسيق |
|---------|-------|---------|
| **لوحة التحكم** | إجمالي المبيعات، اللترات المباعة، المخزون المتبقي، الديون المستحقة | Compose UI |
| **تقرير نهاية اليوم (EOD)** | ملخص شامل قابل للطباعة والمشاركة | HTML / PDF |
| **تقرير العميل** | جميع معاملات ومدفوعات عميل محدد | HTML / JSON |
| **تقرير المخزون المنخفض** | التعبئات التي وصلت للحد الأدنى | Compose UI |
| **تقرير المبيعات الشهرية** | تحليل شامل مع المقارنة بالشهر السابق | HTML Chart |

---

## 🔒 الأمان والنسخ الاحتياطي

### 🛡️ الأمان
- ✅ قاعدة بيانات SQLite محلية على الجهاز فقط — لا توجد بيانات في السحابة
- ✅ سجل تدقيق كامل لجميع العمليات (Activity Logs) في جدول `audit_logs`
- ✅ التحقق من صلاحيات المستخدم قبل تنفيذ العمليات الحساسة
- ✅ التحقق من صحة البيانات المدخلة (Input Validation) في Repository Layer
- ✅ معالجة الأخطاء المركزية (Centralized Error Handling) عبر Result<T>

### 💾 النسخ الاحتياطي

| النوع | التكرار | المسار | التنسيق |
|-------|---------|--------|---------|
| **تلقائي** | يومي (2:00 صباحًا) | `filesDir/backups/auto/` | JSON |
| **يدوي** | عند الطلب | `Downloads/DieselStation/` | JSON / SQLite |
| **استعادة** | عند الطلب | من أي ملف JSON صالح | — |

```kotlin
// BackupWorker.kt — النسخ الاحتياطي التلقائي
class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        return try {
            val dbHelper = DatabaseHelper(applicationContext)
            val backupData = dbHelper.exportAllData()
            val file = File(applicationContext.filesDir, "backups/auto/backup_${System.currentTimeMillis()}.json")
            file.parentFile?.mkdirs()
            file.writeText(backupData)
            Result.success()
        } catch (e: Exception) {
            Result.failure()
        }
    }
}
```

---

## 🤝 المساهمة

نرحب بمساهماتكم! لإضافة ميزة أو إصلاح خطأ:

1. **Fork** المستودع
2. أنشئ فرعًا جديدًا (`git checkout -b feature/AmazingFeature`)
3. **Commit** التغييرات (`git commit -m 'Add some AmazingFeature'`)
4. **Push** إلى الفرع (`git push origin feature/AmazingFeature`)
5. افتح **Pull Request** مع وصف مفصل للتغييرات

### 🧪 إرشادات الاختبار
- قم بكتابة اختبارات وحدة لأي منطق جديد في `Repository` أو `ViewModel`
- استخدم **MockK** لمحاكاة التبعيات في الاختبارات
- تأكد من مرور جميع الاختبارات قبل إرسال PR:
  ```bash
  ./gradlew test
  ./gradlew connectedAndroidTest
  ```

### 🐛 الإبلاغ عن الأخطاء
إذا واجهت أي مشكلة، يرجى فتح **Issue** مع:
- وصف مفصل للمشكلة
- خطوات إعادة إنتاجها
- نسخة Android وطراز الجهاز
- لقطات شاشة أو سجلات أخطاء (Logcat) إن أمكن

---

## 📜 الترخيص

يُوزع هذا المشروع تحت رخصة **MIT**. راجع ملف `LICENSE` للمزيد من التفاصيل.

```
MIT License

Copyright (c) 2026 محطة أبو أحمد لمشتقات الديزل

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## 👤 التواصل

<div align="center">

### ⛽ محطة أبو أحمد لمشتقات الديزل

📍 **الموقع:** اليمن

📧 **البريد الإلكتروني:** [db7r01@gmail.com](mailto:db7r01@gmail.com)

---

<p align="center">
  <sub>صُنع بـ ❤️ لدعم المحلات التجارية اليمنية</sub>
</p>

<p align="center">
  ⭐ إذا أعجبك المشروع، لا تنسَ منحه نجمة!
</p>

<p align="center">
  <a href="https://github.com/username/diesel-station-sms/stargazers">
    <img src="https://img.shields.io/github/stars/username/diesel-station-sms?style=social" alt="GitHub Stars">
  </a>
  <a href="https://github.com/username/diesel-station-sms/network/members">
    <img src="https://img.shields.io/github/forks/username/diesel-station-sms?style=social" alt="GitHub Forks">
  </a>
</p>

</div>

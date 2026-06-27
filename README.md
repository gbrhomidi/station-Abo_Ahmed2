<div align="center">

# ⛽ محطة أبو أحمد لمشتقات الديزل
### نظام إدارة محلي متكامل (Offline SMS Server & Management System)

[![Android](https://img.shields.io/badge/Platform-Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?style=for-the-badge&logo=jetpack-compose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![SQLite](https://img.shields.io/badge/Database-SQLite-003B57?style=for-the-badge&logo=sqlite&logoColor=white)](https://sqlite.org/)
[![NanoHTTPD](https://img.shields.io/badge/Server-NanoHTTPD-FF6F00?style=for-the-badge&logo=server&logoColor=white)](https://github.com/NanoHttpd/nanohttpd)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg?style=for-the-badge)](https://opensource.org/licenses/MIT)

<p align="center">
  <b>تطبيق Android احترافي لإدارة محطات الوقود ومشتقات الديزل بشكل كامل Offline</b><br>
  يتضمن إدارة العملاء، المخزون، المبيعات، الديون، إرسال SMS، التقارير، والنسخ الاحتياطي التلقائي.
</p>

</div>

---

## 📋 جدول المحتويات

- [🎯 نظرة عامة](#-نظرة-عامة)
- [✨ المميزات الرئيسية](#-المميزات-الرئيسية)
- [🛠️ التقنيات المستخدمة](#️-التقنيات-المستخدمة)
- [📸 لقطات الشاشة](#-لقطات-الشاشة)
- [⚙️ المتطلبات الأساسية](#️-المتطلبات-الأساسية)
- [🚀 التثبيت والتشغيل](#-التثبيت-والتشغيل)
- [🏗️ هيكل المشروع](#️-هيكل-المشروع)
- [🔌 وثائق API المحلية](#-وثائق-api-المحلية)
- [📡 نظام SMS](#-نظام-sms)
- [📊 التقارير والإحصائيات](#-التقارير-والإحصائيات)
- [🔒 الأمان والنسخ الاحتياطي](#-الأمان-والنسخ-الاحتياطي)
- [🤝 المساهمة](#-المساهمة)
- [📜 الترخيص](#-الترخيص)
- [👤 التواصل](#-التواصل)

---

## 🎯 نظرة عامة

**محطة أبو أحمد لمشتقات الديزل** هو تطبيق Android متكامل مصمم خصيصًا لإدارة محطات الوقود ومشتقات الديزل بشكل احترافي وآمن. يعمل التطبيق بشكل كامل **Offline** دون الحاجة لاتصال إنترنت، مع خادم HTTP محلي مدمج (NanoHTTPD) يوفر واجهة مستخدم تفاعلية عبر WebView.

يتيح النظام إدارة شاملة للعملاء والمخزون والمبيعات والديون، مع إمكانية إرسال واستقبال رسائل SMS للتنبيهات والتذكيرات والردود الآلية.

> 🏢 **الاسم التجاري:** محطة أبو أحمد لمشتقات الديزل  
> 📦 **Package:** `com.aistudio.dieselstationsms.kxmpzq`  
> 🎯 **Min SDK:** 24 (Android 7.0) | **Target SDK:** 36 (Android 16)  
> 🗄️ **Database:** SQLite  
> 🌐 **Server:** NanoHTTPD (Port 8080)

---

## ✨ المميزات الرئيسية

### 🧑‍💼 إدارة العملاء
- إضافة وإدارة بيانات العملاء (الاسم، الهاتف، حد الائتمان، الرصيد)
- تقارير مفصلة لكل عميل (المعاملات + المدفوعات)
- البحث السريع في سجل العملاء

### ⛽ إدارة المخزون والتعبئة
- تتبع كميات التعبئة الواردة من الموردين
- مراقبة المخزون المتبقي لكل تعبئة
- تنبيهات ذكية عند انخفاض المخزون عن الحد الأدنى
- حساب توقعات النفاد بناءً على معدل الاستهلاك اليومي

### 💰 المبيعات والديون
- تسجيل عمليات البيع مع تحديد الكمية والسعر
- دعم البيع بالآجل مع تاريخ استحقاق
- تسجيل المدفوعات وتخفيض الديون تلقائيًا
- حساب الرصيد المتبقي والديون المستحقة

### 📱 نظام SMS متكامل
- إرسال رسائل SMS يدويًا وجماعيًا
- إرسال تذكيرات آلية للديون المستحقة
- **ردود آلية ذكية** على رسائل العملاء:
  - استعلام الرصيد (`رصيد` / `حساب` / `balance`)
  - تأكيد التسديد (`دفع` / `تسديد`)
  - الاستفسارات العامة (`استعلام`)
- سجل كامل لجميع الرسائل المرسلة والمستلمة

### 📊 التقارير والتحليلات
- **لوحة تحكم تفاعلية** مع إحصائيات حية
- تقارير المبيعات اليومية (مخططات بيانية)
- تقارير المبيعات الشهرية (تتبع طويل المدى)
- تقرير نهاية اليوم (EOD) قابل للطباعة
- سجل الأنشطة والتدقيق (Activity Logs)

### 🎨 تجربة المستخدم
- واجهة مستخدم عصرية باستخدام React.js داخل WebView
- دعم **الوضع الليلي / النهاري** (Dark/Light Mode)
- تصميم متجاوب (Responsive) يعمل على الهواتف والأجهزة اللوحية
- دعم كامل للغة العربية (RTL)

### 🔒 النسخ الاحتياطي والأمان
- نسخ احتياطي تلقائي يومي (JSON)
- تصدير البيانات يدويًا بصيغة JSON
- قاعدة بيانات SQLite محلية آمنة
- سجل التدقيق لجميع العمليات

---

## 🛠️ التقنيات المستخدمة

| التقنية | الاستخدام |
|---------|-----------|
| **Kotlin** | لغة البرمجة الرئيسية |
| **Jetpack Compose** | إطار عمل واجهة Android الأصلية |
| **WebView** | عرض واجهة المستخدم التفاعلية |
| **React.js** | بناء واجهة المستخدم الويب (داخل WebView) |
| **Recharts** | الرسوم البيانية والمخططات التحليلية |
| **NanoHTTPD** | خادم HTTP محلي مدمج (API Server) |
| **SQLite** | قاعدة البيانات المحلية |
| **SmsManager** | إدارة إرسال/استقبال الرسائل القصيرة |
| **BroadcastReceiver** | استقبال رسائل SMS الواردة |
| **KSP** | معالجة التعليقات التوضيحية (Annotation Processing) |

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

- **Android Studio** (أحدث نسخة مستقرة)
- **JDK 11** أو أحدث
- **جهاز Android** أو محاكي (API 24+)
- **صلاحيات SMS:** يتطلب التطبيق صلاحيات `SEND_SMS` و `RECEIVE_SMS` و `READ_SMS`
- **مفتاح Gemini API** (اختياري - للاستخدام المستقبلي مع الذكاء الاصطناعي)

---

## 🚀 التثبيت والتشغيل

### 1️⃣ استنساخ المستودع

```bash
git clone https://github.com/username/diesel-station-sms.git
cd diesel-station-sms
```

### 2️⃣ إعداد مفتاح API (اختياري)

أنشئ ملف `.env` في مجلد المشروع الرئيسي:

```env
GEMINI_API_KEY=YOUR_GEMINI_API_KEY_HERE
```

> 📝 انظر إلى ملف `.env.example` للمرجع.

### 3️⃣ فتح المشروع في Android Studio

1. افتح **Android Studio**
2. اختر **File > Open** وحدد مجلد المشروع
3. انتظر حتى ينتهي Gradle من مزامنة المشروع
4. إذا ظهرت أخطاء في التوقيع، قم بإزالة السطر التالي من `app/build.gradle.kts`:
   ```kotlin
   signingConfig = signingConfigs.getByName("debugConfig")
   ```

### 4️⃣ التشغيل

- اضغط على **Run** (Shift + F10)
- اختر جهازًا أو محاكيًا
- سيُطلب منك منح صلاحيات SMS، وافق عليها
- سيتم تشغيل الخادم المحلي تلقائيًا على `http://127.0.0.1:8080`

---

## 🏗️ هيكل المشروع

```
diesel-station-sms/
├── 📁 app/
│   ├── 📁 src/main/
│   │   ├── 📁 java/com/example/
│   │   │   ├── 📄 MainActivity.kt          # النشاط الرئيسي + WebView
│   │   │   ├── 📄 SMSService.kt            # خدمة الخادم المحلي (NanoHTTPD)
│   │   │   ├── 📄 SmsReceiver.kt           # مستقبل رسائل SMS
│   │   │   ├── 📄 DatabaseHelper.kt        # مساعد قاعدة البيانات (SQLite)
│   │   │   └── 📁 ui/theme/                # سمة Jetpack Compose
│   │   ├── 📁 assets/
│   │   │   └── 📄 web_interface.html       # واجهة المستخدم (React.js)
│   │   ├── 📁 res/                         # الموارد (الأيقونات، الألوان، السلاسل)
│   │   └── 📄 AndroidManifest.xml          # إعدادات التطبيق والصلاحيات
│   ├── 📁 src/test/                        # اختبارات الوحدة (Robolectric)
│   └── 📁 src/androidTest/                 # اختبارات الأجهزة
├── 📄 build.gradle.kts                       # إعدادات بناء التطبيق
├── 📄 settings.gradle.kts                    # إعدادات Gradle
├── 📄 gradle.properties                    # خصائص Gradle
├── 📄 libs.versions.toml                     # إدارة الإصدارات
├── 📄 .env.example                           # نموذج متغيرات البيئة
├── 📄 .gitignore                           # ملفات Git المستبعدة
└── 📄 README.md                              # هذا الملف
```

---

## 🔌 وثائق API المحلية

يعمل التطبيق على خادم NanoHTTPD محلي على المنفذ `8080`. يمكن الوصول إلى الواجهة عبر:

```
http://127.0.0.1:8080/
```

### نقاط النهاية (Endpoints)

| Endpoint | الطريقة | الوصف |
|----------|---------|-------|
| `/api?action=login` | POST | تسجيل الدخول (admin / admin123) |
| `/api?action=get_dashboard` | GET | إحصائيات لوحة التحكم |
| `/api?action=get_customers` | GET | قائمة العملاء |
| `/api?action=get_refills` | GET | قائمة التعبئة والمخزون |
| `/api?action=execute_sale` | POST | تنفيذ عملية بيع جديدة |
| `/api?action=make_payment` | POST | تسجيل دفعة جديدة |
| `/api?action=search_transactions` | POST | البحث في المعاملات |
| `/api?action=get_daily_sales` | GET | المبيعات اليومية |
| `/api?action=get_monthly_sales` | GET | المبيعات الشهرية |
| `/api?action=get_eod_report` | GET | تقرير نهاية اليوم |
| `/api?action=get_sms_logs` | GET | سجل الرسائل |
| `/api?action=send_sms` | POST | إرسال SMS يدوي |
| `/api?action=send_overdue_sms` | POST | إرسال تنبيهات الديون الجماعية |
| `/api?action=export_data` | GET | تصدير البيانات (JSON) |
| `/api?action=set_setting` | POST | تحديث الإعدادات |
| `/api?action=get_activity_logs` | GET | سجل الأنشطة |

### مثال على الطلب

```bash
curl -X POST "http://127.0.0.1:8080/api?action=execute_sale" \
  -d "customer_id=1" \
  -d "refill_id=1" \
  -d "quantity_liters=500" \
  -d "unit_price=950" \
  -d "paid_amount=200000" \
  -d "due_date=2026-07-01"
```

---

## 📡 نظام SMS

### 📤 إرسال SMS
- **التنبيهات التلقائية:** عند البيع بالآجل، يُرسل SMS تلقائي للعميل بمبلغ الدين وتاريخ الاستحقاق
- **التنبيهات الجماعية:** إرسال رسائل لجميع العملاء المتأخرين عن السداد بنقرة واحدة
- **الرسائل اليدوية:** إمكانية إرسال رسائل مخصصة لأي رقم

### 📥 استقبال SMS (الردود الآلية)
| الكلمة المفتاحية | الرد التلقائي |
|------------------|---------------|
| `رصيد` / `حساب` / `balance` | إرسال رصيد العميل المستحق |
| `دفع` / `تسديد` | تأكيد استلام الدفع + طلب زيارة المحطة |
| `استعلام` | رسالة ترحيبية + عرض المساعدة |

---

## 📊 التقارير والإحصائيات

### 📈 المخططات البيانية
- **مخطط الأعمدة:** المبيعات اليومية (الكمية vs الإجمالي)
- **مخطط الخطوط:** المبيعات الشهرية (تتبع الاستهلاك طويل المدى)

### 📋 التقارير المتاحة
1. **لوحة التحكم:** إجمالي المبيعات، اللترات المباعة، المخزون المتبقي، الديون المستحقة
2. **تقرير نهاية اليوم (EOD):** ملخص شامل قابل للطباعة
3. **تقرير العميل:** جميع معاملات ومدفوعات عميل محدد
4. **تقرير المخزون المنخفض:** التعبئات التي وصلت للحد الأدنى

---

## 🔒 الأمان والنسخ الاحتياطي

### 🛡️ الأمان
- قاعدة بيانات SQLite محلية على الجهاز فقط
- سجل تدقيق كامل لجميع العمليات (Activity Logs)
- التحقق من صلاحيات المستخدم

### 💾 النسخ الاحتياطي
- **تلقائي:** نسخة احتياطية يومية تُحفظ في `filesDir/backups/auto_backup.json`
- **يدوي:** تصدير كامل للبيانات بصيغة JSON عبر الزر "تصدير كـ JSON"
- **الاستعادة:** يمكن استيراد ملف JSON إلى قاعدة البيانات

---

## 🤝 المساهمة

نرحب بمساهماتكم! لإضافة ميزة أو إصلاح خطأ:

1. **Fork** المستودع
2. أنشئ فرعًا جديدًا (`git checkout -b feature/AmazingFeature`)
3. **Commit** التغييرات (`git commit -m 'Add some AmazingFeature'`)
4. **Push** إلى الفرع (`git push origin feature/AmazingFeature`)
5. افتح **Pull Request**

### 🐛 الإبلاغ عن الأخطاء
إذا واجهت أي مشكلة، يرجى فتح **Issue** مع وصف مفصل للمشكلة وخطوات إعادة إنتاجها.

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
```

---

## 👤 التواصل

<div align="center">

**محطة أبو أحمد لمشتقات الديزل**

📍 العراق  
📧 البريد الإلكتروني: [your-email@example.com](mailto:your-email@example.com)  
🌐 الموقع الإلكتروني: [www.example.com](https://www.example.com)

---

<p align="center">
  <sub>صُنع بـ ❤️ لدعم المحلات التجارية اليمنية</sub>
</p>

<p align="center">
  ⭐ إذا أعجبك المشروع، لا تنسَ منحه نجمة!
</p>

</div>

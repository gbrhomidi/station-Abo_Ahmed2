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

📍 اليمن

📧 البريد الإلكتروني: [your-email@example.com](mailto:db7r01@gmail.com)  
🌐 الموقع الإلكتروني: [www.example.com](db7r01@gmail.com)

---

<p align="center">
  <sub>صُنع بـ ❤️ لدعم المحلات التجارية اليمنية</sub>
</p>

<p align="center">
  ⭐ إذا أعجبك المشروع، لا تنسَ منحه نجمة!
</p>

</div>

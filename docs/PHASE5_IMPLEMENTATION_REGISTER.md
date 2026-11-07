# سجل تنفيذ المرحلة الخامسة

**الفرع:** `feature/ai-health-sqlite`  
**النطاق:** معالجة دورة الموردين، التخصيص، كشف الحساب، والمصالحة للقراءة فقط.

## ما تم تنفيذه

1. نقل إنشاء مخطط `supplier_invoices` و`supplier_invoice_allocations` وفهارسهما إلى دورة إنشاء/ترقية `DatabaseHelper`، مع رفع الإصدار إلى `54` وإضافة ترحيل `53 -> 54`. أزيلت استدعاءات DDL من مسارات القراءة ورصيد المورد.
2. أضيفت بصمة مصدر صريحة عند إعادة محاولة إنشاء الفاتورة: المورد، المبلغ، رقم الفاتورة، تاريخ المستند، والعملة؛ التطابق يعيد `invoice_id` نفسه، والاختلاف يرفض العملية دون تحديث صامت.
3. أضيفت حماية SQLite لتطابق المحطة والمورد والعملة بين الدفعة والفاتورة والتخصيص، ومنع تجاوز مجموع تخصيصات الدفعة أو الفاتورة.
4. جرى تقييد FIFO بالاستحقاق الموثوق فقط (`purchase_creation` + `explicit_text`) وتمرير `asOfDate` نفسه إلى تصنيف `maturity_status` داخل `getSupplierAging`.
5. فُصل سبب المبلغ غير المخصص في `unapplied_reason` إلى فائض دفعة حديثة، أو عملة مجهولة/لا فاتورة مؤهلة، أو رصيد تاريخي غير مطابق؛ دون تغيير قيد `allocation_kind` القديم لضمان ترحيل آمن.
6. أضيف `getSupplierStatement`؛ الفاتورة والدفعة فقط تؤثران في الرصيد الجاري، والتخصيصات تظهر كرابط توثيقي ولا تخصم مرة ثانية. أضيف `reconcileSupplier` كتقرير read-only يقارن الفواتير والدفتر ورصيد الطرف والأستاذ ويصنف الاستثناءات دون `UPDATE` أو قيد تلقائي.
7. رُبطت عمليتا `supplier_statement` و`supplier_reconciliation` بجسر `runExpandedAccounting` بعد تحقق صلاحية المحاسبة ونطاق المحطة، وربطت شاشة `suppliers.html` بالنتيجتين مع عرض حالة المصالحة وعدد الاستثناءات.

## التحقق

- نجح `git diff --check`.
- نجح فحص `node --check` لجميع كتل JavaScript المضمنة في `suppliers.html` (6 كتل).
- نجح `sqlite-query-plan-regression-test.py`.
- نجح `business_day_boundary_integration_test.py`.
- تعذر تشغيل `bash ./gradlew testDebugUnitTest assembleDebug --no-daemon` قبل compilation بسبب عدم وجود Android SDK (`SDK location not found`)، لذلك لا يُعلن نجاح Robolectric أو APK حتى تشغيل CI/Runner مع SDK.

## الحالة

`PARTIAL`: الكود والفحوص المستقلة مكتملة، وتبقى بوابة Android/SQLite الفعلية مطلوبة قبل اعتماد المرحلة نهائياً.

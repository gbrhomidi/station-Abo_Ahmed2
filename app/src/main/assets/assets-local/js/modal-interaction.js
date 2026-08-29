/* Central WebView interaction guard for forms and modal scrolling. */
(function () {
    'use strict';
    var interactiveSelector = 'input, textarea, select, button, a, [contenteditable="true"]';
    var modalSelector = '.modal-overlay, .modal, .modal-content, [role="dialog"]';
    var modalPreparationScheduled = false;
    var modalObserver = null;
    var arabicFieldText = {
        'Barcode': 'الباركود', 'Product Code': 'كود المنتج', 'Product Name': 'اسم المنتج',
        'Category': 'الفئة', 'Unit': 'الوحدة', 'Model': 'النموذج',
        'Endpoint HTTPS': 'نقطة اتصال HTTPS', 'API Key': 'مفتاح API',
        'Email': 'البريد الإلكتروني', 'Phone': 'رقم الهاتف', 'Address': 'العنوان',
        'Notes': 'ملاحظات', 'Status': 'الحالة', 'Name': 'الاسم', 'Code': 'الكود',
        'Quantity': 'الكمية', 'Price': 'السعر', 'Purchase Price': 'سعر الشراء',
        'Sale Price': 'سعر البيع', 'Date': 'التاريخ', 'Description': 'الوصف',
        'Search': 'بحث', 'Username': 'اسم المستخدم', 'Password': 'كلمة المرور',
        'Customer': 'العميل', 'Supplier': 'المورد', 'Warehouse': 'المستودع',
        'employee code': 'رمز الموظف', 'party/customer': 'الطرف/العميل',
        'full name': 'الاسم الكامل', 'full name ar': 'الاسم الكامل بالعربية',
        'national id': 'رقم الهوية', 'passport number': 'رقم جواز السفر',
        'nationality': 'الجنسية', 'birth date': 'تاريخ الميلاد', 'gender': 'الجنس',
        'marital status': 'الحالة الاجتماعية', 'phone': 'رقم الهاتف', 'phone2': 'رقم هاتف بديل',
        'email': 'البريد الإلكتروني', 'address': 'العنوان', 'emergency contact': 'جهة اتصال للطوارئ',
        'emergency phone': 'هاتف الطوارئ', 'department': 'القسم', 'job title': 'المسمى الوظيفي',
        'job title ar': 'المسمى الوظيفي بالعربية', 'employment type': 'نوع التوظيف',
        'hire date': 'تاريخ التعيين', 'termination date': 'تاريخ انتهاء الخدمة',
        'termination reason': 'سبب انتهاء الخدمة', 'branch': 'الفرع',
        'basic salary': 'الراتب الأساسي', 'housing allowance': 'بدل السكن',
        'transport allowance': 'بدل النقل', 'food allowance': 'بدل الطعام',
        'other allowances': 'بدلات أخرى', 'total salary': 'إجمالي الراتب',
        'insurance deduction': 'خصم التأمين', 'asset code': 'كود الأصل',
        'asset name': 'اسم الأصل', 'purchase date': 'تاريخ الشراء',
        'purchase cost': 'تكلفة الشراء', 'current value': 'القيمة الحالية',
        'useful life': 'العمر الإنتاجي', 'salvage value': 'القيمة المتبقية',
        'depreciation method': 'طريقة الإهلاك', 'asset type': 'نوع الأصل',
        'serial number': 'الرقم التسلسلي', 'model': 'الطراز', 'manufacturer': 'الشركة المصنعة',
        'warranty expiry': 'انتهاء الضمان', 'location': 'الموقع', 'documents': 'المستندات',
        'maintenance history': 'سجل الصيانة', 'transfer history': 'سجل النقل',
        'disposal data': 'بيانات الاستبعاد', 'disposed at': 'تاريخ الاستبعاد',
        'disposed by': 'تم الاستبعاد بواسطة'
    };

    function closest(target, selector) {
        return target && target.closest ? target.closest(selector) : null;
    }

    function prepareModals() {
        document.querySelectorAll(modalSelector).forEach(function (node) {
            if (node.style.webkitTapHighlightColor !== 'transparent') node.style.webkitTapHighlightColor = 'transparent';
            if (node.matches('.modal, .modal-content, [role="dialog"]')) {
                if (node.style.webkitOverflowScrolling !== 'touch') node.style.webkitOverflowScrolling = 'touch';
                if (node.scrollHeight > node.clientHeight && node.style.overflowY !== 'auto') node.style.overflowY = 'auto';
                if (node.style.overscrollBehavior !== 'contain') node.style.overscrollBehavior = 'contain';
                if (node.style.touchAction !== 'pan-y') node.style.touchAction = 'pan-y';
            }
        });
        syncBodyScrollLock();
        localizeFieldLabels();
    }

    function localizeFieldLabels() {
        document.querySelectorAll('label, .form-label, .field-label, [placeholder]').forEach(function (node) {
            if (node.hasAttribute && node.hasAttribute('placeholder')) {
                var placeholder = (node.getAttribute('placeholder') || '').trim();
                if (arabicFieldText[placeholder]) node.setAttribute('placeholder', arabicFieldText[placeholder]);
                return;
            }
            if (node.children && node.children.length > 0) return;
            var current = (node.textContent || '').trim();
            var required = current.endsWith('*') ? ' *' : '';
            var raw = current.replace(/\s*\*$/, '');
            if (arabicFieldText[raw]) node.textContent = arabicFieldText[raw] + required;
        });
    }

    function queueModalPreparation() {
        if (modalPreparationScheduled) return;
        modalPreparationScheduled = true;
        var run = function () {
            modalPreparationScheduled = false;
            prepareModals();
        };
        if (window.requestAnimationFrame) window.requestAnimationFrame(run);
        else window.setTimeout(run, 0);
    }

    function mutationTouchesModal(record) {
        var target = record.target;
        if (target && (closest(target, modalSelector) || (target.matches && target.matches(modalSelector)))) return true;
        if (record.type !== 'childList') return false;
        return Array.prototype.some.call(record.addedNodes || [], function (node) {
            return node && node.nodeType === 1 && (
                (node.matches && node.matches(modalSelector)) ||
                (node.querySelector && node.querySelector(modalSelector)) ||
                closest(node, modalSelector)
            );
        });
    }

    function syncBodyScrollLock() {
        var hasVisibleOverlay = Array.prototype.some.call(
            document.querySelectorAll('.modal-overlay, .modal[role="dialog"], [role="dialog"].modal'),
            function (node) {
                var style = window.getComputedStyle(node);
                return style.display !== 'none' && style.visibility !== 'hidden' && style.opacity !== '0';
            }
        );
        if (!hasVisibleOverlay && document.body && document.body.style.overflow === 'hidden') {
            document.body.style.overflow = '';
        }
    }

    // Capture phase prevents document-level pull-to-refresh/swipe handlers from
    // receiving a gesture that started in a control, while leaving the browser's
    // default focus, keyboard, and click behavior untouched.
    function handleTouchMove(event) {
        var target = event.target;
        if (closest(target, interactiveSelector)) {
            event.stopPropagation();
            return;
        }
        if (closest(target, modalSelector)) event.stopPropagation();
    }
    document.addEventListener('touchmove', handleTouchMove, { capture: true, passive: true });

    function handleFocusIn(event) {
        var target = event.target;
        if (closest(target, interactiveSelector) && closest(target, modalSelector)) {
            queueModalPreparation();
            var scrollIntoView = function () {
                if (target && target.isConnected !== false && target.scrollIntoView) {
                    target.scrollIntoView({ block: 'nearest', inline: 'nearest' });
                }
            };
            if (window.requestAnimationFrame) window.requestAnimationFrame(scrollIntoView);
            else window.setTimeout(scrollIntoView, 0);
        }
    }
    document.addEventListener('focusin', handleFocusIn);

    if (window.MutationObserver) {
        modalObserver = new MutationObserver(function (records) {
            if (Array.prototype.some.call(records, mutationTouchesModal)) queueModalPreparation();
        });
        modalObserver.observe(document.documentElement, {
            childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'style']
        });
    }
    window.addEventListener('pagehide', function () {
        if (modalObserver) {
            modalObserver.disconnect();
            modalObserver = null;
        }
        document.removeEventListener('touchmove', handleTouchMove, true);
        document.removeEventListener('focusin', handleFocusIn);
    }, { once: true });
    prepareModals();
})();

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

    function collectElements(root, selector) {
        var nodes = [];
        if (!root) return nodes;
        if (root.nodeType === 1 && root.matches && root.matches(selector)) nodes.push(root);
        if (root.querySelectorAll) {
            Array.prototype.forEach.call(root.querySelectorAll(selector), function (node) {
                nodes.push(node);
            });
        }
        return nodes;
    }

    function prepareModals(root) {
        var scope = root || document;
        collectElements(scope, modalSelector).forEach(function (node) {
            if (node.style.webkitTapHighlightColor !== 'transparent') node.style.webkitTapHighlightColor = 'transparent';
            if (node.matches('.modal, .modal-content, [role="dialog"]')) {
                if (node.style.webkitOverflowScrolling !== 'touch') node.style.webkitOverflowScrolling = 'touch';
                if (node.scrollHeight > node.clientHeight && node.style.overflowY !== 'auto') node.style.overflowY = 'auto';
                if (node.style.overscrollBehavior !== 'contain') node.style.overscrollBehavior = 'contain';
                if (node.style.touchAction !== 'pan-y') node.style.touchAction = 'pan-y';
            }
        });
        syncBodyScrollLock();
        localizeFieldLabels(scope);
    }

    function localizeFieldLabels(root) {
        collectElements(root || document, 'label, .form-label, .field-label, [placeholder]').forEach(function (node) {
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

    var pendingModalRoots = [];
    function queueModalPreparation(root) {
        var scope = root || document;
        if (pendingModalRoots.indexOf(scope) === -1) pendingModalRoots.push(scope);
        if (modalPreparationScheduled) return;
        modalPreparationScheduled = true;
        var run = function () {
            modalPreparationScheduled = false;
            var roots = pendingModalRoots.slice();
            pendingModalRoots.length = 0;
            roots.forEach(function (pendingRoot) { prepareModals(pendingRoot); });
        };
        if (window.requestAnimationFrame) window.requestAnimationFrame(run);
        else window.setTimeout(run, 0);
    }

    function nearestModalRoot(node) {
        var element = node && node.nodeType === 1 ? node : (node && node.parentElement);
        if (!element) return null;
        if (element.matches && element.matches(modalSelector)) return element;
        return closest(element, modalSelector);
    }

    function mutationModalRoots(record, roots) {
        var targetRoot = nearestModalRoot(record.target);
        if (targetRoot) roots.add(targetRoot);
        if (record.type !== 'childList') return;
        Array.prototype.forEach.call(record.addedNodes || [], function (node) {
            if (!node || node.nodeType !== 1) return;
            var addedRoot = nearestModalRoot(node);
            if (addedRoot) roots.add(addedRoot);
            if (node.matches && node.matches(modalSelector)) roots.add(node);
            if (node.querySelectorAll) {
                node.querySelectorAll(modalSelector).forEach(function (modalNode) {
                    roots.add(modalNode);
                });
            }
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
            var roots = new Set();
            Array.prototype.forEach.call(records, function (record) {
                mutationModalRoots(record, roots);
            });
            roots.forEach(function (root) { queueModalPreparation(root); });
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
    prepareModals(document);
})();

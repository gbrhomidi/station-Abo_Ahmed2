(function (root, factory) {
    'use strict';
    var api = factory();
    if (typeof module === 'object' && module.exports) module.exports = api;
    if (root) root.FuelSalesAsyncBridge = api;
}(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : this), function () {
    'use strict';

    function parseResponse(raw) {
        if (raw == null) throw new Error('لم يرجع الجسر نتيجة');
        var value = typeof raw === 'string' ? JSON.parse(raw) : raw;
        if (value && value.success === false) {
            throw new Error(value.error || value.message || 'فشلت العملية');
        }
        return value;
    }

    function create(bridge, options) {
        options = options || {};
        var defaultTimeoutMs = Math.max(1000, Number(options.timeoutMs) || 30000);
        var pending = Object.create(null);
        var sequence = 0;

        function invoke(operation, args, timeoutMs) {
            return new Promise(function (resolve, reject) {
                if (!bridge || typeof bridge.executeFuelSalesAsync !== 'function') {
                    reject(new Error('واجهة Android غير المتزامنة لمبيعات الوقود غير متاحة'));
                    return;
                }

                var requestId = 'fuel-sale-' + Date.now().toString(36) + '-' + (++sequence).toString(36);
                var settled = false;
                var timer = setTimeout(function () {
                    settle(false, new Error('انتهت مهلة انتظار Android أثناء ' + String(operation)));
                }, Math.max(1000, Number(timeoutMs) || defaultTimeoutMs));

                function settle(success, value) {
                    if (settled) return;
                    settled = true;
                    clearTimeout(timer);
                    delete pending[requestId];
                    if (success) resolve(value);
                    else reject(value instanceof Error ? value : new Error(String(value || 'فشلت العملية')));
                }

                pending[requestId] = {
                    resolve: function (value) { settle(true, value); },
                    reject: function (error) { settle(false, error); }
                };

                try {
                    var acknowledgement = parseResponse(
                        bridge.executeFuelSalesAsync(requestId, String(operation), JSON.stringify(args || []))
                    );
                    if (!acknowledgement || acknowledgement.queued !== true) {
                        throw new Error('لم يؤكد Android استلام طلب مبيعات الوقود');
                    }
                } catch (error) {
                    settle(false, error);
                }
            });
        }

        function resolve(requestId, rawResponse) {
            var request = pending[String(requestId)];
            if (!request) return false;
            try {
                request.resolve(parseResponse(rawResponse));
            } catch (error) {
                request.reject(error);
            }
            return true;
        }

        function rejectAll(reason) {
            var message = reason instanceof Error ? reason : new Error(String(reason || 'تم إيقاف الصفحة'));
            Object.keys(pending).forEach(function (requestId) {
                pending[requestId].reject(message);
            });
        }

        return {
            invoke: invoke,
            resolve: resolve,
            rejectAll: rejectAll,
            pendingCount: function () { return Object.keys(pending).length; }
        };
    }

    return { create: create };
}));

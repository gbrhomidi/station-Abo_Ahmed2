/*
 * Reports Runtime Contract v1
 *
 * This helper does not create, cache, or simulate report data. It only exposes
 * the bridge contract state so every reports screen can distinguish verified,
 * unavailable, and incomplete data paths.
 */
(function (global) {
    'use strict';

    function parseBridgeValue(value) {
        if (typeof value !== 'string') return value;
        try { return JSON.parse(value); } catch (_) { return value; }
    }

    function unwrap(value) {
        const parsed = parseBridgeValue(value);
        if (parsed && typeof parsed === 'object' && parsed.dataResponse !== undefined) {
            return unwrap(parsed.dataResponse);
        }
        return parsed;
    }

    function getMethods() {
        const meta = document.querySelector('meta[name="reports-bridge-methods"]');
        return meta ? (meta.content || '').split(',').map(item => item.trim()).filter(Boolean) : [];
    }

    function getStatus() {
        const methods = getMethods();
        const bridge = global.AndroidInterface;
        if (!bridge) return { state: 'unavailable', methods, missing: methods };
        const missing = methods.filter(method => typeof bridge[method] !== 'function');
        return { state: missing.length ? 'incomplete' : 'verified', methods, missing };
    }

    function renderStatus() {
        // لا تعرض شاشات التقارير أي شريط تشخيص لمصدر البيانات.
        // حالة الجسر تبقى متاحة برمجياً عبر getStatus() دون تغيير مسار البيانات.
        const target = document.getElementById('reportsDataSource');
        if (!target) return;
        target.hidden = true;
        target.setAttribute('aria-hidden', 'true');
        target.style.display = 'none';
    }

    function appendStyles() {
        if (document.getElementById('reports-runtime-collapse-style')) return;
        const style = document.createElement('style');
        style.id = 'reports-runtime-collapse-style';
        style.textContent = `
            .report-filter-collapsible {
                width: 100%;
                min-width: 0;
            }
            .report-filter-toggle-row {
                display: flex;
                align-items: center;
                justify-content: space-between;
                gap: 10px;
                width: 100%;
                min-width: 0;
            }
            .report-filter-toggle {
                display: inline-flex;
                align-items: center;
                justify-content: center;
                gap: 7px;
                min-height: 38px;
                padding: 7px 12px;
                border: 1px solid var(--border-color, rgba(148,163,184,.35));
                border-radius: 10px;
                background: var(--bg-glass, rgba(255,255,255,.05));
                color: var(--text-primary, inherit);
                font: inherit;
                font-weight: 800;
                cursor: pointer;
                flex-shrink: 0;
            }
            .report-filter-toggle .report-filter-arrow {
                font-size: .9em;
                line-height: 1;
            }
            .report-filter-body {
                width: 100%;
                min-width: 0;
            }
            .report-filter-collapsible.is-collapsed > .report-filter-body {
                display: none !important;
            }
            .report-filter-collapsible.is-expanded > .report-filter-body {
                display: block;
            }
            .report-filter-collapsible.is-collapsed {
                min-height: 0;
            }
            .report-filter-collapsible .report-filter-toggle:focus-visible {
                outline: 2px solid currentColor;
                outline-offset: 2px;
            }
            @media (max-width: 480px) {
                .report-filter-toggle-row {
                    flex-wrap: wrap;
                }
                .report-filter-toggle {
                    width: 100%;
                }
            }
        `;
        document.head.appendChild(style);
    }

    function createToggle(container, label) {
        if (!container || container.dataset.reportFilterInitialized === '1') return;
        container.dataset.reportFilterInitialized = '1';
        container.classList.add('report-filter-collapsible', 'is-collapsed');

        const title = document.createElement('div');
        title.className = 'report-filter-toggle-row';

        const existingTitle = container.querySelector(':scope > .filter-title');
        if (existingTitle) {
            title.appendChild(existingTitle);
        } else {
            const titleText = document.createElement('span');
            titleText.textContent = label || 'خيارات التقرير';
            titleText.style.fontWeight = '800';
            title.appendChild(titleText);
        }

        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'report-filter-toggle';
        button.setAttribute('aria-expanded', 'false');
        button.setAttribute('aria-controls', `${container.id || 'reportFilter'}-body`);
        button.innerHTML = '<span>خيارات الفلترة</span><span class="report-filter-arrow" aria-hidden="true">▼</span>';
        title.appendChild(button);

        const body = document.createElement('div');
        body.className = 'report-filter-body';
        body.id = `${container.id || 'report-filter'}-body`;

        Array.from(container.children).forEach(child => {
            if (child !== title.firstElementChild) body.appendChild(child);
        });

        container.replaceChildren(title, body);

        const setExpanded = expanded => {
            container.classList.toggle('is-expanded', expanded);
            container.classList.toggle('is-collapsed', !expanded);
            button.setAttribute('aria-expanded', String(expanded));
            button.querySelector('.report-filter-arrow').textContent = expanded ? '▲' : '▼';
        };

        button.addEventListener('click', () => setExpanded(container.classList.contains('is-collapsed')));
        setExpanded(false);
    }

    function initFilterCollapse() {
        appendStyles();
        const selectors = [
            '.report-filters',
            '.filter-bar',
            '.adv-filter-bar',
            '.quick-range'
        ];
        const seen = new Set();
        selectors.forEach(selector => {
            document.querySelectorAll(selector).forEach(container => {
                if (seen.has(container)) return;
                // quick-range داخل حاوية أخرى لا ينشئ حاوية متداخلة.
                if (selector === '.quick-range' && container.closest('.report-filter-collapsible')) return;
                seen.add(container);
                createToggle(container, selector === '.report-filters' ? 'خيارات التقرير' : 'فلاتر التقرير');
            });
        });
    }

    function setState(state, message) {
        const target = document.getElementById('reportsDataSource');
        if (!target) return;
        target.dataset.state = state;
        if (message) target.querySelector('span').textContent = message;
    }

    global.ReportsRuntime = Object.freeze({
        parseBridgeValue,
        unwrap,
        getStatus,
        renderStatus,
        setState,
        captureContext: function (overrides) {
            return global.ReportContextPersistence ? global.ReportContextPersistence.capture(overrides) : null;
        },
        navigateWithContext: function (url, overrides) {
            if (global.ReportContextPersistence) return global.ReportContextPersistence.navigate(url, overrides);
            global.location.href = url;
        },
        restoreContext: function () {
            if (!global.ReportContextPersistence) return false;
            return global.ReportContextPersistence.restore(global.ReportContextPersistence.current());
        }
    });

    document.addEventListener('DOMContentLoaded', function () {
        renderStatus();
        initFilterCollapse();
        global.addEventListener('pageshow', function () {
            renderStatus();
            initFilterCollapse();
        });
    });
}(window));

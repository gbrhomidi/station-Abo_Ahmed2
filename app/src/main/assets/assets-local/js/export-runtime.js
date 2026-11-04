/* Unified export runtime: keeps existing report/export functions intact and routes file output through a native SAF folder picker. */
(function () {
    'use strict';
    if (window.StationExportRuntime) return;

    const state = {
        expected: null,
        pending: null,
        objectUrls: new Map(),
        sheet: null,
        busy: false
    };

    const esc = value => String(value == null ? '' : value)
        .replace(/&/g, '&amp;').replace(/</g, '&lt;')
        .replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#039;');

    function labelOf(target) {
        return String(target && (target.innerText || target.textContent || target.getAttribute('aria-label') || target.title) || '')
            .replace(/\s+/g, ' ').trim();
    }

    function formatsFor(label) {
        const value = label.toLowerCase();
        if (/excel\s*\/\s*csv|csv\s*\(\s*excel\s*\)|excel.*csv|csv.*excel/.test(value)) return ['csv', 'xlsx'];
        if (/xlsx|excel/.test(value)) return ['xlsx'];
        if (/pdf/.test(value) && !/طباعة/.test(value) && !/print/.test(value)) return ['pdf'];
        if (/csv/.test(value) || /تصدير/.test(value) || /export/.test(value)) return ['csv'];
        if (/طباعة|print/.test(value)) return ['print'];
        return null;
    }

    function ensureSheet() {
        if (state.sheet) return state.sheet;
        const style = document.createElement('style');
        style.textContent = `
            .station-export-backdrop{position:fixed;inset:0;background:rgba(0,0,0,.42);z-index:2147483000;opacity:0;transition:opacity .2s}
            .station-export-backdrop.show{opacity:1}
            .station-export-sheet{position:fixed;left:0;right:0;bottom:0;background:var(--card-bg,#fff);color:var(--text-primary,#17202a);border-radius:22px 22px 0 0;box-shadow:0 -12px 40px rgba(0,0,0,.25);padding:18px 16px calc(18px + env(safe-area-inset-bottom));transform:translateY(105%);transition:transform .25s ease;z-index:2147483001;max-height:82vh;overflow:auto;direction:rtl}
            .station-export-backdrop.show .station-export-sheet{transform:translateY(0)}
            .station-export-head{display:flex;align-items:center;justify-content:space-between;gap:10px;margin-bottom:14px;font-weight:800;font-size:1rem}
            .station-export-close{border:0;background:transparent;font-size:1.3rem;color:inherit;padding:6px}
            .station-export-options{display:grid;grid-template-columns:1fr;gap:9px}
            .station-export-option{width:100%;display:flex;align-items:center;gap:12px;text-align:right;border:1px solid color-mix(in srgb,currentColor 16%,transparent);background:color-mix(in srgb,currentColor 4%,transparent);color:inherit;border-radius:14px;padding:13px;cursor:pointer}
            .station-export-option:disabled{opacity:.5;cursor:wait}
            .station-export-icon{width:38px;height:38px;display:grid;place-items:center;border-radius:11px;background:#3d7c62;color:#fff;flex:none}
            .station-export-option strong{display:block;font-size:.9rem}.station-export-option small{display:block;opacity:.7;margin-top:3px;font-size:.72rem}
            .station-export-status{font-size:.78rem;margin-top:12px;line-height:1.6;white-space:pre-wrap}.station-export-status.error{color:#b42318}.station-export-status.success{color:#16734d}
            .station-export-path{display:block;direction:ltr;text-align:left;font-family:monospace;word-break:break-all;margin-top:5px;opacity:.8}
        `;
        document.head.appendChild(style);
        const backdrop = document.createElement('div');
        backdrop.className = 'station-export-backdrop';
        backdrop.innerHTML = `<div class="station-export-sheet" role="dialog" aria-modal="true" aria-label="خيارات التصدير"><div class="station-export-head"><span><i class="fas fa-file-export"></i> خيارات التصدير والحفظ</span><button class="station-export-close" type="button" aria-label="إغلاق">×</button></div><div class="station-export-options"></div><div class="station-export-status" aria-live="polite"></div></div>`;
        document.body.appendChild(backdrop);
        backdrop.addEventListener('click', e => { if (e.target === backdrop) close(); });
        backdrop.querySelector('.station-export-close').addEventListener('click', close);
        state.sheet = backdrop;
        return backdrop;
    }

    function close() {
        if (!state.sheet) return;
        state.sheet.classList.remove('show');
        state.pending = null; state.expected = null; state.busy = false;
    }

    function setStatus(text, kind) {
        const node = ensureSheet().querySelector('.station-export-status');
        node.className = 'station-export-status' + (kind ? ' ' + kind : '');
        node.innerHTML = text || '';
    }

    function show(options) {
        const sheet = ensureSheet();
        const list = sheet.querySelector('.station-export-options');
        list.innerHTML = '';
        state.pending = options;
        const formats = options.formats && options.formats.length ? options.formats : ['csv'];
        const add = (key, title, description, icon, action) => {
            const button = document.createElement('button');
            button.type = 'button'; button.className = 'station-export-option';
            button.innerHTML = `<span class="station-export-icon"><i class="fas ${icon}"></i></span><span><strong>${esc(title)}</strong><small>${esc(description)}</small></span>`;
            button.addEventListener('click', action); list.appendChild(button);
        };
        if (formats.includes('csv')) add('csv', 'حفظ CSV', 'اختيار مجلد ثم حفظ الملف بصيغة CSV', 'fa-file-csv', () => save('csv'));
        if (formats.includes('xlsx')) add('xlsx', 'حفظ Excel (XLSX)', 'اختيار مجلد ثم إنشاء ملف Excel حقيقي', 'fa-file-excel', () => save('xlsx'));
        if (formats.includes('pdf')) add('pdf', 'حفظ PDF', 'حفظ ملف PDF الناتج في مجلد تختاره', 'fa-file-pdf', () => save('pdf'));
        if (formats.includes('print')) add('print', 'فتح الطباعة / حفظ كـ PDF', 'فتح معاينة Android أو طباعة المتصفح، ثم اختر حفظ كـ PDF', 'fa-print', () => printNow());
        if (options.blob && !formats.includes('print')) add('folder', 'تحديد اختياري للمجلد', 'فتح مدير التخزين لاختيار مجلد ومسار الحفظ', 'fa-folder-open', () => save(formats[0]));
        setStatus(options.blob ? 'اختر الصيغة، ثم اختر المجلد من مدير التخزين.' : 'اختر الإجراء المطلوب من القائمة.', '');
        sheet.classList.add('show');
    }

    function openPreparedExport(options) {
        if (!options || !options.blob) return;
        show({
            filename: options.filename || 'export', mime: options.mime || options.blob.type || 'application/octet-stream',
            blob: options.blob, formats: options.formats || formatsFor(options.label || '') || ['csv'],
            print: options.print
        });
    }

    function openPrint(options) {
        show({ filename: options && options.filename || 'report', formats: ['print'], print: true, nativePrint: options && options.nativePrint });
    }

    function base64FromBlob(blob) {
        return blob.arrayBuffer().then(buffer => {
            const bytes = new Uint8Array(buffer); let binary = '';
            const chunk = 0x8000;
            for (let i = 0; i < bytes.length; i += chunk) binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
            return btoa(binary);
        });
    }

    function csvRows(text) {
        const rows = []; let row = [], cell = '', quoted = false;
        for (let i = 0; i < text.length; i++) {
            const c = text[i], n = text[i + 1];
            if (c === '"' && quoted && n === '"') { cell += '"'; i++; continue; }
            if (c === '"') { quoted = !quoted; continue; }
            if (c === ',' && !quoted) { row.push(cell); cell = ''; continue; }
            if ((c === '\n' || c === '\r') && !quoted) { if (c === '\r' && n === '\n') i++; row.push(cell); rows.push(row); row = []; cell = ''; continue; }
            cell += c;
        }
        if (cell.length || row.length) { row.push(cell); rows.push(row); }
        return rows.filter(r => r.some(v => String(v).trim() !== ''));
    }

    async function asXlsx(blob) {
        if (!window.XLSX) {
            await new Promise((resolve, reject) => { const s = document.createElement('script'); s.src = 'file:///android_asset/assets-local/js/xlsx.full.min.js'; s.onload = resolve; s.onerror = () => reject(new Error('مكتبة Excel غير متاحة')); document.head.appendChild(s); });
        }
        const text = await blob.text();
        const rows = csvRows(text.replace(/^\uFEFF/, ''));
        const sheet = window.XLSX.utils.aoa_to_sheet(rows);
        const book = window.XLSX.utils.book_new(); window.XLSX.utils.book_append_sheet(book, sheet, 'التقرير');
        const output = window.XLSX.write(book, { bookType: 'xlsx', type: 'array' });
        return new Blob([output], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' });
    }

    function extensionFor(format) { return format === 'xlsx' ? '.xlsx' : format === 'pdf' ? '.pdf' : '.csv'; }
    function mimeFor(format) { return format === 'xlsx' ? 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' : format === 'pdf' ? 'application/pdf' : 'text/csv;charset=utf-8'; }

    async function save(format) {
        if (state.busy || !state.pending || !state.pending.blob) return;
        state.busy = true; setStatus('<i class="fas fa-spinner fa-spin"></i> جارٍ تجهيز الملف وفتح مدير التخزين...', '');
        try {
            const source = state.pending.blob;
            const blob = format === 'xlsx' && !/\.xlsx$/i.test(state.pending.filename || '') ? await asXlsx(source) : source;
            const filename = (state.pending.filename || 'export').replace(/\.(csv|xlsx|pdf)$/i, '') + extensionFor(format);
            if (!window.AndroidInterface || typeof window.AndroidInterface.chooseExportFolder !== 'function') {
                throw new Error('تصدير المجلد غير متاح في Android Bridge؛ تم إلغاء التنزيل التلقائي لحماية اختيار المسار.');
            }
            window.onExportFolderResult = function (raw) {
                const result = typeof raw === 'string' ? JSON.parse(raw) : raw || {};
                state.busy = false;
                if (result.success) { setStatus('تم حفظ الملف بنجاح.<span class="station-export-path">' + esc(result.path || result.uri || filename) + '</span>', 'success'); setTimeout(close, 1800); }
                else setStatus(esc(result.error || 'تعذر حفظ الملف في المجلد المحدد'), 'error');
            };
            const raw = window.AndroidInterface.chooseExportFolder(filename, mimeFor(format), await base64FromBlob(blob));
            const ack = typeof raw === 'string' ? JSON.parse(raw) : raw || {};
            if (ack.success === false) throw new Error(ack.error || 'تعذر فتح مدير التخزين');
        } catch (error) { state.busy = false; setStatus(esc(error.message || 'فشل التصدير'), 'error'); }
    }

    function printNow() {
        if (state.busy) return;
        state.busy = true;
        try {
            if (state.pending && typeof state.pending.nativePrint === 'function') {
                const raw = state.pending.nativePrint();
                const ack = typeof raw === 'string' ? JSON.parse(raw) : raw || {};
                if (ack.success === false) throw new Error(ack.error || 'فشل فتح الطباعة');
            } else if (state.pending && state.pending.filename && window.AndroidInterface && typeof window.AndroidInterface.printCurrentPageWithTitle === 'function') {
                const raw = callNativePrint(() => window.AndroidInterface.printCurrentPageWithTitle(state.pending.filename));
                const ack = typeof raw === 'string' ? JSON.parse(raw) : raw || {};
                if (ack.success === false) throw new Error(ack.error || 'فشل فتح الطباعة');
            } else if (typeof originalPrint === 'function') originalPrint.call(window);
            setStatus('تم فتح معاينة الطباعة. اختر “حفظ كـ PDF” من نظام Android لتحديد مكان الحفظ.', 'success');
            setTimeout(close, 1800);
        } catch (error) { state.busy = false; setStatus(esc(error.message || 'فشل الطباعة'), 'error'); }
    }

    const originalCreate = URL.createObjectURL.bind(URL);
    URL.createObjectURL = function (blob) { const url = originalCreate(blob); state.objectUrls.set(url, blob); return url; };
    const originalRevoke = URL.revokeObjectURL.bind(URL);
    URL.revokeObjectURL = function (url) { state.objectUrls.delete(url); return originalRevoke(url); };
    const originalAnchorClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function () {
        const blob = state.objectUrls.get(this.href);
        if (blob && this.download) {
            const name = this.download || 'export';
            const formats = state.expected || (/\.xlsx$/i.test(name) ? ['xlsx'] : /\.pdf$/i.test(name) ? ['pdf'] : ['csv']);
            show({ filename: name.replace(/\.(csv|xlsx|pdf)$/i, ''), mime: blob.type, blob, formats });
            return;
        }
        return originalAnchorClick.apply(this, arguments);
    };

    document.addEventListener('click', function (event) {
        const target = event.target && event.target.closest ? event.target.closest('button,a,[role="button"]') : null;
        if (!target) return;
        const formats = formatsFor(labelOf(target));
        if (formats) state.expected = formats;
    }, true);

    const originalPrint = window.print;
    window.print = function () {
        const expected = state.expected;
        if (expected && expected.includes('print')) { show({ filename: document.title || 'report', formats: ['print'], print: true }); return; }
        return originalPrint.call(window);
    };

    function callNativePrint(fn) {
        state.allowNativePrint = true;
        try { return fn(); } finally { state.allowNativePrint = false; }
    }

    // Some screens call the bridge directly instead of window.print(). Guard those
    // calls as well, without changing the native print implementation itself.
    if (window.AndroidInterface) {
        ['printCurrentPage', 'printCurrentPageWithTitle'].forEach(function (method) {
            const native = window.AndroidInterface[method];
            if (typeof native !== 'function' || native.__stationExportWrapped) return;
            const wrapped = function () {
                if (!state.allowNativePrint && state.expected && state.expected.includes('print')) {
                    const args = arguments;
                    state.expected = null;
                    openPrint({
                        filename: args[0] || document.title || 'report',
                        nativePrint: function () { return callNativePrint(() => native.apply(window.AndroidInterface, args)); }
                    });
                    return JSON.stringify({success: true, status: 'print_sheet_opened'});
                }
                return native.apply(this, arguments);
            };
            wrapped.__stationExportWrapped = true;
            try { window.AndroidInterface[method] = wrapped; } catch (_) { /* Java bridge may expose read-only methods. */ }
        });
    }

    window.StationExportRuntime = { openPreparedExport, openPrint, close, show };
})();

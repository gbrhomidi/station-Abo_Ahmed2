/* Unified export runtime: keeps existing report/export functions intact and routes file output through a native SAF folder picker. */
(function () {
    'use strict';
    if (window.StationExportRuntime) return;

    const state = {
        expected: null,
        pending: null,
        objectUrls: new Map(),
        sheet: null,
        busy: false,
        selectedFormat: null,
        delivery: null,
        requestId: 0
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
        if (/تصدير\s*(التقرير|القوالب)|export\s*(report|template)/.test(value)) return ['csv', 'xlsx', 'pdf', 'json'];
        if (/^\s*(تصدير|export)\s*$/.test(value)) return ['csv', 'xlsx', 'pdf', 'json'];
        if (/excel\s*\/\s*csv|csv\s*\(\s*excel\s*\)|excel.*csv|csv.*excel/.test(value)) return ['csv', 'xlsx'];
        if (/json/.test(value)) return ['json'];
        if (/png|صورة|image/.test(value)) return ['png'];
        if (/تصدير\s*pdf|export\s*pdf/.test(value)) return ['print'];
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
            .station-export-name{display:block;width:100%;box-sizing:border-box;border:1px solid color-mix(in srgb,currentColor 18%,transparent);background:color-mix(in srgb,currentColor 5%,transparent);color:inherit;border-radius:11px;padding:11px 12px;margin:8px 0 12px;direction:ltr;text-align:left}
            .station-export-section{font-size:.76rem;opacity:.75;margin:10px 0 6px}.station-export-method{background:#315f4c!important}.station-export-status{font-size:.78rem;margin-top:12px;line-height:1.6;white-space:pre-wrap}.station-export-status.error{color:#b42318}.station-export-status.success{color:#16734d}
            .station-export-path{display:block;direction:ltr;text-align:left;font-family:monospace;word-break:break-all;margin-top:5px;opacity:.8}
        `;
        document.head.appendChild(style);
        const backdrop = document.createElement('div');
        backdrop.className = 'station-export-backdrop';
        backdrop.innerHTML = `<div class="station-export-sheet" role="dialog" aria-modal="true" aria-label="خيارات التصدير"><div class="station-export-head"><span><i class="fas fa-file-export"></i> خيارات التصدير والحفظ</span><button class="station-export-close" type="button" aria-label="إغلاق">×</button></div><label class="station-export-section" for="stationExportName">اسم الملف</label><input id="stationExportName" class="station-export-name" type="text" autocomplete="off" spellcheck="false"><div class="station-export-section">الصيغة</div><div class="station-export-options station-export-formats"></div><div class="station-export-section">طريقة الحفظ</div><div class="station-export-options station-export-delivery"></div><div class="station-export-status" aria-live="polite"></div></div>`;
        document.body.appendChild(backdrop);
        backdrop.addEventListener('click', e => { if (e.target === backdrop) close(); });
        backdrop.querySelector('.station-export-close').addEventListener('click', close);
        state.sheet = backdrop;
        return backdrop;
    }

    function close() {
        if (!state.sheet) return;
        state.sheet.classList.remove('show');
        state.pending = null; state.expected = null; state.busy = false; state.selectedFormat = null; state.delivery = null; state.requestId++;
    }

    function setStatus(text, kind) {
        const node = ensureSheet().querySelector('.station-export-status');
        node.className = 'station-export-status' + (kind ? ' ' + kind : '');
        node.innerHTML = text || '';
    }

    function show(options) {
        const sheet = ensureSheet();
        const formatsList = sheet.querySelector('.station-export-formats');
        const deliveryList = sheet.querySelector('.station-export-delivery');
        formatsList.innerHTML = ''; deliveryList.innerHTML = '';
        state.pending = options;
        const formats = options.formats && options.formats.length ? options.formats : ['csv'];
        state.selectedFormat = formats[0]; state.delivery = null;
        const nameInput = sheet.querySelector('#stationExportName');
        nameInput.value = String(options.filename || 'export').replace(/\.(csv|xlsx|pdf|json|png)$/i, '') || 'export';
        const add = (list, key, title, description, icon, action, cls = '') => {
            const button = document.createElement('button');
            button.type = 'button'; button.className = 'station-export-option ' + cls;
            button.innerHTML = `<span class="station-export-icon"><i class="fas ${icon}"></i></span><span><strong>${esc(title)}</strong><small>${esc(description)}</small></span>`;
            button.addEventListener('click', action); list.appendChild(button); return button;
        };
        const formatTitles = {csv:['CSV','تصدير وحفظ بصيغة CSV','fa-file-csv'],xlsx:['Excel (XLSX)','تصدير وحفظ بصيغة Excel/XLSX','fa-file-excel'],pdf:['PDF','تصدير وحفظ بصيغة PDF','fa-file-pdf'],json:['JSON','تصدير وحفظ بصيغة JSON','fa-file-code'],png:['PNG','تصدير وحفظ بصيغة PNG','fa-image'],print:['طباعة / PDF','فتح معاينة الطباعة وحفظ PDF','fa-print']};
        formats.forEach(format => { const t = formatTitles[format] || [format.toUpperCase(), 'تصدير الملف', 'fa-file-export']; add(formatsList, format, t[0], t[1], t[2], () => { state.selectedFormat = format; setStatus('تم تحديد صيغة ' + t[0] + '. اختر طريقة الحفظ.', ''); }); });
        if (formats.includes('print')) add(deliveryList, 'print', 'فتح معاينة الطباعة', 'معاينة Android أو طباعة المتصفح ثم حفظ PDF', 'fa-print', () => printNow(), 'station-export-method');
        else {
            add(deliveryList, 'browser', 'التنزيل عبر المتصفح', 'تنزيل الملف مباشرة باسم الملف المحدد', 'fa-download', () => downloadInBrowser(state.selectedFormat));
            add(deliveryList, 'folder', 'تصدير اختياري', 'فتح مدير التخزين لاختيار أو إنشاء المجلد والمسار', 'fa-folder-open', () => save(state.selectedFormat), 'station-export-method');
        }
        setStatus(options.blob ? 'عدّل الاسم إن رغبت، ثم اختر الصيغة وطريقة الحفظ.' : 'اختر الإجراء المطلوب من القائمة.', '');
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

    function openBlobExport(blob, filename, formats) {
        if (!blob) return false;
        openPreparedExport({
            blob: blob instanceof Blob ? blob : new Blob([blob]),
            filename: filename || 'export',
            mime: blob.type || 'application/octet-stream',
            formats: formats || formatsFor(filename || '') || ['csv']
        });
        return true;
    }

    function openPrint(options) {
        show({ filename: options && options.filename || 'report', formats: ['print'], print: true, nativePrint: options && options.nativePrint });
    }

    function base64FromBlob(blob) {
        if (!blob || typeof blob.arrayBuffer !== 'function') return Promise.reject(new Error('ملف التصدير غير صالح'));
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

    function extensionFor(format) { return format === 'xlsx' ? '.xlsx' : format === 'pdf' ? '.pdf' : format === 'json' ? '.json' : format === 'png' ? '.png' : '.csv'; }
    function mimeFor(format) { return format === 'xlsx' ? 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' : format === 'pdf' ? 'application/pdf' : format === 'json' ? 'application/json;charset=utf-8' : format === 'png' ? 'image/png' : 'text/csv;charset=utf-8'; }

    async function ensureJsPdf() {
        if (window.jspdf && window.jspdf.jsPDF) return window.jspdf.jsPDF;
        await new Promise((resolve, reject) => { const s = document.createElement('script'); s.src = 'file:///android_asset/assets-local/js/jspdf.umd.min.js'; s.onload = resolve; s.onerror = () => reject(new Error('مكتبة PDF غير متاحة')); document.head.appendChild(s); });
        if (!window.jspdf || !window.jspdf.jsPDF) throw new Error('مكتبة PDF غير متاحة');
        return window.jspdf.jsPDF;
    }

    async function asJson(blob) {
        const text = await blob.text();
        try { return new Blob([JSON.stringify(JSON.parse(text), null, 2)], { type: mimeFor('json') }); } catch (_) {
            return new Blob([JSON.stringify(csvRows(text.replace(/^\uFEFF/, '')).map(row => row.reduce((o, v, i) => (o['column_' + (i + 1)] = v, o), {})), null, 2)], { type: mimeFor('json') });
        }
    }

    async function asPdf(blob) {
        if (/application\/pdf/i.test(blob.type || '')) return blob;
        const jsPDF = await ensureJsPdf(); const doc = new jsPDF('p', 'pt', 'a4');
        const lines = (await blob.text()).replace(/^\uFEFF/, '').split(/\r?\n/);
        let y = 40; doc.setFontSize(9);
        lines.forEach(line => { if (y > 790) { doc.addPage(); y = 40; } doc.text(String(line).slice(0, 150), 40, y); y += 13; });
        return doc.output('blob');
    }

    async function asPng(blob) {
        if (/^image\/png/i.test(blob.type || '')) return blob;
        const text = (await blob.text()).replace(/^\uFEFF/, ''); const lines = text.split(/\r?\n/).slice(0, 200);
        const canvas = document.createElement('canvas'); canvas.width = 1600; canvas.height = Math.max(120, lines.length * 24 + 40);
        const ctx = canvas.getContext('2d'); ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, canvas.width, canvas.height); ctx.fillStyle = '#111'; ctx.font = '18px Arial';
        lines.forEach((line, i) => ctx.fillText(String(line).slice(0, 180), 24, 32 + i * 24));
        return new Promise((resolve, reject) => canvas.toBlob(blobResult => blobResult ? resolve(blobResult) : reject(new Error('تعذر إنشاء صورة PNG')), 'image/png'));
    }

    async function blobForFormat(source, format) {
        if (format === 'xlsx') return /\.xlsx$/i.test(state.pending.filename || '') ? source : asXlsx(source);
        if (format === 'json') return asJson(source);
        if (format === 'pdf') return asPdf(source);
        if (format === 'png') return asPng(source);
        return source;
    }

    function filenameFor(format) {
        const input = ensureSheet().querySelector('#stationExportName');
        const raw = String(input && input.value || state.pending && state.pending.filename || 'export').trim();
        const base = raw.replace(/\.(csv|xlsx|pdf|json|png)$/i, '').replace(/[\\/:*?"<>|\r\n]+/g, '_').trim() || 'export';
        return base + extensionFor(format);
    }

    function isProtectedExport(name) {
        return /(^|[-_ ])(database[-_ ]export|full[-_ ]database|backup|نسخة|نسخ|قاعدة[-_ ]البيانات)([-_. ]|$)/i.test(String(name || ''));
    }

    async function downloadInBrowser(format) {
        if (state.busy || !state.pending || !state.pending.blob) return;
        state.busy = true; setStatus('<i class="fas fa-spinner fa-spin"></i> جارٍ تجهيز التنزيل...', '');
        try {
            const blob = await blobForFormat(state.pending.blob, format); const url = URL.createObjectURL(blob);
            const a = document.createElement('a'); a.href = url; a.download = filenameFor(format); document.body.appendChild(a);
            originalAnchorClick.call(a); a.remove(); setTimeout(() => URL.revokeObjectURL(url), 1500);
            state.busy = false; setStatus('تم بدء تنزيل الملف عبر المتصفح.', 'success'); setTimeout(close, 1200);
        } catch (error) { state.busy = false; setStatus(esc(error.message || 'فشل تجهيز التنزيل'), 'error'); }
    }

    async function save(format) {
        if (state.busy || !state.pending || !state.pending.blob) return;
        const requestId = ++state.requestId;
        state.busy = true; setStatus('<i class="fas fa-spinner fa-spin"></i> جارٍ تجهيز الملف وفتح مدير التخزين...', '');
        try {
            const source = state.pending.blob;
            const blob = await blobForFormat(source, format);
            const filename = filenameFor(format);
            if (!window.AndroidInterface || typeof window.AndroidInterface.chooseExportFolder !== 'function') {
                throw new Error('تصدير المجلد غير متاح في Android Bridge؛ تم إلغاء التنزيل التلقائي لحماية اختيار المسار.');
            }
            window.onExportFolderResult = function (raw) {
                let result;
                try { result = typeof raw === 'string' ? JSON.parse(raw) : raw || {}; }
                catch (_) { if (requestId === state.requestId) { state.busy = false; setStatus('استجابة غير صالحة من مدير التخزين.', 'error'); } return; }
                if (requestId !== state.requestId) return;
                state.busy = false;
                if (result.success) { setStatus('تم حفظ الملف بنجاح.<span class="station-export-path">' + esc(result.path || result.uri || filename) + '</span>', 'success'); setTimeout(close, 1800); }
                else setStatus(esc(result.error || 'تعذر حفظ الملف في المجلد المحدد'), 'error');
            };
            const raw = window.AndroidInterface.chooseExportFolder(filename, mimeFor(format), await base64FromBlob(blob));
            let ack;
            try { ack = typeof raw === 'string' ? JSON.parse(raw) : raw || {}; }
            catch (_) { throw new Error('استجابة غير صالحة من Android أثناء فتح مدير التخزين'); }
            if (ack.success === false) throw new Error(ack.error || 'تعذر فتح مدير التخزين');
        } catch (error) { if (requestId === state.requestId) { state.busy = false; setStatus(esc(error.message || 'فشل التصدير'), 'error'); } }
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
        if (this.download && isProtectedExport(this.download)) return originalAnchorClick.apply(this, arguments);
        if (blob && this.download) {
            const name = this.download || 'export';
            const formats = state.expected || (/\.xlsx$/i.test(name) ? ['xlsx'] : /\.pdf$/i.test(name) ? ['pdf'] : ['csv']);
            show({ filename: name.replace(/\.(csv|xlsx|pdf)$/i, ''), mime: blob.type, blob, formats });
            return;
        }
        if (this.download && /^data:/i.test(this.href)) {
            try {
                const match = this.href.match(/^data:([^;,]+)?(;base64)?,(.*)$/i);
                if (match) {
                    const mime = match[1] || 'application/octet-stream';
                    const bytes = match[2]
                        ? Uint8Array.from(atob(match[3]), c => c.charCodeAt(0))
                        : new TextEncoder().encode(decodeURIComponent(match[3]));
                    openBlobExport(new Blob([bytes], { type: mime }), this.download, formatsFor(this.download));
                    return;
                }
            } catch (_) { /* use the browser's normal anchor behavior */ }
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

    function installFileWriterGuards() {
        const xlsx = window.XLSX;
        if (xlsx && !xlsx.__stationExportWrapped) {
            ['writeFile', 'writeFileXLSX'].forEach(function (method) {
                const native = xlsx[method];
                if (typeof native !== 'function') return;
                xlsx[method] = function (workbook, filename) {
                    try {
                        const output = xlsx.write(workbook, { bookType: 'xlsx', type: 'array' });
                        openBlobExport(new Blob([output], { type: mimeFor('xlsx') }), filename || 'export.xlsx', state.expected || ['xlsx']);
                        return;
                    } catch (_) { return native.apply(this, arguments); }
                };
            });
            xlsx.__stationExportWrapped = true;
        }

        const jsPDFCtor = window.jspdf && window.jspdf.jsPDF || window.jsPDF;
        if (jsPDFCtor && jsPDFCtor.API && !jsPDFCtor.API.__stationExportWrapped) {
            const nativeSave = jsPDFCtor.API.save;
            if (typeof nativeSave === 'function') {
                jsPDFCtor.API.save = function (filename) {
                    try {
                        openBlobExport(this.output('blob'), filename || 'export.pdf', state.expected || ['pdf']);
                        return this;
                    } catch (_) { return nativeSave.apply(this, arguments); }
                };
                jsPDFCtor.API.__stationExportWrapped = true;
            }
        }

        if (typeof window.saveAs === 'function' && !window.saveAs.__stationExportWrapped) {
            const nativeSaveAs = window.saveAs;
            const wrappedSaveAs = function (blob, filename) {
                if (blob instanceof Blob) { openBlobExport(blob, filename, formatsFor(filename || '')); return; }
                return nativeSaveAs.apply(this, arguments);
            };
            wrappedSaveAs.__stationExportWrapped = true;
            window.saveAs = wrappedSaveAs;
        }
    }

    installFileWriterGuards();
    let guardAttempts = 0;
    const guardTimer = setInterval(function () {
        installFileWriterGuards();
        if (++guardAttempts > 20) clearInterval(guardTimer);
    }, 250);

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

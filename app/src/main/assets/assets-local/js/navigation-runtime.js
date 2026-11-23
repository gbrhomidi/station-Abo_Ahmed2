/*
 * Shared navigation runtime for the local station WebView.
 * It normalizes all home/back controls without replacing page-specific business logic.
 */
(function () {
    'use strict';

    var EXIT_MS = 180;
    var navigating = false;
    var styleId = 'station-navigation-runtime-style';

    function installStyles() {
        if (document.getElementById(styleId)) return;
        var style = document.createElement('style');
        style.id = styleId;
        style.textContent = [
            'html:not(.station-page-ready):not(.station-page-exit) body {',
            '  opacity: 0;',
            '  transform: translate3d(-14px, 0, 0);',
            '}',
            'html.station-page-ready body {',
            '  opacity: 1;',
            '  transform: none;',
            '  transition: opacity 180ms ease, transform 180ms ease;',
            '}',
            'html.station-page-exit body {',
            '  opacity: 0;',
            '  transform: translate3d(14px, 0, 0);',
            '  transition: opacity 180ms ease, transform 180ms ease;',
            '  pointer-events: none;',
            '}',
            '@media (prefers-reduced-motion: reduce) {',
            '  html.station-page-ready body, html.station-page-exit body {',
            '    transition-duration: 1ms !important;',
            '  }',
            '}'
        ].join('\n');
        (document.head || document.documentElement).appendChild(style);
    }

    function markPageReady() {
        installStyles();
        document.documentElement.classList.add('station-page-ready');
    }

    function beginPageExit() {
        if (navigating) return false;
        navigating = true;
        installStyles();
        document.documentElement.classList.remove('station-page-ready');
        document.documentElement.classList.add('station-page-exit');
        return true;
    }

    function isHomeControl(element) {
        if (!element || !element.getAttribute) return false;

        var id = (element.id || '').toLowerCase();
        var action = (element.getAttribute('data-action') ||
            element.getAttribute('data-sidebar-action') ||
            element.getAttribute('data-sidebar') || '').toLowerCase();
        var onclick = (element.getAttribute('onclick') || '').toLowerCase();
        var href = (element.getAttribute('href') || '').toLowerCase();
        var label = ((element.getAttribute('aria-label') || '') + ' ' +
            (element.getAttribute('title') || '')).toLowerCase();

        if (action === 'home' || action === 'back') return true;
        if (/^(home(btn|button)?|btnback|sidebarback)$/.test(id)) return true;
        if (onclick.indexOf('gohome(') !== -1 ||
            onclick.indexOf("handlesidebaraction('home')") !== -1 ||
            onclick.indexOf('handlebuttonclick(\'home\')') !== -1 ||
            onclick.indexOf('handlebuttonclick(\'back\')') !== -1 ||
            onclick.indexOf("location.href='../main.html'") !== -1 ||
            onclick.indexOf("location.replace('../main.html'") !== -1 ||
            onclick.indexOf("location.href='file:///android_asset/main.html'") !== -1 ||
            /(^|\/)main\.html(?:[?#]|$)/.test(href)) {
            return true;
        }

        // Limit text-based fallback to clearly identified navigation controls.
        var isButton = element.tagName === 'BUTTON' || element.getAttribute('role') === 'button';
        return isButton && /(^|\s)(رجوع|الرئيسية|الصفحة الرئيسية)(\s|$)/.test(label);
    }

    function navigateHome(event) {
        if (event) {
            event.preventDefault();
            event.stopImmediatePropagation();
        }
        if (!beginPageExit()) return false;
        var destination = new URL('../main.html', window.location.href).href;
        window.setTimeout(function () {
            window.location.replace(destination);
        }, EXIT_MS);
        return false;
    }

    // Capture phase runs before inline handlers and ordinary addEventListener handlers.
    // This gives every page one stable, deduplicated home/back behavior.
    document.addEventListener('click', function (event) {
        var element = event.target && event.target.closest
            ? event.target.closest('button, a, [role="button"]')
            : null;
        if (element && isHomeControl(element)) navigateHome(event);
    }, true);

    window.__stationBeginPageExit = beginPageExit;
    window.__stationNavigateHome = navigateHome;

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', markPageReady, { once: true });
    } else {
        markPageReady();
    }
}());

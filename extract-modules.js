const fs = require('fs');
const path = require('path');

const mainHtmlPath = 'app/src/main/assets/main.html';
const content = fs.readFileSync(mainHtmlPath, 'utf8');

// نبحث عن أقسام القائمة الجانبية (Sidebar) التي تمثل الوحدات (Modules)
// وعادة ما تكون بتنسيق <div class="nav-item"> أو <li> تحتوي على <a>
const modulePattern = /<a[^>]*href=["']([^"']*\.html)["'][^>]*>(.*?)<\/a>/gi;
const groupPattern = /<div class="nav-section-title">(.*?)<\/div>/gi;

let modules = [];
let currentGroup = "General";

// استخراج العناوين الرئيسية للأقسام
const lines = content.split('\n');
let currentSection = "Core";

const extractedLinks = [];

lines.forEach(line => {
    // محاولة التقاط عناوين الأقسام
    const sectionMatch = line.match(/<div[^>]*class=["'][^"']*nav-section-title[^"']*["'][^>]*>(.*?)<\/div>/i) || 
                         line.match(/<h[1-6][^>]*class=["'][^"']*menu-title[^"']*["'][^>]*>(.*?)<\/h[1-6]>/i) ||
                         line.match(/<span[^>]*class=["'][^"']*nav-group-title[^"']*["'][^>]*>(.*?)<\/span>/i);
    
    if (sectionMatch) {
        currentSection = sectionMatch[1].replace(/<[^>]*>/g, '').trim();
    }

    // محاولة التقاط الروابط
    const linkMatch = line.match(/<a[^>]*href=["']([^"']*\.html)["'][^>]*>/i);
    if (linkMatch) {
        const href = linkMatch[1];
        // استخراج النص داخل الرابط
        const textMatch = line.match(/<a[^>]*>(.*?)<\/a>/i);
        let text = href;
        if (textMatch) {
            text = textMatch[1].replace(/<[^>]*>/g, '').trim();
        } else {
            // ربما النص في السطر التالي
            text = "Unknown - " + href;
        }
        
        // تنظيف المسار
        const screenName = href.replace('screens/', '').replace('./screens/', '');
        
        extractedLinks.push({
            section: currentSection,
            screen: screenName,
            href: href,
            text: text
        });
    }
});

// تجميع الشاشات حسب الأقسام (Modules)
const moduleMap = {};
extractedLinks.forEach(link => {
    if (!moduleMap[link.section]) {
        moduleMap[link.section] = [];
    }
    // تجنب التكرار
    if (!moduleMap[link.section].find(s => s.screen === link.screen)) {
        moduleMap[link.section].push(link);
    }
});

fs.writeFileSync('extracted-modules.json', JSON.stringify(moduleMap, null, 2));
console.log(`Extracted ${Object.keys(moduleMap).length} modules with ${extractedLinks.length} total links.`);

const fs = require('fs');
const path = require('path');
const source = fs.readFileSync(path.join(__dirname, 'app/src/main/assets/screens/ai-assistant.html'), 'utf8');
const expect = (condition, message) => { if (!condition) throw new Error(message); };

expect(source.includes("const profilesTarget=$('aiProfiles'); if(!profilesTarget)return; profilesTarget.innerHTML="), 'يجب ألا يكتب callback إعدادات AI في عنصر أزيل من DOM');
expect(source.includes('if (!sysBadge || !availableBadge || !cooldownBadge || !healthList) return;'), 'يجب ألا يكتب فحص صحة AI في عناصر غائبة');
expect(source.includes("else if(CFG.mode!=='backup'&&CFG.mode!=='ai')renderCards();"), 'يجب ألا يمسح renderCards واجهة AI بعد initSpecial');
console.log('AI assistant DOM lifecycle regression PASS.');

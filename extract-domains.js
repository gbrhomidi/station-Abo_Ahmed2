const fs = require('fs');
const inventory = JSON.parse(fs.readFileSync('screen-inventory-deep.json', 'utf8'));

const domains = [...new Set(inventory.map(s => s.domain))];

console.log("=== Discovered Domains ===");
domains.forEach(d => {
    const screens = inventory.filter(s => s.domain === d).map(s => s.file);
    console.log(`\nDomain: ${d} (${screens.length} screens)`);
    console.log(`Sample screens: ${screens.slice(0, 5).join(', ')}`);
});

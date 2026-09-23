const fs = require('fs');
const source = fs.readFileSync('app/src/main/assets/screens/fuel-sales.html', 'utf8');
const pos = fs.readFileSync('app/src/main/assets/screens/pos.html', 'utf8');
const expect = (condition, message) => { if (!condition) throw new Error(message); };

expect(source.includes('id="fuelAdjustmentModal"'), 'Fuel adjustment modal is missing');
expect(source.includes('id="fuelAdjustmentType"'), 'Fuel adjustment type selector is missing');
expect(source.includes('value="return">مرتجع'), 'Fuel return option is missing');
expect(source.includes('value="damage">تالف'), 'Fuel damage option is missing');
expect(source.includes('openFuelAdjustment'), 'Fuel adjustment modal opener is missing');
expect(source.includes('saveFuelAdjustment'), 'Fuel adjustment modal save handler is missing');
expect(source.includes('processFuelSaleAdjustment'), 'Fuel adjustment modal is not connected to Android');
expect(pos.includes('id="returnDetailType"'), 'Product return/damage type selector is missing');
expect(pos.includes("apiCall('processProductSaleAdjustment'"), 'Product return/damage modal is not connected to the dedicated adjustment bridge');

console.log('Fuel/product adjustment modal JS test PASS');

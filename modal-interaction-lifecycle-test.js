const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('app/src/main/assets/assets-local/js/modal-interaction.js', 'utf8');
const listeners = new Map();
const animationFrames = [];
let observerCallback = null;
let observerDisconnected = false;

class MutationObserverMock {
  constructor(callback) {
    observerCallback = callback;
  }
  observe() {}
  disconnect() { observerDisconnected = true; }
}

const documentMock = {
  body: { style: { overflow: '' } },
  documentElement: {},
  querySelectorAll() { return []; },
  addEventListener(type, callback) { listeners.set(type, callback); },
  removeEventListener(type) { listeners.delete(type); },
};
const windowMock = {
  MutationObserver: MutationObserverMock,
  getComputedStyle() { return { display: 'none', visibility: 'hidden', opacity: '0' }; },
  requestAnimationFrame(callback) { animationFrames.push(callback); return animationFrames.length; },
  setTimeout() { throw new Error('requestAnimationFrame must be preferred in this environment'); },
  addEventListener(type, callback) { listeners.set(`window:${type}`, callback); },
};
const context = vm.createContext({ window: windowMock, document: documentMock, MutationObserver: MutationObserverMock, Array });
vm.runInContext(source, context);

assert.ok(listeners.has('focusin'), 'Focus handler must be registered');
assert.ok(listeners.has('touchmove'), 'Touch handler must be registered');
assert.ok(observerCallback, 'Mutation observer must be registered');

let externalScrolls = 0;
const externalInput = {
  isConnected: true,
  closest(selector) { return selector.includes('input') ? this : null; },
  scrollIntoView() { externalScrolls += 1; },
};
listeners.get('focusin')({ target: externalInput });
assert.equal(animationFrames.length, 0, 'Focusing a non-modal control must not schedule modal work');
assert.equal(externalScrolls, 0, 'Focusing a non-modal control must not force scrolling');

let modalScrolls = 0;
const modalInput = {
  isConnected: true,
  closest(selector) {
    if (selector.includes('input')) return this;
    if (selector.includes('.modal-overlay')) return { matches() { return true; } };
    return null;
  },
  scrollIntoView() { modalScrolls += 1; },
};
listeners.get('focusin')({ target: modalInput });
assert.equal(animationFrames.length, 2, 'Focusing a modal control must schedule only preparation and one deferred scroll');
animationFrames.splice(0).forEach((callback) => callback());
assert.equal(modalScrolls, 1, 'A modal control must scroll once after focus');

const unrelatedNode = { nodeType: 1, matches() { return false; }, querySelector() { return null; }, closest() { return null; } };
observerCallback([{ type: 'attributes', target: unrelatedNode }]);
assert.equal(animationFrames.length, 0, 'Unrelated mutations must not schedule modal preparation');

const modalNode = { nodeType: 1, matches() { return true; }, querySelector() { return null; }, closest() { return null; } };
observerCallback([{ type: 'childList', target: modalNode, addedNodes: [] }]);
observerCallback([{ type: 'childList', target: modalNode, addedNodes: [] }]);
assert.equal(animationFrames.length, 1, 'Repeated modal mutations in one frame must be coalesced');
animationFrames.splice(0).forEach((callback) => callback());

listeners.get('window:pagehide')();
assert.equal(observerDisconnected, true, 'Observer must disconnect when the page is hidden');
assert.equal(listeners.has('touchmove'), false, 'Touch listener must be removed when the page is hidden');
assert.equal(listeners.has('focusin'), false, 'Focus listener must be removed when the page is hidden');

console.log('Shared modal interaction lifecycle PASS.');

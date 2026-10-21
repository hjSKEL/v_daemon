'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const web = path.resolve(__dirname, '../../main/resources/web');
const html = fs.readFileSync(path.join(web, 'index.html'), 'utf8');
const source = fs.readFileSync(process.env.VDAEMON_TEST_APP_JS || path.join(web, 'app.js'), 'utf8');

async function screen() {
  const elements = new Map();
  function element(value = '') {
    const classes = new Set();
    return {
      value, checked: false, hidden: false, disabled: false, dataset: {}, textContent: '', listeners: {},
      classList: {add: name => classes.add(name), remove: name => classes.delete(name),
        toggle(name, active) { if (active) classes.add(name); else classes.delete(name); }, contains: name => classes.has(name)},
      addEventListener(name, callback) { this.listeners[name] = callback; },
      replaceChildren() {}, append() {}, add() {}, setAttribute() {},
      focus() { this.focused = true; }, scrollIntoView() { this.scrolled = true; },
    };
  }
  for (const match of html.matchAll(/\bid="([^"]+)"([^>]*)/g)) {
    const node = element(/\bvalue="([^"]*)"/.exec(match[2])?.[1] || '');
    node.type = /\btype="([^"]*)"/.exec(match[2])?.[1] || '';
    node.checked = /\bchecked\b/.test(match[2]); elements.set(match[1], node);
  }
  elements.get('action').value = 'BootNotification';
  elements.get('steps').value = '[]';
  const calls = [], requests = [];
  let fleet = [];
  let planResult = [{delayMs: 0, action: 'Authorize', payload: {idTag: 'CARD'}}];
  let planError;
  const context = vm.createContext({
    document: {getElementById: id => { assert.ok(elements.has(id), `Missing element ${id}`); return elements.get(id); }, createElement: () => element()},
    Option: function(text, value) { this.text = text; this.value = value; },
    setInterval() {}, URL, Blob,
    async fetch(url, options = {}) {
      requests.push({url, body: options.body ? JSON.parse(options.body) : undefined});
      if (url === '/api/chargers') return {ok: true, json: async () => fleet};
      if (url.endsWith('/clear-error')) {
        const charger = fleet.find(c => url === `/api/chargers/${c.id}/clear-error`);
        if (charger) charger.lastError = '';
        return {ok: true, json: async () => charger};
      }
      if (url !== '/api/plan') return {ok: true, json: async () => fleet.find(c => url === `/api/chargers/${encodeURIComponent(c.id)}`) || []};
      const body = JSON.parse(options.body); calls.push(body);
      if (!body.idTag || planError) return {ok: false, status: 400, json: async () => ({error: planError || 'idTag 값이 필요합니다.'})};
      return {ok: true, json: async () => planResult};
    },
  });
  vm.runInContext(source, context);
  await new Promise(setImmediate);
  return {elements, calls, requests, context, setFleet: value => { fleet = value; }, click: id => elements.get(id).listeners.click(),
    setPlan: result => { planResult = result; }, setError: error => { planError = error; }};
}

test('missing card shows actionable error beside generator and preserves JSON', async () => {
  const ui = await screen();
  await ui.click('makePlan');
  assert.equal(ui.elements.get('steps').value, '[]');
  assert.equal(ui.calls.length, 0);
  assert.match(ui.elements.get('planMessage').textContent, /idTag/);
  assert.ok(ui.elements.get('planMessage').classList.contains('error'));
  assert.ok(ui.elements.get('idTag').focused);
  assert.equal(ui.elements.get('makePlan').disabled, false);
});

test('generator replaces JSON and brings the generated steps into view', async () => {
  const ui = await screen();
  ui.elements.get('idTag').value = ' CARD ';
  ui.elements.get('steps').value = 'old invalid JSON';
  ui.elements.get('includeAuthorize').checked = true;
  await ui.click('makePlan');
  assert.equal(ui.calls[0].idTag, 'CARD');
  assert.equal(ui.calls[0].includeAuthorize, true);
  assert.equal(ui.calls[0].durationSeconds, 60);
  assert.equal(JSON.parse(ui.elements.get('steps').value)[0].action, 'Authorize');
  assert.match(ui.elements.get('planMessage').textContent, /생성/);
  assert.ok(ui.elements.get('steps').focused && ui.elements.get('steps').scrolled);
  assert.equal(ui.elements.get('makePlan').disabled, false);
});

test('append keeps existing steps before the new session', async () => {
  const ui = await screen();
  ui.elements.get('idTag').value = 'CARD';
  ui.elements.get('steps').value = '[{"action":"Heartbeat","payload":{}}]';
  await ui.click('appendPlan');
  assert.deepEqual(JSON.parse(ui.elements.get('steps').value).map(step => step.action), ['Heartbeat', 'Authorize']);
  assert.match(ui.elements.get('planMessage').textContent, /추가/);
});

test('invalid append JSON is preserved and rejected before requesting a plan', async () => {
  const ui = await screen();
  ui.elements.get('idTag').value = 'CARD'; ui.elements.get('steps').value = '{}';
  await ui.click('appendPlan');
  assert.equal(ui.calls.length, 0);
  assert.equal(ui.elements.get('steps').value, '{}');
  assert.match(ui.elements.get('planMessage').textContent, /배열/);
  assert.ok(ui.elements.get('planMessage').classList.contains('error'));
});

test('server validation failure is visible beside generator without losing existing JSON', async () => {
  const ui = await screen();
  ui.elements.get('idTag').value = 'CARD'; ui.elements.get('steps').value = '[{"action":"Heartbeat"}]';
  ui.setError('sampleSeconds 범위: 1~5');
  await ui.click('makePlan');
  assert.equal(ui.elements.get('steps').value, '[{"action":"Heartbeat"}]');
  assert.match(ui.elements.get('planMessage').textContent, /sampleSeconds/);
  assert.ok(ui.elements.get('planMessage').classList.contains('error'));
  assert.equal(ui.elements.get('makePlan').disabled, false);
});

test('manual Authorize payload contains only the entered idTag', async () => {
  const ui = await screen();
  assert.ok(html.includes('<option>Authorize</option>'));
  ui.elements.get('action').value = 'Authorize'; ui.elements.get('idTag').value = ' CARD ';
  await ui.click('template');
  assert.deepEqual(JSON.parse(ui.elements.get('payload').value), {idTag: 'CARD'});
});

test('Faulted preset uses the standard StatusNotification action and vendorErrorCode field', async () => {
  const ui = await screen();
  ui.setFleet([charger('000001-01')]);
  await vm.runInContext('refresh()', ui.context);
  ui.elements.get('action').value = 'StatusNotificationFaulted';
  ui.elements.get('action').listeners.change();
  assert.deepEqual(JSON.parse(ui.elements.get('payload').value), {
    connectorId: 0, status: 'Faulted', errorCode: 'OtherError', timestamp: '$now', vendorErrorCode: 'CT03'
  });
  await ui.click('send');
  const sent = ui.requests.find(r => r.url.endsWith('/send')).body;
  assert.equal(sent.action, 'StatusNotification');
  assert.equal(sent.payload.vendorErrorCode, 'CT03');
  assert.equal(sent.payload.venderErrorCode, undefined);
});

test('StatusNotification can target charger zero without changing the charging connector selection', async () => {
  const ui = await screen();
  ui.setFleet([charger('000001-01')]);
  await vm.runInContext('refresh()', ui.context);
  ui.elements.get('connectorId').value = '2'; ui.elements.get('connectorId').listeners.change();
  ui.elements.get('action').value = 'StatusNotification'; ui.elements.get('action').listeners.change();
  assert.equal(ui.elements.get('statusTargetField').hidden, false);
  assert.equal(JSON.parse(ui.elements.get('payload').value).connectorId, 2);
  ui.elements.get('statusTarget').value = 'charger'; ui.elements.get('statusTarget').listeners.change();
  assert.equal(JSON.parse(ui.elements.get('payload').value).connectorId, 0);
  assert.equal(ui.elements.get('connectorId').value, '2');
  await ui.click('send');
  assert.equal(ui.requests.find(r => r.url.endsWith('/send')).body.payload.connectorId, 0);
  ui.elements.get('action').value = 'StartTransaction'; ui.elements.get('action').listeners.change();
  assert.equal(ui.elements.get('statusTargetField').hidden, true);
  assert.equal(JSON.parse(ui.elements.get('payload').value).connectorId, 2);
  const active = charger('000001-01'); active.chargePoint = {connectorId: 0, status: 'Unavailable'};
  ui.setFleet([active]); await vm.runInContext('refresh()', ui.context);
  assert.match(ui.elements.get('chargePointState').textContent, /충전기 자체 \(0\): Unavailable/);
});

test('connector switch preserves separate editable plans, card values and Boot options', async () => {
  const ui = await screen();
  ui.elements.get('idTag').value = 'CARD1'; ui.elements.get('steps').value = '[{"action":"Heartbeat"}]';
  ui.elements.get('includeAuthorize').checked = true;
  ui.elements.get('connectorId').value = '2'; ui.elements.get('connectorId').listeners.change();
  assert.equal(ui.elements.get('steps').value, '[]'); assert.equal(ui.elements.get('idTag').value, '');
  ui.elements.get('idTag').value = 'CARD2'; ui.elements.get('steps').value = '[{"action":"Authorize"}]';
  ui.elements.get('connectorId').value = '1'; ui.elements.get('connectorId').listeners.change();
  assert.equal(ui.elements.get('idTag').value, 'CARD1'); assert.equal(ui.elements.get('steps').value, '[{"action":"Heartbeat"}]');
  assert.equal(ui.elements.get('includeAuthorize').checked, true);
  ui.elements.get('connectorId').value = '2'; ui.elements.get('connectorId').listeners.change();
  assert.equal(ui.elements.get('idTag').value, 'CARD2'); assert.equal(ui.elements.get('steps').value, '[{"action":"Authorize"}]');
});

test('start and stop address the selected connector while both progress states are available', async () => {
  const ui = await screen();
  ui.setFleet([{id: '000001-01', connection: 'CONNECTED', schedule: 'RUNNING', step: 5, totalSteps: 20,
    connectors: [], events: [], schedules: [{connectorId: 1, status: 'RUNNING', step: 3, totalSteps: 10}, {connectorId: 2, status: 'RUNNING', step: 2, totalSteps: 10}]}]);
  await vm.runInContext("selected = '000001-01'; refresh()", ui.context);
  ui.elements.get('connectorId').value = '2'; ui.elements.get('connectorId').listeners.change();
  assert.match(ui.elements.get('selectedScheduleState').textContent, /2: RUNNING.*2\/10/);
  ui.elements.get('steps').value = '[{"action":"Heartbeat","payload":{}}]';
  await ui.click('runSchedule'); await ui.click('stopSchedule');
  const start = ui.requests.find(r => r.url.endsWith('/schedule'));
  const stop = ui.requests.find(r => r.url.endsWith('/stop'));
  assert.equal(start.body.connectorId, 2); assert.equal(stop.body.connectorId, 2);
  assert.match(ui.elements.get('messageText').textContent, /커넥터 2/);
});

function charger(id, status = 'RUNNING') {
  return {id, connection: 'CONNECTED', schedule: status, step: 1, totalSteps: 2, pending: 0, heartbeatSeconds: 300,
    connectors: [], schedules: [{connectorId: 1, status, step: 1, totalSteps: 2}],
    events: [{sequence: 1, time: '2026-10-21T00:00:00Z', direction: 'EVENT', data: id}]};
}
async function choose(ui, id) {
  ui.elements.get('chargerSelect').value = id;
  ui.elements.get('chargerSelect').listeners.change();
  await new Promise(setImmediate);
}

test('initial selection and charger changes complete URL and label logs for the chosen charger', async () => {
  const ui = await screen();
  ui.setFleet([charger('000001-01'), charger('000001-02')]);
  await vm.runInContext('refresh()', ui.context);
  assert.equal(ui.elements.get('daemonUrl').value, 'wss://127.0.0.1:8091/ocpp16/000001-01');
  assert.equal(ui.elements.get('eventsChargerId').textContent, '000001-01');
  ui.elements.get('daemonUrl').value = 'wss://test.local:9090/ocpp16/000001-01?test=1';
  await choose(ui, '000001-02');
  assert.equal(ui.elements.get('daemonUrl').value, 'wss://test.local:9090/ocpp16/000001-02?test=1');
  assert.equal(ui.elements.get('eventsChargerId').textContent, '000001-02');
  assert.match(ui.elements.get('events').textContent, /000001-02/);
  assert.doesNotMatch(ui.elements.get('events').textContent, /000001-01/);
});

test('charger selection resets both editors and old connector drafts without stopping running schedules', async () => {
  const ui = await screen();
  ui.setFleet([charger('000001-01', 'COMPLETED'), charger('000001-02', 'COMPLETED')]);
  await vm.runInContext('refresh()', ui.context);
  ui.elements.get('idTag').value = 'CARD1'; ui.elements.get('steps').value = '[{"action":"Heartbeat"}]';
  ui.elements.get('connectorId').value = '2'; ui.elements.get('connectorId').listeners.change();
  ui.elements.get('idTag').value = 'CARD2'; ui.elements.get('steps').value = '[{"action":"Authorize"}]';
  ui.elements.get('action').value = 'StopTransaction'; ui.elements.get('payload').value = '{"transactionId":123}';
  ui.elements.get('energyWh').value = '9999'; ui.elements.get('startsAt').value = '2026-10-01T00:00:00Z';
  ui.elements.get('includeAuthorize').checked = true;
  ui.elements.get('validationResult').hidden = false;
  await choose(ui, '000001-02');
  assert.equal(ui.elements.get('connectorId').value, '1');
  assert.equal(ui.elements.get('idTag').value, ''); assert.equal(ui.elements.get('steps').value, '[]');
  assert.equal(ui.elements.get('energyWh').value, '1000'); assert.equal(ui.elements.get('startsAt').value, '');
  assert.equal(ui.elements.get('includeAuthorize').checked, false);
  assert.equal(ui.elements.get('action').value, 'BootNotification');
  assert.equal(JSON.parse(ui.elements.get('payload').value).chargeBoxSerialNumber, '000001-02');
  assert.equal(ui.elements.get('validationResult').hidden, true);
  assert.match(ui.elements.get('selectedScheduleState').textContent, /COMPLETED/);
  await choose(ui, '000001-01');
  assert.equal(ui.elements.get('steps').value, '[]'); assert.equal(ui.elements.get('idTag').value, '');
  ui.elements.get('connectorId').value = '2'; ui.elements.get('connectorId').listeners.change();
  assert.equal(ui.elements.get('steps').value, '[]'); assert.equal(ui.elements.get('idTag').value, '');
  assert.equal(ui.requests.filter(r => r.body).length, 0);
});

test('selecting a running charger restores each connector draft but resets the manual editor', async () => {
  const ui = await screen();
  const active = charger('000001-01');
  active.schedules.push({connectorId: 2, status: 'RUNNING', step: 1, totalSteps: 2});
  ui.setFleet([active, charger('000001-02', 'IDLE')]);
  await vm.runInContext('refresh()', ui.context);
  ui.elements.get('idTag').value = 'CARD1'; ui.elements.get('steps').value = '[{"action":"Heartbeat"}]';
  ui.elements.get('connectorId').value = '2'; ui.elements.get('connectorId').listeners.change();
  ui.elements.get('idTag').value = 'CARD2'; ui.elements.get('steps').value = '[{"action":"Authorize"}]';
  ui.elements.get('energyWh').value = '4321'; ui.elements.get('includeAuthorize').checked = true;
  await choose(ui, '000001-02');
  assert.equal(ui.elements.get('steps').value, '[]');
  await choose(ui, '000001-01');
  assert.equal(ui.elements.get('connectorId').value, '2');
  assert.equal(ui.elements.get('idTag').value, 'CARD2');
  assert.equal(ui.elements.get('steps').value, '[{"action":"Authorize"}]');
  assert.equal(ui.elements.get('energyWh').value, '4321');
  assert.equal(ui.elements.get('includeAuthorize').checked, true);
  assert.equal(ui.elements.get('action').value, 'BootNotification');
  assert.equal(JSON.parse(ui.elements.get('payload').value).chargeBoxSerialNumber, '000001-01');
  ui.elements.get('connectorId').value = '1'; ui.elements.get('connectorId').listeners.change();
  assert.equal(ui.elements.get('idTag').value, 'CARD1');
  assert.equal(ui.elements.get('steps').value, '[{"action":"Heartbeat"}]');
  active.schedules.forEach(s => { s.status = 'COMPLETED'; }); active.schedule = 'COMPLETED';
  await choose(ui, '000001-02'); await choose(ui, '000001-01');
  assert.equal(ui.elements.get('steps').value, '[]'); assert.equal(ui.elements.get('idTag').value, '');
  assert.equal(ui.requests.filter(r => r.body).length, 0);
});

test('running schedule JSON is restored from the server when the browser has no draft', async () => {
  const ui = await screen();
  const active = charger('000001-01');
  const steps = [{delayMs: 5000, action: 'Heartbeat', payload: {}}];
  active.schedules[0].steps = steps;
  active.schedules.push({connectorId: 2, status: 'RUNNING', steps});
  ui.setFleet([active]);
  await vm.runInContext('refresh()', ui.context);
  assert.deepEqual(JSON.parse(ui.elements.get('steps').value), steps);
  ui.elements.get('connectorId').value = '2'; ui.elements.get('connectorId').listeners.change();
  assert.deepEqual(JSON.parse(ui.elements.get('steps').value), steps);
});

test('a plan response for the previous charger cannot refill the newly reset schedule', async () => {
  const ui = await screen();
  ui.setFleet([charger('000001-01'), charger('000001-02')]);
  await vm.runInContext('refresh()', ui.context);
  let complete;
  ui.setPlan(new Promise(resolve => { complete = resolve; }));
  ui.elements.get('idTag').value = 'CARD1';
  const pending = ui.click('makePlan');
  await new Promise(setImmediate);
  await choose(ui, '000001-02');
  complete([{action: 'Authorize', payload: {idTag: 'CARD1'}}]);
  await pending;
  assert.equal(ui.elements.get('steps').value, '[]');
  assert.equal(ui.elements.get('planMessage').hidden, true);
});

test('closing notice clears only selected charger error and new errors show again', async () => {
  const ui = await screen();
  const first = {id: '000001-01', connection: 'CONNECTED', lastError: 'Heartbeat CALLERROR: InternalError', events: [], schedules: [], connectors: []};
  const second = {id: '000001-02', lastError: 'Another error'};
  ui.setFleet([first, second]);
  vm.runInContext("selectCharger('000001-01')", ui.context);
  await vm.runInContext('refresh()', ui.context);
  assert.match(ui.elements.get('messageText').textContent, /CALLERROR/);
  await ui.click('closeMessage');
  assert.equal(first.lastError, '');
  assert.equal(second.lastError, 'Another error');
  assert.equal(ui.elements.get('message').hidden, true);
  await vm.runInContext('refresh()', ui.context);
  assert.equal(ui.elements.get('message').hidden, true);
  first.lastError = 'StatusNotification CALLERROR: FormationViolation';
  await vm.runInContext('refresh()', ui.context);
  assert.equal(ui.elements.get('message').hidden, false);
  assert.match(ui.elements.get('messageText').textContent, /FormationViolation/);
  assert.ok(ui.requests.some(r => r.url === '/api/chargers/000001-01/clear-error'));
});

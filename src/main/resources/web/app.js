'use strict';
const $ = id => document.getElementById(id);
let selected = '', current = null, lastEventSignature = '', polling = false, selectionVersion = 0;
const drafts = new Map();
const selectedConnectors = new Map();
let selectionPending = false;
const draftFields = ['steps', 'idTag', 'meterStartWh', 'energyWh', 'durationSeconds', 'sampleSeconds', 'startsAt', 'speed', 'includeBoot', 'includeAuthorize'];
const initialDraft = Object.fromEntries(draftFields.map(id => [id, $(id).type === 'checkbox' ? $(id).checked : $(id).value]));
const initialAction = $('action').value;
let draftKey = `/${$('connectorId').value}`;
function saveDraft() {
  if (draftKey) drafts.set(draftKey, Object.fromEntries(draftFields.map(id => [id, $(id).type === 'checkbox' ? $(id).checked : $(id).value])));
}
function loadDraft() {
  draftKey = `${selected}/${$('connectorId').value}`;
  const draft = drafts.get(draftKey) || initialDraft;
  for (const id of draftFields) { if ($(id).type === 'checkbox') $(id).checked = draft[id]; else $(id).value = draft[id]; }
  const run = current?.id === selected ? current.schedules?.find(s => s.connectorId === number('connectorId')) : null;
  if (!drafts.has(draftKey) && run?.status === 'RUNNING' && run.steps) $('steps').value = JSON.stringify(run.steps, null, 2);
  $('planMessage').hidden = true;
}
function switchDraft() {
  saveDraft(); loadDraft();
  render(current);
}
function selectCharger(id) {
  saveDraft();
  if (selected) selectedConnectors.set(selected, $('connectorId').value);
  selectionVersion++;
  selected = id; current = null; lastEventSignature = '';
  selectionPending = Boolean(id);
  draftKey = ''; $('connectorId').value = selectedConnectors.get(id) || '1';
  $('daemonUrl').value = $('daemonUrl').value.replace(/(?:\{chargerId\}|%7BchargerId%7D|\d{6}-\d{2})(?=\/?(?:[?#]|$))/i, selected || '{chargerId}');
  $('action').value = initialAction; $('validationAt').value = '';
  $('statusTarget').value = 'connector';
  $('validationResult').hidden = true; $('validationResult').textContent = '';
  loadDraft(); render(null); template();
}
function notify(message, error = false) { $('messageText').textContent = message; $('message').classList.toggle('error', error); $('message').hidden = false; }
async function api(path, data) {
  const response = await fetch(path, data === undefined ? {} : {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(data)});
  const value = await response.json();
  if (!response.ok) throw new Error(value.error || `HTTP ${response.status}`);
  return value;
}
function chargerPath(action) { if (!selected) throw new Error('가상 충전기를 선택하세요.'); return `/api/chargers/${encodeURIComponent(selected)}/${action}`; }
function number(id) { const n = Number($(id).value); if (!Number.isInteger(n)) throw new Error(`${id}: 정수를 입력하세요.`); return n; }
function time() { return $('validationAt').value.trim() || new Date().toISOString(); }
function state() { return current?.connectors?.find(c => c.connectorId === number('connectorId')); }
function template() {
  $('statusTargetField').hidden = $('action').value !== 'StatusNotification';
  const connectorId = number('connectorId'), idTag = $('idTag').value.trim(), meter = Math.round(state()?.meterWh || 0);
  const payloads = {
    BootNotification: {chargePointVendor: 'KEVIT', chargePointModel: 'VirtualCharger', chargeBoxSerialNumber: selected, firmwareVersion: 'v-daemon-1.0'},
    Authorize: {idTag},
    StatusNotification: {connectorId: $('statusTarget').value === 'charger' ? 0 : connectorId, status: 'Available', errorCode: 'NoError', timestamp: '$now'},
    StatusNotificationFaulted: {connectorId: 0, status: 'Faulted', errorCode: 'OtherError', timestamp: '$now', vendorErrorCode: 'CT03'},
    StartTransaction: {connectorId, idTag, meterStart: meter, timestamp: '$now'},
    MeterValues: {connectorId, transactionId: '$transactionId', meterValue: [{timestamp: '$now', sampledValue: [{value: String(meter + 100), measurand: 'Energy.Active.Import.Register', unit: 'Wh'}]}]},
    StopTransaction: {transactionId: `$transactionId:${connectorId}`, idTag, meterStop: meter, timestamp: '$now', reason: 'Local'},
    DataTransfer: {vendorId: 'kr.co.kevit', messageId: 'Tariff', data: JSON.stringify({connectorId, idTag, timestamp: time()})},
    Heartbeat: {},
  };
  $('payload').value = JSON.stringify(payloads[$('action').value], null, 2);
}
function render(value) {
  current = value;
  if (value && selectionPending) {
    selectionPending = false;
    const running = value.schedules?.some(s => s.status === 'RUNNING');
    if (!running) {
      for (const key of drafts.keys()) if (key.startsWith(`${selected}/`)) drafts.delete(key);
      $('connectorId').value = '1';
    }
    loadDraft();
    template();
  }
  $('eventsChargerId').textContent = selected || '—';
  $('currentId').textContent = value?.id || '—';
  $('chargePointState').textContent = `충전기 자체 (0): ${value?.chargePoint?.status || 'Unknown'}`;
  $('connectionState').textContent = value?.connection || '—';
  $('scheduleState').textContent = value ? `${value.schedule} · ${value.step}/${value.totalSteps}` : '—';
  const chosen = value?.schedules?.find(s => s.connectorId === number('connectorId'));
  $('selectedScheduleState').textContent = `커넥터 ${$('connectorId').value}: ${chosen ? `${chosen.status} · ${chosen.step}/${chosen.totalSteps}${chosen.lastError ? ' · ' + chosen.lastError : ''}` : 'IDLE'}`;
  $('schedules').replaceChildren();
  for (const schedule of value?.schedules || []) {
    const row = document.createElement('tr');
    for (const text of [schedule.connectorId, schedule.status, `${schedule.step}/${schedule.totalSteps}`, schedule.lastError || '—']) {
      const cell = document.createElement('td'); cell.textContent = text; row.append(cell);
    }
    $('schedules').append(row);
  }
  $('pendingState').textContent = value ? `${value.pending} / ${value.heartbeatSeconds}초` : '—';
  $('worker').textContent = value ? `전용 작업 스레드: ${value.worker}` : '충전기별 전용 작업 스레드';
  $('connectors').replaceChildren();
  for (const connector of value?.connectors || []) {
    const row = document.createElement('tr');
    for (const field of ['connectorId', 'status', 'transactionId', 'meterWh', 'idTag']) { const cell = document.createElement('td'); cell.textContent = connector[field] ?? '—'; row.append(cell); }
    $('connectors').append(row);
  }
  const signature = `${value?.id}:${value?.events?.at(-1)?.sequence}`;
  if (signature !== lastEventSignature) {
    lastEventSignature = signature;
    $('events').textContent = value?.events?.map(e => `${e.time}  ${e.direction}\n${JSON.stringify(e.data)}`).join('\n\n') || '대기 중';
    $('events').scrollTop = $('events').scrollHeight;
  }
  const error = value?.logError || value?.lastError;
  if (error && error !== '사용자 연결 종료') notify(error, true);
}
async function refresh() {
  if (polling) return; polling = true;
  try {
    const fleet = await api('/api/chargers');
    if (!fleet.some(c => c.id === selected)) {
      const next = fleet[0]?.id || '';
      if (next !== selected) selectCharger(next);
    }
    const ids = fleet.map(c => c.id).join(',');
    if ($('chargerSelect').dataset.ids !== ids) {
      $('chargerSelect').replaceChildren();
      if (!fleet.length) $('chargerSelect').add(new Option('충전기를 추가하세요', ''));
      fleet.forEach(c => $('chargerSelect').add(new Option(c.id, c.id)));
      $('chargerSelect').dataset.ids = ids;
    }
    $('chargerSelect').value = selected;
    $('fleet').replaceChildren();
    for (const charger of fleet) {
      const item = document.createElement('div'); item.className = 'fleet-item';
      const name = document.createElement('strong'); name.textContent = charger.id;
      const status = document.createElement('span'); status.textContent = charger.connection;
      item.append(name, status); $('fleet').append(item);
    }
    const requestedId = selected;
    const requestedVersion = selectionVersion;
    const value = requestedId ? await api(`/api/chargers/${encodeURIComponent(requestedId)}`) : null;
    if (requestedVersion === selectionVersion) render(value);
  } catch (error) { notify(error.message, true); }
  finally { polling = false; }
}
function on(id, callback) { $(id).addEventListener('click', async () => {
  $(id).disabled = true;
  try { await callback(); await refresh(); }
  catch (error) { notify(error.message, true); }
  finally { $(id).disabled = false; }
}); }
on('closeMessage', async () => {
  const id = selected, version = selectionVersion;
  const value = id ? await api(chargerPath('clear-error'), {}) : null;
  if (id !== selected || version !== selectionVersion) return;
  // Ignore an older poll response that still contains the dismissed error.
  selectionVersion++;
  if (value) render(value);
  $('message').hidden = true;
});
on('addCharger', async () => { const c = await api('/api/chargers', {id: $('chargerId').value.trim()}); selectCharger(c.id); notify(`${selected} 추가 완료`); });
on('connect', async () => { await api(chargerPath('connect'), {url: $('daemonUrl').value.trim(), password: $('password').value, heartbeatSeconds: number('heartbeatSeconds')}); $('password').value = ''; notify('연결을 시작했습니다.'); });
on('disconnect', async () => { await api(chargerPath('disconnect'), {}); notify('연결을 종료했습니다. 거래 상태는 메모리에 유지됩니다.'); });
on('remove', async () => { await api(chargerPath('remove'), {}); selectCharger(''); notify('충전기를 제거했습니다.'); });
on('template', template);
on('send', async () => { await api(chargerPath('send'), {connectorId: number('connectorId'), action: $('action').value === 'StatusNotificationFaulted' ? 'StatusNotification' : $('action').value, payload: JSON.parse($('payload').value)}); notify('메시지를 전송했습니다. 아래 응답 기록을 확인하세요.'); });
on('validate', async () => {
  if (!selected) throw new Error('가상 충전기를 선택하세요.');
  const result = await api('/api/validate', {chargerId: selected, idTag: $('idTag').value.trim(), timestamp: time()});
  $('validationResult').hidden = false; $('validationResult').textContent = JSON.stringify(result, null, 2);
  notify(result.ok ? '사전 데이터 검증을 통과했습니다.' : '누락·사용 중지·요금 적용 기간을 확인하세요.', !result.ok);
});
async function plan(append) {
  const plannedVersion = selectionVersion, plannedConnector = $('connectorId').value;
  const message = $('planMessage');
  message.hidden = false; message.classList.remove('error'); message.textContent = '스케줄을 생성하고 있습니다.';
  try {
    const idTag = $('idTag').value.trim();
    if (!idTag) { $('idTag').focus(); throw new Error('회원카드 idTag를 입력한 뒤 스케줄을 생성하세요.'); }
    const existing = append ? JSON.parse($('steps').value) : [];
    if (!Array.isArray(existing)) throw new Error('기존 스케줄은 배열이어야 합니다.');
    const steps = await api('/api/plan', {connectorId: number('connectorId'), idTag, meterStartWh: number('meterStartWh'), energyWh: number('energyWh'), durationSeconds: number('durationSeconds'), sampleSeconds: number('sampleSeconds'), speed: number('speed'), startsAt: $('startsAt').value.trim(), includeBoot: $('includeBoot').checked, includeAuthorize: $('includeAuthorize').checked});
    if (plannedVersion !== selectionVersion || plannedConnector !== $('connectorId').value) return;
    $('steps').value = JSON.stringify([...existing, ...steps], null, 2);
    message.textContent = `${steps.length}개 메시지 단계를 ${append ? '추가' : '생성'}했습니다. 아래 JSON을 확인한 뒤 스케줄 실행을 누르세요.`;
    $('steps').focus({preventScroll: true}); $('steps').scrollIntoView({behavior: 'smooth', block: 'center'});
    notify(message.textContent);
  } catch (error) {
    if (plannedVersion !== selectionVersion || plannedConnector !== $('connectorId').value) return;
    message.textContent = error.message; message.classList.add('error'); throw error;
  }
}
on('makePlan', () => plan(false)); on('appendPlan', () => plan(true));
on('runSchedule', async () => { await api(chargerPath('schedule'), {connectorId: number('connectorId'), steps: JSON.parse($('steps').value)}); notify(`커넥터 ${$('connectorId').value} 스케줄 실행을 시작했습니다.`); });
on('stopSchedule', async () => { await api(chargerPath('stop'), {connectorId: number('connectorId')}); notify(`커넥터 ${$('connectorId').value}의 다음 단계부터 중지합니다. 이미 전송한 메시지의 응답은 계속 처리합니다.`); });
on('download', () => {
  if (!current) throw new Error('가상 충전기를 선택하세요.');
  const url = URL.createObjectURL(new Blob([JSON.stringify(current.events, null, 2)], {type: 'application/json'}));
  const a = document.createElement('a'); a.href = url; a.download = `${selected}-events.json`; a.click(); URL.revokeObjectURL(url);
});
function tab(mode) {
  for (const name of ['manual', 'schedule']) { const active = name === mode; $(`${name}Panel`).hidden = !active; $(`${name}Tab`).classList.toggle('active', active); $(`${name}Tab`).setAttribute('aria-pressed', String(active)); }
}
$('manualTab').addEventListener('click', () => tab('manual')); $('scheduleTab').addEventListener('click', () => tab('schedule'));
$('chargerSelect').addEventListener('change', () => { selectCharger($('chargerSelect').value); notify(`${selected} 선택`); refresh(); });
$('connectorId').addEventListener('change', switchDraft);
$('action').addEventListener('change', template);
$('statusTarget').addEventListener('change', template);
template(); refresh(); setInterval(refresh, 1000);

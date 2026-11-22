'use strict';
// Coach team board, nudges and the morning digest (SPEC 9 wave 2). Loaded after dashboard.js and built from its helpers.
Object.assign(dictionary.bg, {
  readiness:'Сутрешна проверка (скок от място)',
  rdBoard:'Готовност', rdEyebrow:'Табло на отбора', rdCheckedIn:'Направили отчет днес: ',
  rdAthlete:'Спортист', rdCheckin:'Отчет', rdStatus:'Готовност', rdWellbeing:'Самочувствие', rdJump:'Скок спрямо нормата',
  rdLoad:'Натоварване (7/28 дни)', rdAction:'Напомняне',
  rd_good:'Добре', rd_normal:'Нормално', rd_take_care:'Внимание', rd_still_learning:'Още се учи', rd_no_data:'Няма данни',
  rdDone:'Направен', rdMissing:'Няма отчет', rdNudged:'Напомнено днес', rdNudge:'Напомни', rdNudgeAll:'Напомни на всички без отчет',
  rdNudgeSent:'Напомнянето е изпратено.', rdNudgeNone:'Няма на кого да се напомни.',
  rd_wellbeing:'самочувствие', rd_resting_hr:'пулс в покой', rd_hrv:'вариабилност на пулса', rd_jump:'скок', rd_sleep:'сън',
  rdWorse:'По-неблагоприятно от обичайното: ', rdBetter:'По-добре от обичайното: ', rdLearning:'Още се събират данни: ',
  rdOf:' от ', rdDays:' дни', rd_well_above:'много над обичайното', rd_above:'над обичайното', rd_similar:'както обикновено',
  rd_below:'под обичайното', rdLoadLearning:'нужни са 28 дни', rdExcluded:' несигурни не се броят',
  rdGroup:'Група', rdAllGroups:'Целият клуб', rdSort:'Подреждане', rdSortStatus:'По готовност', rdSortName:'По име',
  rdSortLoad:'По натоварване', rdSortJump:'По скок',
  rdDigest:'Сутрешен преглед', rdDigestEmpty:'Няма какво да се отбележи тази сутрин.',
  rdPhrase:'Оформи с локалния модел', rdPhrased:'Оформено от локалния модел само от фактите по-долу.',
  rdNoModel:'Локалният модел не е наличен; показан е списъкът с факти.', rdFacts:'Факти',
  rdFact_missing_checkin:'Няма отчет: ', rdFact_below_usual:'Под обичайното: ', rdFact_load_spike:'Натоварване над обичайното: ',
  rdNote:'Информация за спортиста и треньора, не медицински съвет. Всеки показател се сравнява с последните 28 дни на самия спортист (поне 7 стойности); промяна се отчита само над 0,5 × стандартното отклонение и типичната грешка. Сънят се сравнява с нужните часове за възрастта и с редовността. Натоварването е RPE × минути за всяка тренировка; съотношението 7/28 дни описва само сравнение с обичайното, не е прогноза.'
});
Object.assign(dictionary.en, {
  readiness:'Morning readiness check (countermovement jump)',
  rdBoard:'Readiness', rdEyebrow:'Team board', rdCheckedIn:'Checked in today: ',
  rdAthlete:'Athlete', rdCheckin:'Check-in', rdStatus:'Readiness', rdWellbeing:'Wellbeing', rdJump:'Jump vs usual',
  rdLoad:'Load (7/28 days)', rdAction:'Reminder',
  rd_good:'Good', rd_normal:'Normal', rd_take_care:'Take care', rd_still_learning:'Still learning', rd_no_data:'No data',
  rdDone:'Done', rdMissing:'Not checked in', rdNudged:'Nudged today', rdNudge:'Nudge', rdNudgeAll:'Nudge everyone not checked in',
  rdNudgeSent:'Reminder sent.', rdNudgeNone:'Nobody to remind.',
  rd_wellbeing:'wellbeing', rd_resting_hr:'resting heart rate', rd_hrv:'heart rate variability', rd_jump:'jump', rd_sleep:'sleep',
  rdWorse:'Less favourable than usual: ', rdBetter:'Better than usual: ', rdLearning:'Still collecting: ',
  rdOf:' of ', rdDays:' days', rd_well_above:'well above usual', rd_above:'above usual', rd_similar:'similar to usual',
  rd_below:'below usual', rdLoadLearning:'needs 28 days', rdExcluded:' uncertain not counted',
  rdGroup:'Group', rdAllGroups:'Whole club', rdSort:'Sort', rdSortStatus:'By readiness', rdSortName:'By name',
  rdSortLoad:'By load', rdSortJump:'By jump',
  rdDigest:'Morning digest', rdDigestEmpty:'Nothing to note this morning.',
  rdPhrase:'Phrase with the local model', rdPhrased:'Phrased by the local model from the facts below only.',
  rdNoModel:'The local model is not available; the list of facts is shown.', rdFacts:'Facts',
  rdFact_missing_checkin:'Not checked in: ', rdFact_below_usual:'Below usual: ', rdFact_load_spike:'Load above usual: ',
  rdNote:'Information for athlete and coach, not medical advice. Each measure is compared with the athlete’s own last 28 days (at least 7 values); a change counts only beyond 0.5 × SD and the typical error. Sleep is compared with the hours needed for the age and with regularity. Load is RPE × minutes per session; the 7/28-day ratio only compares with what is usual, it is not a forecast.'
});
state.readiness = {group:'', sort:'status', phrased:false};
const RD_ORDER = {take_care:0, normal:1, good:2, still_learning:3, no_data:4};

async function boardData() {
  const params = new URLSearchParams({day:apiDay()});
  if (state.readiness.group) params.set('group_id', state.readiness.group);
  if (state.user.role === 'admin') params.set('club_id', state.user.club_id);
  const board = await api('/api/team/board?' + params);
  params.delete('group_id');
  if (state.readiness.phrased) params.set('phrased', 'true');
  params.set('language', state.language);
  const digest = await api('/api/team/digest?' + params);  // after the board: both share the server's slot limit
  const [boardGroups, athletes] = await Promise.all([
    api('/api/groups' + (state.user.role === 'admin' ? '?club_id=' + state.user.club_id : '')), allPages('/api/athletes')]);
  return {readinessBoard:board, digest, boardGroups, athletes};
}
async function nudge(ids) {
  const result = await api('/api/team/nudge', {method:'POST', body:JSON.stringify({athlete_ids:ids, day:state.readinessBoard.day})});
  await load('board');
  message(result.sent.length ? 'rdNudgeSent' : 'rdNudgeNone');
}
function factorNames(keys) { return keys.map(key => t('rd_' + key)).join(', '); }
function statusCell(row) {
  const cell = node('td');
  cell.append(node('span', t('rd_' + row.status), 'rd-status rd-' + row.status));
  if (row.worse.length) cell.append(node('small', t('rdWorse') + factorNames(row.worse)));
  else if (row.better.length) cell.append(node('small', t('rdBetter') + factorNames(row.better)));
  if (row.status === 'still_learning' && row.learning.length) cell.append(node('small', t('rdLearning') + factorNames(row.learning)));
  return cell;
}
function jumpCell(jump) {
  const cell = node('td', null, 'num');
  if (jump.value == null) cell.textContent = '—';
  else {
    cell.append(node('strong', format(jump.value) + ' ' + t('recCm')));
    if (jump.difference_pct != null) cell.append(node('small', (jump.difference_pct > 0 ? '+' : '') + format(jump.difference_pct) + ' %', jump.state === 'worse' ? 'below' : null));
  }
  if (jump.baseline == null) cell.append(node('small', format(jump.baseline_n) + t('rdOf') + format(jump.needed)));
  if (jump.excluded) cell.append(node('small', format(jump.excluded) + t('rdExcluded')));
  return cell;
}
function loadCell(row) {
  const cell = node('td', null, 'num');
  if (!row.load_ready) { cell.append(node('span', '—'), node('small', t('rdLoadLearning'))); return cell; }
  cell.append(node('strong', row.load_ratio == null ? '—' : format(row.load_ratio, 'knee_ankle_ratio')));
  if (row.load_band) cell.append(node('small', t('rd_' + row.load_band), ['above', 'well_above'].includes(row.load_band) ? 'below' : null));
  return cell;
}
function digestPanel(digest) {
  const panel = node('section', null, 'inline-panel rd-digest');
  panel.append(node('h2', t('rdDigest')));
  if (!digest.facts.length) { panel.append(node('p', t('rdDigestEmpty'), 'muted')); return panel; }
  if (digest.text) panel.append(node('p', digest.text, 'rd-phrased'), node('p', t('rdPhrased'), 'muted'));
  else if (state.readiness.phrased) panel.append(node('p', t('rdNoModel'), 'muted'));
  const list = node('ul', null, 'status-list');
  for (const fact of digest.facts) {
    const item = node('li');
    let detail = '';
    if (fact.kind === 'below_usual') detail = t('rd_' + fact.status) + (fact.factors.length ? ' · ' + factorNames(fact.factors) : '');
    if (fact.kind === 'load_spike') detail = format(fact.ratio, 'knee_ankle_ratio') + ' · ' + t('rd_' + fact.band);
    if (fact.kind === 'missing_checkin' && fact.nudged) detail = t('rdNudged');
    item.append(node('span', t('rdFact_' + fact.kind) + fact.name), node('span', detail, 'muted'));
    list.append(item);
  }
  panel.append(digest.text ? details('rdFacts', list) : list);
  if (!digest.text) {
    const phrase = button('rdPhrase', async () => { state.readiness.phrased = true; await load('board'); });
    phrase.id = 'digest-phrase'; panel.append(phrase);
  }
  return panel;
}
function board(content) {
  const data = state.readinessBoard;
  const rows = [...data.rows];
  const checked = rows.filter(row => row.checked_in).length;
  const hero = overviewHero(t('rdEyebrow'), t('rdBoard'), date(data.day) + ' · ' + t('rdCheckedIn') + checked + ' / ' + rows.length);
  const strip = node('div', null, 'attendance-strip rd-strip'); strip.setAttribute('aria-hidden', 'true');
  for (const row of rows.slice(0, 32)) strip.append(node('i', null, 'rd-' + row.status + (row.checked_in ? '' : ' absent')));
  hero.append(strip);
  content.append(hero, digestPanel(state.digest));
  const controls = node('div', null, 'rd-controls');
  const groups = state.boardGroups.filter(g => g.club_id === data.club_id);
  if (groups.length) {
    const select = node('select'); select.id = 'rd-group'; select.setAttribute('aria-label', t('rdGroup'));
    for (const [value, name] of [['', t('rdAllGroups')], ...groups.map(g => [String(g.id), g.name])]) {
      const option = node('option', name); option.value = value; option.selected = value === String(state.readiness.group); select.append(option);
    }
    select.addEventListener('change', () => { state.readiness.group = select.value; load('board'); });
    controls.append(select);
  }
  const sort = node('select'); sort.id = 'rd-sort'; sort.setAttribute('aria-label', t('rdSort'));
  for (const [value, key] of [['status', 'rdSortStatus'], ['name', 'rdSortName'], ['load', 'rdSortLoad'], ['jump', 'rdSortJump']]) {
    const option = node('option', t(key)); option.value = value; option.selected = value === state.readiness.sort; sort.append(option);
  }
  sort.addEventListener('change', () => { state.readiness.sort = sort.value; render('#rd-sort'); });
  controls.append(sort);
  const waiting = rows.filter(row => !row.checked_in && !row.nudged).map(row => row.athlete_id);
  const all = button('rdNudgeAll', () => nudge(waiting)); all.id = 'nudge-all'; all.disabled = !waiting.length;
  controls.append(all);
  content.append(controls);
  const by = {
    status: (a, b) => RD_ORDER[a.status] - RD_ORDER[b.status] || a.name.localeCompare(b.name, locale()),
    name: (a, b) => a.name.localeCompare(b.name, locale()),
    load: (a, b) => (b.load_ratio ?? -1) - (a.load_ratio ?? -1),
    jump: (a, b) => (a.jump.difference_pct ?? 1e9) - (b.jump.difference_pct ?? 1e9)}[state.readiness.sort];
  rows.sort(by);
  const grid = table(['rdAthlete', 'rdCheckin', 'rdStatus', 'rdWellbeing', 'rdJump', 'rdLoad', 'rdAction'], t('rdBoard'));
  grid.wrap.classList.add('club-table', 'rd-table');
  [3, 4, 5].forEach(index => { grid.head.children[index].className = 'num'; });
  for (const row of rows) {
    const tr = node('tr', null, row.checked_in ? null : 'rd-absent');
    const name = node('td'); const athlete = state.athletes.find(a => a.id === row.athlete_id);
    name.append(athlete ? athleteLink(athlete) : node('span', row.name));
    const checkin = node('td');
    checkin.append(node('span', t(row.checked_in ? 'rdDone' : 'rdMissing'), 'badge' + (row.checked_in ? '' : ' pending')));
    if (row.nudged) checkin.append(node('small', t('rdNudged')));
    const action = node('td');
    if (!row.checked_in && !row.nudged) { const control = button('rdNudge', () => nudge([row.athlete_id])); control.id = 'nudge-' + row.athlete_id; action.append(control); }
    else action.textContent = '—';
    tr.append(name, checkin, statusCell(row), node('td', row.wellbeing == null ? '—' : row.wellbeing + ' / 25', 'num'), jumpCell(row.jump), loadCell(row), action);
    grid.body.append(tr);
  }
  content.append(rows.length ? grid.wrap : node('p', t('noAthletes'), 'empty'), node('p', t('rdNote'), 'muted rd-note'));
}

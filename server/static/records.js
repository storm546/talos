'use strict';
// Personal records and the club leaderboard (SPEC 8). Loaded after dashboard.js and built from its helpers.
Object.assign(dictionary.bg, {
  recTitle:'Рекорди', recBoard:'Класация', recClubBoard:'Клубна класация',
  recIntro:'Най-добрите резултати от вече записаните скокове, серии и задържания. Скокове с предупреждение не се броят.',
  recHeight:'Височина на скока', recFlight:'Време във въздуха', recHeaviest:'Най-тежка серия',
  recEstimate:'Изчислен максимум за 1 повторение', recEstimateNote:'По формулата на Епли, само от серии до 10 повторения.',
  recHold:'Най-дълго задържане', recPrevious:'Предишен рекорд', recNew:'Нов рекорд', recFirst:'Първи резултат',
  recExcluded:'Скокове с предупреждение, които не се броят: ', recNone:'Още няма рекорди. Появяват се след първия скок или серия с тежест.',
  recStrength:'Сила', recExercise:'Упражнение', recReps:'повт.', recRank:'Място', recName:'Спортист', recBest:'Най-добър резултат',
  recPeriod:'Период', rec30:'30 дни', rec90:'90 дни', recAll:'Цялото време', recMeasure:'Показател', recGroup:'Група',
  recAllGroups:'Целият клуб', recNotShown:'Не са показани (не участват или чакат потвърждение от треньора): ',
  recYou:'Вие', recOptIn:'Покажи ме в класацията на клуба',
  recOptInNote:'Членовете на клуба виждат само малкото Ви име, първата буква на фамилията и най-добрия Ви скок. Можете да се откажете по всяко време.',
  recAwaitCoach:'Под 18 години: треньорът също трябва да потвърди участието.', recEmptyBoard:'Още няма класирани спортисти за този период.',
  recStatus:'Участие в класацията', recOptedIn:'Спортистът участва', recOptedOut:'Спортистът не участва', recShown:'Показан', recHidden:'Скрит',
  recAllow:'Потвърдете участието (под 18 г.)', recDisallow:'Оттеглете потвърждението', recUnder18:'Под 18 години',
  recScale:'обща скала 0–', recCm:'см', recMs:'мс', recKg:'кг', recSec:'сек'
});
Object.assign(dictionary.en, {
  recTitle:'Records', recBoard:'Leaderboard', recClubBoard:'Club leaderboard',
  recIntro:'Best results from the jumps, sets and holds already recorded. Jumps with a warning do not count.',
  recHeight:'Jump height', recFlight:'Flight time', recHeaviest:'Heaviest set',
  recEstimate:'Estimated one-rep max', recEstimateNote:'Epley formula, from sets of at most 10 reps only.',
  recHold:'Longest hold', recPrevious:'Previous best', recNew:'New record', recFirst:'First result',
  recExcluded:'Jumps with a warning that do not count: ', recNone:'No records yet. They appear after the first jump or weighted set.',
  recStrength:'Strength', recExercise:'Exercise', recReps:'reps', recRank:'Rank', recName:'Athlete', recBest:'Best result',
  recPeriod:'Period', rec30:'30 days', rec90:'90 days', recAll:'All time', recMeasure:'Measure', recGroup:'Group',
  recAllGroups:'Whole club', recNotShown:'Not shown (not opted in or awaiting the coach’s confirmation): ',
  recYou:'You', recOptIn:'Show me on the club leaderboard',
  recOptInNote:'Club members see only your first name, last initial and best jump. You can turn this off at any time.',
  recAwaitCoach:'Under 18: your coach also needs to confirm.', recEmptyBoard:'No ranked athletes for this period yet.',
  recStatus:'Leaderboard participation', recOptedIn:'Athlete opted in', recOptedOut:'Athlete not opted in', recShown:'Shown', recHidden:'Hidden',
  recAllow:'Confirm participation (under 18)', recDisallow:'Withdraw confirmation', recUnder18:'Under 18',
  recScale:'shared scale 0–', recCm:'cm', recMs:'ms', recKg:'kg', recSec:'s'
});
state.board = {measure:'jump_height', period:'all', group:''};
const REC_LABEL = {jump_height:'recHeight', flight_time:'recFlight', heaviest_set:'recHeaviest', estimated_1rm:'recEstimate', form_hold:'recHold'};
const REC_PERIOD = {'30':'rec30', '90':'rec90', all:'recAll'};

async function recordsData(view) {
  if (view === 'records') {
    const target = athleteTarget();
    return {records: target ? await api('/api/athletes/' + target.id + '/records') : null};
  }
  const params = new URLSearchParams({measure:state.board.measure, period:state.board.period});
  if (state.board.group) params.set('group_id', state.board.group);
  const own = state.user.role === 'admin' ? '?club_id=' + state.user.club_id : '';
  const [leaderboard, boardGroups] = await Promise.all([api('/api/clubs/me/leaderboard?' + params), api('/api/groups' + own)]);
  return {leaderboard, boardGroups};
}
// A jump-shaped object for flightGlyph: height and flight time are tied by h = g t^2 / 8.
function boardJump(measure, value) {
  const flight = measure === 'flight_time' ? value : Math.sqrt(8 * value / 100 / 9.80665) * 1000;
  return {flight_time_ms:flight, jump_height_cm:9.80665 * (flight / 1000) ** 2 / 8 * 100, warnings:[]};
}
function recValue(record) {
  const unit = {cm:'recCm', ms:'recMs', kg:'recKg'}[record.unit];
  if (record.measure === 'form_hold') return format(record.value / 1000) + ' ' + t('recSec');
  const digits = record.unit === 'ms' ? 'flight_time_ms' : undefined;
  return format(record.value, digits) + ' ' + t(unit) + (record.reps && record.measure === 'heaviest_set' ? ' × ' + record.reps : '');
}
function recPrevious(record) {
  if (!record.previous) return t('recFirst');
  return t('recPrevious') + ': ' + recValue({...record, ...record.previous}) + ' · ' + date(record.previous.date);
}
function recordsBoard(records) {
  const jumps = records.filter(r => r.source === 'jump' || r.source === 'form_set');
  const scale = Math.ceil(Math.max(40, ...jumps.filter(r => r.measure === 'jump_height').map(r => r.value * 1.15)) / 10) * 10;
  const board = node('section', null, 'flight-board record-lanes');
  const lanes = node('div', null, 'flight-lanes'); board.append(lanes);
  for (const record of jumps) {
    const lane = node('div', null, 'flight-lane' + (record.new ? ' record-new' : ''));
    const label = record.measure === 'form_hold' ? record['name_' + state.language] + ' · ' + t('recHold') : t(REC_LABEL[record.measure]);
    if (record.measure !== 'form_hold') lane.append(flightGlyph(boardJump(record.measure, record.value), scale));
    lane.append(node('span', label, 'record-label'), node('strong', recValue(record)));
    if (record.new) lane.append(node('span', t('recNew'), 'badge record-badge'));
    lane.append(node('span', date(record.date)), node('span', recPrevious(record)));
    lanes.append(lane);
  }
  return board;
}
function strengthTable(records) {
  const rows = new Map();
  for (const r of records.filter(r => r.source === 'set_log')) {
    const row = rows.get(r.exercise_id) || {exercise:r.exercise}; row[r.measure] = r; rows.set(r.exercise_id, row);
  }
  const grid = table(['recExercise', 'recHeaviest', 'recEstimate', 'date'], t('recStrength'));
  grid.wrap.classList.add('club-table');
  [...grid.head.children].slice(1, 3).forEach(cell => { cell.className = 'num'; });
  for (const row of rows.values()) {
    const tr = node('tr'); tr.append(node('td', row.exercise));
    for (const key of ['heaviest_set', 'estimated_1rm']) {
      const cell = node('td', null, 'num'); const r = row[key];
      if (r) {
        cell.append(node('strong', recValue(r)));
        if (r.new) cell.append(' ', node('span', t('recNew'), 'badge record-badge'));
        cell.append(node('small', recPrevious(r)));
      } else cell.textContent = '—';
      tr.append(cell);
    }
    tr.append(node('td', date(row.heaviest_set.date)));
    grid.body.append(tr);
  }
  return rows.size ? grid.wrap : null;
}
function leaderboardStatus(athlete) {
  const wrap = node('section', null, 'inline-panel record-status');
  wrap.append(node('h2', t('recStatus')));
  const line = node('p');
  line.append(node('span', t(athlete.leaderboard_opt_in ? 'recOptedIn' : 'recOptedOut'), 'badge' + (athlete.leaderboard_opt_in ? '' : ' pending')), ' ',
    node('span', t(athlete.shown ? 'recShown' : 'recHidden'), 'badge' + (athlete.shown ? '' : ' pending')));
  if (athlete.under_18) line.append(' ', node('span', t('recUnder18'), 'badge'));
  wrap.append(line);
  if (athlete.under_18) {
    const allowed = Boolean(athlete.leaderboard_allowed);
    const control = button(allowed ? 'recDisallow' : 'recAllow', async () => {
      await api('/api/users/' + athlete.id + '/profile', {method:'PATCH', body:JSON.stringify({leaderboard_allowed:!allowed})});
      Object.assign(athlete, {leaderboard_allowed:!allowed, shown:!allowed && athlete.leaderboard_opt_in && athlete.consent_recorded});
      await load('records'); message('saved');
    }, allowed ? 'secondary' : '');
    control.id = 'leaderboard-allowed';
    wrap.append(control);
  }
  return wrap;
}
function records(content) {
  const athlete = beginAthleteView(content, 'records', t('recTitle'));
  if (!athlete) return;
  content.append(node('p', t('recIntro'), 'muted'));
  if (state.user.role !== 'athlete') content.append(leaderboardStatus(athlete));
  const data = state.records;
  if (!data || !data.records.length) content.append(node('p', t('recNone'), 'empty'));
  else {
    const hero = node('section', null, 'overview-hero record-hero');
    hero.append(node('p', t('recTitle'), 'eyebrow'), recordsBoard(data.records));
    content.append(hero);
    const strength = strengthTable(data.records);
    if (strength) content.append(node('h2', t('recStrength')), node('p', t('recEstimateNote'), 'muted'), strength);
  }
  if (data) content.append(node('p', t('recExcluded') + format(data.excluded_jumps), 'muted'));
}
function segmented(label, choices, current, pick) {
  const group = node('div', null, 'week'); group.setAttribute('role', 'group'); group.setAttribute('aria-label', t(label));
  for (const [value, key] of choices) {
    const control = button(key, () => pick(value)); control.className = 'secondary';
    control.setAttribute('aria-pressed', String(value === current)); group.append(control);
  }
  return group;
}
function leaderboard(content) {
  const data = state.leaderboard;
  const own = state.user.role === 'athlete';
  const pick = change => { Object.assign(state.board, change); load('leaderboard'); };
  const hero = overviewHero(t('recClubBoard'), t('recBoard'), t(REC_LABEL[data.measure]) + ' · ' + t(REC_PERIOD[data.period]));
  const controls = node('div', null, 'board-controls');
  controls.append(segmented('recMeasure', [['jump_height', 'recHeight'], ['flight_time', 'recFlight']], data.measure, measure => pick({measure})),
    segmented('recPeriod', [['30', 'rec30'], ['90', 'rec90'], ['all', 'recAll']], data.period, period => pick({period})));
  const groups = state.boardGroups.filter(g => g.club_id === state.user.club_id);
  if (groups.length) {
    const select = node('select'); select.id = 'board-group'; select.setAttribute('aria-label', t('recGroup'));
    for (const [value, name] of [['', t('recAllGroups')], ...groups.map(g => [String(g.id), g.name])]) {
      const option = node('option', name); option.value = value; option.selected = value === String(state.board.group); select.append(option);
    }
    select.addEventListener('change', () => pick({group:select.value}));
    controls.append(select);
  }
  hero.append(controls);
  const rows = data.rows;
  if (rows.length) {
    const scale = data.measure === 'jump_height' ? Math.ceil(Math.max(40, rows[0].value * 1.15) / 10) * 10 : 60;
    const board = node('section', null, 'flight-board');
    board.append(node('h2', t(REC_LABEL[data.measure]) + ' · ' + t('recScale') + scale + ' ' + t('recCm')));
    const lanes = node('ol', null, 'flight-lanes board-lanes');
    for (const row of rows.slice(0, 8)) {
      const lane = node('li', null, 'flight-lane' + (row.you ? ' own-lane' : ''));
      lane.append(flightGlyph(boardJump(data.measure, row.value), scale), node('strong', recValue({...row, unit:data.unit, measure:data.measure})),
        node('span', row.rank + '. ' + row.name + (row.you ? ' · ' + t('recYou') : '')));
      lanes.append(lane);
    }
    board.append(lanes); hero.append(board);
  }
  content.append(hero);
  if (own) {
    const me = data.me;
    const panel = node('section', null, 'inline-panel record-status');
    const label = node('label', null, 'checkbox'); const box = node('input'); box.type = 'checkbox'; box.id = 'leaderboard-opt-in';
    box.checked = me.leaderboard_opt_in;
    box.addEventListener('change', () => run(async () => {
      await api('/api/me/profile', {method:'PATCH', body:JSON.stringify({leaderboard_opt_in:box.checked})});
      await load('leaderboard'); message('saved');
    }, box));
    label.append(box, node('span', t('recOptIn'))); panel.append(label, node('p', t('recOptInNote'), 'muted'));
    if (me.under_18 && me.leaderboard_opt_in && !me.leaderboard_allowed) panel.append(node('p', t('recAwaitCoach'), 'warning'));
    content.append(panel);
  }
  if (!rows.length) content.append(node('p', t('recEmptyBoard'), 'empty'));
  else {
    const grid = table(['recRank', 'recName', 'recBest', 'date'], t('recBoard'));
    grid.wrap.classList.add('club-table', 'board-table');
    grid.head.children[0].className = 'num'; grid.head.children[2].className = 'num';
    for (const row of rows) {
      const tr = node('tr', null, row.you ? 'own-row' : null);
      tr.append(node('td', String(row.rank), 'num'), node('td', row.name + (row.you ? ' · ' + t('recYou') : '')),
        node('td', recValue({...row, unit:data.unit, measure:data.measure}), 'num'), node('td', date(row.date)));
      grid.body.append(tr);
    }
    content.append(grid.wrap);
  }
  if (!own) content.append(node('p', t('recNotShown') + format(data.not_shown), 'muted board-hidden'));
}

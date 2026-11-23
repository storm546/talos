'use strict';
// Field tests of the wave-3 phone tools (SPEC 9.3). Loaded after dashboard.js and records.js and built from their helpers.
Object.assign(dictionary.bg, {
  ftTitle:'Полеви тестове', ftIntro:'Числа от инструментите на телефона: обхват на движение, баланс, спринт и реакция, кардио с GPS. Видео, поза и маршрут остават на телефона.',
  ftRom:'Обхват на движение', ftBalance:'Баланс на един крак', ftSpeed:'Спринт, ловкост и реакция', ftCardio:'Кардио с GPS',
  ftJoint:'Става', ftLeft:'Ляво', ftRight:'Дясно', ftDiff:'Разлика', ftError:'Грешка', ftLeg:'Опорен крак', ftBest:'Най-добро',
  ftLatest:'Последно', ftSway:'Люлеене', ftEnded:'Край', ftTest:'Тест', ftPrecision:'Точност', ftActivity:'Активност',
  ftDistance:'Разстояние', ftDuration:'Време', ftPace:'Темпо', ftSplits:'Километри', ftNone:'Още няма полеви тестове. Появяват се след първия запис от телефона.',
  ftWithin:'в рамките на грешката', ftNotValidated:'не е проверено спрямо еталон',
  ftRomNote:'Ъгъл от позата, задържан неподвижно поне 1 s. Типична грешка ±10° (коляно отстрани, измерена); за таза и рамото няма еталон. Сравнявайте със себе си при същата постановка.',
  ftBalanceNote:'Времето спира при стъпване, при силно люлеене или при загуба на позата. Люлеенето е средното отклонение на таза в % от дължината на крака; включва шума на позата.',
  ftSpeedNote:'Времената са от кадрите на камерата, точни до един кадър. При старт със сигнал времето включва реакцията и неизмереното закъснение на звука на телефона.',
  ftCardioNote:'Само обобщение: разстояние, време, темпо и километрите. Маршрутът не напуска телефона.',
  knee_flexion:'Сгъване в коляното', hip_flexion:'Сгъване в тазобедрената става', shoulder_flexion:'Повдигане на ръката напред', shoulder_abduction:'Повдигане на ръката встрани',
  touch_down:'стъпване', sway:'люлеене', time_limit:'60 s', stopped:'спрян', lost_tracking:'загубена поза',
  ft_sprint:'Спринт', ft_505:'5-0-5', ft_t_test:'Т-тест', ft_reaction:'Реакция', ftCue:'старт със сигнал', ftCrossing:'старт от линията',
  run:'Бягане', walk:'Ходене', cycle:'Колело', ftLegL:'Ляв', ftLegR:'Десен', recBalance:'Най-дълъг баланс', recSprint:'Най-бърз спринт',
  recAgility:'Най-бърза ловкост', ftFieldRecords:'Полеви тестове', ftS:'с', ftMs:'мс', ftM:'м', ftKm:'км'
});
Object.assign(dictionary.en, {
  ftTitle:'Field tests', ftIntro:'Numbers from the phone tools: range of motion, balance, sprint and reaction, GPS cardio. Video, pose and route stay on the phone.',
  ftRom:'Range of motion', ftBalance:'Single-leg balance', ftSpeed:'Sprint, agility and reaction', ftCardio:'GPS cardio',
  ftJoint:'Joint', ftLeft:'Left', ftRight:'Right', ftDiff:'Difference', ftError:'Error', ftLeg:'Stance leg', ftBest:'Best',
  ftLatest:'Latest', ftSway:'Sway', ftEnded:'Ended by', ftTest:'Test', ftPrecision:'Precision', ftActivity:'Activity',
  ftDistance:'Distance', ftDuration:'Duration', ftPace:'Pace', ftSplits:'Kilometres', ftNone:'No field tests yet. They appear after the first phone recording.',
  ftWithin:'within the measuring error', ftNotValidated:'not checked against a reference',
  ftRomNote:'Angle from the pose, held still for at least 1 s. Typical error ±10° (side-view knee, measured); hip and shoulder have no reference. Compare with yourself in the same setup.',
  ftBalanceNote:'The clock stops when the foot touches down, the hips sway too far or the pose is lost. Sway is the mean hip excursion in % of leg length and includes pose noise.',
  ftSpeedNote:'Times come from camera frames, precise to one frame. A cue start includes the reaction and the phone’s unmeasured audio delay.',
  ftCardioNote:'Summary only: distance, duration, pace and kilometre splits. The route never leaves the phone.',
  knee_flexion:'Knee flexion', hip_flexion:'Hip flexion', shoulder_flexion:'Shoulder flexion', shoulder_abduction:'Shoulder abduction',
  touch_down:'touch-down', sway:'sway', time_limit:'60 s', stopped:'stopped', lost_tracking:'pose lost',
  ft_sprint:'Sprint', ft_505:'5-0-5', ft_t_test:'T-test', ft_reaction:'Reaction', ftCue:'cue start', ftCrossing:'line start',
  run:'Run', walk:'Walk', cycle:'Cycle', ftLegL:'Left', ftLegR:'Right', recBalance:'Longest balance', recSprint:'Fastest sprint',
  recAgility:'Fastest agility', ftFieldRecords:'Field tests', ftS:'s', ftMs:'ms', ftM:'m', ftKm:'km'
});
Object.assign(REC_LABEL, {balance_stance:'recBalance', sprint_time:'recSprint', agility_time:'recAgility'});

const ftSeconds = (ms, digits = 2) => new Intl.NumberFormat(locale(), {minimumFractionDigits:digits, maximumFractionDigits:digits}).format(ms / 1000) + ' ' + t('ftS');
const ftClock = seconds => Math.floor(seconds / 60) + ':' + String(Math.round(seconds % 60)).padStart(2, '0');
const ftDeg = value => value == null ? '—' : format(value, 'deg0') + '°';
DIGITS.deg0 = 0;

async function fieldtestsData() {
  const target = athleteTarget();
  return {measurements: target ? await api('/api/measurements?athlete_id=' + target.id + '&limit=500') : []};
}
// Left and right as bars growing outwards from a shared centre on one 0–180° scale.
function ftMirror(left, right) {
  const svg = svgNode('svg', {viewBox:'0 0 240 18', class:'ft-mirror', role:'img',
    'aria-label':t('ftLeft') + ' ' + ftDeg(left) + ', ' + t('ftRight') + ' ' + ftDeg(right)});
  svg.append(svgNode('line', {x1:120, y1:0, x2:120, y2:18, class:'ft-axis'}));
  if (left != null) svg.append(svgNode('rect', {x:120 - left / 180 * 116, y:3, width:left / 180 * 116, height:12, rx:2, class:'ft-left'}));
  if (right != null) svg.append(svgNode('rect', {x:120, y:3, width:right / 180 * 116, height:12, rx:2, class:'ft-right'}));
  return svg;
}
function ftSection(content, title, note, labels) {
  content.append(node('h2', t(title)), node('p', t(note), 'muted'));
  const grid = table(labels, t(title)); grid.wrap.classList.add('club-table', 'ft-table');
  content.append(grid.wrap); return grid.body;
}
function ftRow(body, cells) {
  const tr = node('tr');
  for (const cell of cells) { const td = node('td', typeof cell === 'string' ? cell : null); if (typeof cell !== 'string') td.append(cell); tr.append(td); }
  body.append(tr);
}
function fieldtests(content) {
  const athlete = beginAthleteView(content, 'fieldtests', t('ftTitle'));
  if (!athlete) return;
  content.append(node('p', t('ftIntro'), 'muted'));
  const rows = state.measurements || []; // newest first
  if (!rows.length) { content.append(node('p', t('ftNone'), 'empty')); return; }
  const kind = name => rows.filter(row => row.kind === name);
  const latest = (list, key) => { const seen = new Map(); for (const row of list) if (!seen.has(key(row))) seen.set(key(row), row); return [...seen.values()]; };
  const rom = kind('rom');
  if (rom.length) {
    const body = ftSection(content, 'ftRom', 'ftRomNote', ['ftJoint', 'ftLeft', 'ftRight', 'ftDiff', 'ftError', 'date']);
    for (const row of latest(rom, row => row.joint)) {
      const both = row.left_deg != null && row.right_deg != null;
      const diff = both ? Math.abs(row.left_deg - row.right_deg) : null;
      const name = node('span'); name.append(node('strong', t(row.joint)), ftMirror(row.left_deg, row.right_deg));
      ftRow(body, [name, ftDeg(row.left_deg), ftDeg(row.right_deg),
        diff == null ? '—' : ftDeg(diff) + (diff < row.error_deg ? ' · ' + t('ftWithin') : ''),
        '±' + ftDeg(row.error_deg) + (row.warnings.includes('not_validated') ? ' · ' + t('ftNotValidated') : ''), date(row.created_at)]);
    }
  }
  const balance = kind('balance');
  if (balance.length) {
    const body = ftSection(content, 'ftBalance', 'ftBalanceNote', ['ftLeg', 'ftBest', 'ftLatest', 'ftSway', 'ftEnded', 'date']);
    for (const leg of ['L', 'R']) {
      const list = balance.filter(row => row.leg === leg);
      if (!list.length) continue;
      const best = list.reduce((a, b) => b.stance_ms > a.stance_ms ? b : a);
      ftRow(body, [t('ftLeg' + leg), ftSeconds(best.stance_ms, 1), ftSeconds(list[0].stance_ms, 1),
        list[0].sway_pct == null ? '—' : format(list[0].sway_pct) + ' %', t(list[0].ended_by), date(list[0].created_at)]);
    }
  }
  const timed = rows.filter(row => ['sprint', 'agility', 'reaction'].includes(row.kind));
  if (timed.length) {
    const body = ftSection(content, 'ftSpeed', 'ftSpeedNote', ['ftTest', 'ftBest', 'ftLatest', 'ftPrecision', 'date']);
    const label = row => row.kind === 'sprint' ? t('ft_sprint') + ' ' + format(row.distance_m) + ' ' + t('ftM') + ' · ' + t(row.start === 'cue' ? 'ftCue' : 'ftCrossing')
      : t('ft_' + (row.kind === 'agility' ? row.test : 'reaction'));
    const key = row => row.kind + ':' + (row.distance_m ?? '') + ':' + (row.test ?? '') + ':' + (row.start ?? '');
    for (const first of latest(timed, key)) {
      const list = timed.filter(row => key(row) === key(first));
      const best = list.reduce((a, b) => b.time_ms < a.time_ms ? b : a);
      const digits = first.kind === 'reaction' ? 3 : 2;
      ftRow(body, [label(first), ftSeconds(best.time_ms, digits), ftSeconds(first.time_ms, digits), '±' + format(first.precision_ms, 'deg0') + ' ' + t('ftMs'), date(first.created_at)]);
    }
  }
  const cardio = kind('cardio');
  if (cardio.length) {
    const body = ftSection(content, 'ftCardio', 'ftCardioNote', ['date', 'ftActivity', 'ftDistance', 'ftDuration', 'ftPace', 'ftSplits']);
    for (const row of cardio.slice(0, 10)) {
      ftRow(body, [date(row.created_at), t(row.activity), format(row.distance_m / 1000, 'km2') + ' ' + t('ftKm'), ftClock(row.duration_s),
        row.avg_pace_s_per_km == null ? '—' : ftClock(row.avg_pace_s_per_km) + ' /' + t('ftKm'), row.splits_s.map(ftClock).join(' · ') || '—']);
    }
  }
}
DIGITS.km2 = 2;
// Field-test bests on the records page, next to jump and strength records.
function fieldRecords(records) {
  const list = records.filter(r => r.source === 'measurement');
  if (!list.length) return null;
  const grid = table(['ftTest', 'recBest', 'recPrevious', 'date'], t('ftFieldRecords'));
  grid.wrap.classList.add('club-table');
  for (const r of list) {
    const which = r.measure === 'balance_stance' ? t('ftLeg' + r.leg) : r.measure === 'sprint_time' ? format(r.distance_m) + ' ' + t('ftM') : t('ft_' + r.test);
    const best = node('td', null, 'num'); best.append(node('strong', ftSeconds(r.value, r.measure === 'balance_stance' ? 1 : 2)));
    if (r.new) best.append(' ', node('span', t('recNew'), 'badge record-badge'));
    const tr = node('tr'); tr.append(node('td', t(REC_LABEL[r.measure]) + ' · ' + which), best,
      node('td', r.previous ? ftSeconds(r.previous.value, r.measure === 'balance_stance' ? 1 : 2) + ' · ' + date(r.previous.date) : t('recFirst')),
      node('td', date(r.date)));
    grid.body.append(tr);
  }
  return grid.wrap;
}

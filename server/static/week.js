'use strict';
// The athlete's week (SPEC 10.3) under the Today cards, from GET /api/me/week. Loaded after dashboard.js.
Object.assign(dictionary.bg, {
  wkEyebrow:'Седмицата', wkTitle:'Тази седмица', wkPrev:'Предишна седмица', wkNext:'Следваща седмица',
  wkTraining:'Тренировки', wkDays:'Дни с тренировка', wkSessions:'Тренировки', wkMinutes:'Минути', wkLoad:'Натоварване',
  wkPrevLoad:'миналата седмица: ', wkSets:'Серии', wkVolume:'Обем', wkTop:'Най-често',
  wkConsistency:'Постоянство', wkCheckin:'Ежедневен отчет', wkTrainingDays:'Тренировъчни дни',
  wkOfDays:(done, days) => done + ' от последните ' + days + ' дни', wkLearning:'Започва с първия Ви запис.',
  wkBody:'Самочувствие и храна', wkCheckins:'Дни с отчет', wkWellbeing:'Средно самочувствие', wkReadiness:'Готовност по дни',
  wkFoodDays:'Дни с храна', wkKcal:'Средно ккал', wkProtein:'Средно протеин', wkWeight:'Тегло (тренд)',
  wkRecords:'Рекорди', wkNoRecords:'Няма нови рекорди тази седмица.', wkText:'Обобщение от езиковия модел само от фактите.',
  rd_adjustment:'Не сте на 100%', adj_ill:'неразположение', adj_aches:'болежки', adj_tired:'умора', adj_busy:'натоварен ден',
  adjMode_rest:'почивка днес', adjMode_lighter:'по-леко днес', adjRestDay:'Ден за почивка',
  adjRestNote:'Отбелязахте почивка за днес. Планът се връща утре.', adjLighterBadge:'По-лек вариант'
});
Object.assign(dictionary.en, {
  wkEyebrow:'Your week', wkTitle:'This week', wkPrev:'Previous week', wkNext:'Next week',
  wkTraining:'Training', wkDays:'Training days', wkSessions:'Sessions', wkMinutes:'Minutes', wkLoad:'Load',
  wkPrevLoad:'last week: ', wkSets:'Sets', wkVolume:'Volume', wkTop:'Most done',
  wkConsistency:'Consistency', wkCheckin:'Daily check-in', wkTrainingDays:'Training days',
  wkOfDays:(done, days) => done + ' of the last ' + days + ' days', wkLearning:'Starts with your first entry.',
  wkBody:'Wellbeing and food', wkCheckins:'Check-in days', wkWellbeing:'Average wellbeing', wkReadiness:'Readiness by day',
  wkFoodDays:'Days with food', wkKcal:'Average kcal', wkProtein:'Average protein', wkWeight:'Weight (trend)',
  wkRecords:'Records', wkNoRecords:'No new records this week.', wkText:'Summary by the language model, from the facts only.',
  rd_adjustment:'Not feeling 100%', adj_ill:'unwell', adj_aches:'aches', adj_tired:'tired', adj_busy:'busy day',
  adjMode_rest:'rest today', adjMode_lighter:'lighter today', adjRestDay:'Rest day',
  adjRestNote:'You marked today as a rest day. The plan is back tomorrow.', adjLighterBadge:'Lighter version'
});

function isoWeek(monday) {
  const thursday = new Date(monday.getTime() + 3 * 864e5);
  const year = thursday.getUTCFullYear();
  return year + '-W' + String(Math.floor((thursday - Date.UTC(year, 0, 1)) / 864e5 / 7) + 1).padStart(2, '0');
}

function weekSection(content) {
  const w = state.weekData;
  if (!w) return;
  const section = node('section', null, 'week-section');
  section.id = 'week';
  const head = node('div', null, 'section-head');
  const title = node('div'); title.append(node('h2', t('wkTitle')), node('p', date(w.from) + ' – ' + date(w.to), 'muted'));
  head.append(title);
  const nav = node('div', null, 'actions');
  const monday = new Date(w.from + 'T00:00:00Z');
  const go = days => async () => { state.week = isoWeek(new Date(monday.getTime() + days * 864e5)); await load('today'); };
  const prev = button('wkPrev', go(-7)); prev.id = 'week-prev';
  const next = button('wkNext', go(7)); next.id = 'week-next';
  const today = new Date(apiDay() + 'T00:00:00Z');
  const current = isoWeek(new Date(today - ((today.getUTCDay() + 6) % 7) * 864e5));
  next.disabled = (state.week || current) === current;
  nav.append(prev, next);
  head.append(nav);
  section.append(node('p', t('wkEyebrow'), 'eyebrow'), head);
  if (w.text) section.append(node('p', w.text, 'week-text'), node('small', t('wkText')));
  const grid = node('div', null, 'overview-grid week-grid');
  const line = (label, value) => { const row = node('div', null, 'stat-line'); row.append(node('span', label), node('strong', value)); return row; };
  const tr = w.training;
  const training = overviewCard(t('wkTraining'), formatNumber(tr.days) + ' / 7', t('wkDays'), 'training');
  training.append(line(t('wkSessions'), formatNumber(tr.sessions)), line(t('wkMinutes'), formatNumber(tr.minutes)),
    line(t('wkLoad'), formatNumber(tr.load) + ' ' + t('tdLoadUnit') + ' · ' + t('wkPrevLoad') + formatNumber(tr.load_previous_week)),
    line(t('wkSets'), formatNumber(tr.sets)), line(t('wkVolume'), formatNumber(tr.volume_kg) + ' ' + t('recKg')));
  for (const e of tr.top_exercises.slice(0, 3)) training.append(line(t('wkTop') + ' · ' + e.exercise, e.sets + ' × · ' + formatNumber(e.volume_kg) + ' ' + t('recKg')));
  const habits = overviewCard(t('wkConsistency'), null, '', 'checkin');
  for (const [key, label] of [['checkin', 'wkCheckin'], ['training', 'wkTrainingDays']]) {
    const habit = w.consistency[key];
    const row = line(t(label), habit ? habit.percent + ' %' : '—');
    row.append(node('small', habit ? t('wkOfDays')(habit.done, habit.days) : t('wkLearning')));
    if (habit) { const bar = node('progress'); bar.max = 100; bar.value = habit.percent; bar.setAttribute('aria-label', t(label)); row.append(bar); }
    habits.append(row);
  }
  const body = overviewCard(t('wkBody'), null, '', 'food');
  const r = w.readiness;
  body.append(line(t('wkCheckins'), formatNumber(w.checkins.days) + ' / 7'),
    line(t('wkWellbeing'), w.checkins.wellbeing_avg == null ? '—' : format(w.checkins.wellbeing_avg) + ' / 25'),
    line(t('wkReadiness'), ['good', 'normal', 'take_care', 'still_learning'].filter(k => r[k]).map(k => t('rd_' + k) + ' ' + r[k]).join(' · ') || '—'),
    line(t('wkFoodDays'), formatNumber(w.nutrition.days_logged) + ' / 7'),
    line(t('wkKcal'), w.nutrition.kcal_avg == null ? '—' : formatNumber(w.nutrition.kcal_avg) + (w.nutrition.kcal_target ? ' / ' + formatNumber(w.nutrition.kcal_target) : '')),
    line(t('wkProtein'), w.nutrition.protein_avg == null ? '—' : formatNumber(w.nutrition.protein_avg) + (w.nutrition.protein_target ? ' / ' + formatNumber(w.nutrition.protein_target) : '') + ' g'),
    line(t('wkWeight'), w.weight.end_trend == null ? '—' : format(w.weight.start_trend) + ' → ' + format(w.weight.end_trend) + ' ' + t('recKg')));
  const records = overviewCard(t('wkRecords'), null, w.records.length ? '' : t('wkNoRecords'), 'index');
  for (const rec of w.records.slice(0, 5)) records.append(line(t(REC_LABEL[rec.measure] || 'recTitle') + (rec.exercise ? ' · ' + rec.exercise : ''), recValue(rec)));
  grid.append(training, habits, body, records);
  section.append(grid);
  content.append(section);
}

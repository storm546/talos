'use strict';
const dictionary = {
  bg: {
    language:'Език', title:'Механика на приземяването', intro:'Историята на отбора и промяната спрямо личните базови стойности.',
    signIn:'Вход', signOut:'Изход', email:'Имейл', password:'Парола', name:'Име', role:'Роля', club:'Клуб',
    admin:'Администратор', coach:'Треньор', athlete:'Спортист', team:'Отбор', users:'Потребители', clubs:'Клубове', audit:'Дневник',
    ownHistory:'Моите скокове', loading:'Зареждане…', error:'Заявката не бе изпълнена. Проверете данните и опитайте отново.',
    expired:'Сесията изтече. Влезте отново.', invalidLogin:'Невалиден имейл или парола.', rateLimit:'Твърде много опити. Опитайте след 15 минути.',
    forbidden:'Нямате достъп до това действие.', notFound:'Записът не е намерен.', conflict:'Този запис вече съществува.',
    badInput:'Проверете полетата. Паролата трябва да е поне 8 знака.', saved:'Промените са запазени.',
    jumps:'Скокове', lastJump:'Последен скок', ratio:'Отношение колене/глезени', peakMean:'Средно пиково сгъване (°)',
    asymmetry:'Асиметрия (%)', consent:'Съгласие', recorded:'Записано', missing:'Липсва', record:'Запиши',
    consentBy:'Кой дава съгласие (например име на родител)', consentNote:'Запишете само вече получено съгласие.',
    saveConsent:'Запиши съгласие', revokeConsent:'Оттегли съгласие', cancel:'Отказ', addAthlete:'Добави спортист',
    createUser:'Създай потребител', createClub:'Създай клуб', create:'Създай', changeNote:'Промяна: медиана на последните 3 скока спрямо медианата на първите 3. Стрелките показват посока, а не оценка.',
    baseline:'Базови стойности', provisional:'Базовите стойности са предварителни: нужни са поне 3 скока.',
    history:'История на скоковете', back:'Към отбора', date:'Дата', test:'Тест', fps:'Кадри/сек', flight:'Време на полет (ms)',
    height:'Височина (cm)', contactL:'Сгъване при контакт Л (°)', contactR:'Сгъване при контакт Д (°)',
    peakL:'Пиково сгъване Л (°)', peakR:'Пиково сгъване Д (°)', fppaL:'Ъгъл в челната равнина Л (°)', fppaR:'Ъгъл в челната равнина Д (°)',
    quality:'Видимост', warnings:'Предупреждения', none:'Няма', cmj:'Скок с предварително приклякане', drop_jump:'Скок от платформа с отскок',
    manual_edges:'кадрите са избрани ръчно', low_fps:'Ниска кадрова честота', low_visibility:'Слаба видимост', no_flight_detected:'Не е отчетен полет', uncertain_timing:'Несигурно време на полета', single_leg_view:'Страничен изглед: измерен е само по-добре видимият крак',
    flexion:'Пиково сгъване на коленете (°)', chartNote:'От най-стария към най-новия скок. Пунктираните линии са базовите стойности.',
    legs:'Ляво: плътна линия и кръгчета · Дясно: точкова линия и квадратчета', noData:'Още няма скокове. Качете първия скок от приложението след записване на съгласие.',
    noAthletes:'Няма спортисти. Добавете първия спортист от формуляра по-долу.', csv:'Изтегли CSV', export:'Изтегли моите данни (JSON)',
    delete:'Изтрий моите скокове', deleteNote:'Всички ваши скокове ще бъдат изтрити окончателно. Профилът ви остава.',
    confirmDelete:'Потвърди изтриването', deleted:'Скоковете са изтрити.', active:'Активен', inactive:'Неактивен', status:'Състояние',
    deactivate:'Деактивирай', reactivate:'Активирай', resetPassword:'Нова парола', newPassword:'Нова парола (поне 8 знака)',
    passwordNote:'Всички текущи сесии на потребителя ще бъдат прекратени.', actions:'Действия', action:'Действие', target:'Обект', actor:'Извършител', detail:'Подробности',
    previous:'Предишни', next:'Следващи', noRows:'Няма записи.', guideTitle:'Как да четем числата',
    guide:'Стойностите са приблизителни оценки от една камера на телефон. Следете промяната спрямо личните базови стойности при сходни условия на заснемане. Това са числа за обсъждане с треньор. Видеото остава на телефона.',
    login_success:'Успешен вход', login_failure:'Неуспешен вход', user_created:'Създаден потребител', consent_changed:'Променено съгласие',
    user_activated:'Активиран потребител', user_deactivated:'Деактивиран потребител', password_reset:'Сменена парола',
    csv_export:'CSV експорт', json_export:'JSON експорт', data_deleted:'Изтрити скокове', club_created:'Създаден клуб',
    firstJump:'Първи скок', baselineValue:'Базова стойност', latest:'Последна стойност', recent:'Медиана на последните 3', absolute:'Промяна', percent:'Промяна (%)', metric:'Показател',
    groups:'Групи', sessions:'Сесии', wellbeingView:'Самочувствие', checkins:'Отбелязвания',
    liveOn:'на живо', liveOff:'връзката прекъсна', liveConnecting:'свързване…',
    groupName:'Име на групата', addGroup:'Добави група', createGroup:'Създай група', renameGroup:'Преименувай', group:'Група',
    deleteGroup:'Изтрий', members:'Членове', chooseMembers:'Избери членове', saveMembers:'Запиши членове',
    confirmDeleteGroup:'Изтриването на групата ще превърне нейните сесии в сесии за целия клуб. Това не може да се отмени.',
    deleteGroupConfirm:'Да, изтрий', noGroups:'Няма групи. Създайте първата група.', wholeClub:'Целият клуб',
    sessionTitle:'Заглавие', startsAt:'Начало', place:'Място', trials:'Опити', notes:'Бележки',
    addSession:'Добави сесия', createSession:'Създай сесия', editSession:'Редактирай', saveSession:'Запиши сесия',
    cancelSession:'Откажи', restoreSession:'Възстанови',
    calendar:'Календар (.ics)', upcoming:'Предстоящи', pastSession:'Минала', cancelled:'Отказана', cancelledBadge:'Отказана',
    report:'Доклад', trialsDone:'Изпълнени', trials:'Опити', warningsCount:'Предупреждения', reportTrials:'опит',
    sessionNote:'Сесиите са подредени по начална дата. Отказаните сесии се показват в миналите.',
    noSessions:'Няма сесии.', day:'Дата', wellbeing:'Самочувствие (5–25)', load:'Натоварване', belowUsual:'под обичайното',
    sleep:'Сън', fatigue:'Свежест', soreness:'Без болежки', stress:'Спокойствие', mood:'Настроение', items:'Петте показателя',
    trainingMinutes:'Минути', rpe:'Субективно натоварване', noCheckins:'Няма отбелязвания за този ден.',
    feedback:'Обратна връзка', writeComment:'Напишете коментар', comment:'Коментар', send:'Изпрати',
    referTo:'За кой скок или сесия', aboutJump:'за скок', aboutSession:'за сесия', reply:'Отговори',
    wellbeingChart:'Самочувствие и натоварване', loadLegend:'Точки: самочувствие · квадратчета: натоварване',
    mySessions:'Моите сесии', myCheckins:'Моите отбелязвания', noJumpRef:'Без връзка', noSessionRef:'Без сесия',
    saveCheckin:'Запиши отбелязването', checkinNote:'Оценките са от 1 до 5, където 5 е най-добре. Пишете как се чувствате днес.'
  },
  en: {
    language:'Language', title:'Landing mechanics', intro:'Your team’s history and change against each athlete’s own baseline.',
    signIn:'Sign in', signOut:'Sign out', email:'Email', password:'Password', name:'Name', role:'Role', club:'Club',
    admin:'Admin', coach:'Coach', athlete:'Athlete', team:'Team', users:'Users', clubs:'Clubs', audit:'Audit log',
    ownHistory:'My jumps', loading:'Loading…', error:'Request failed. Check your entries and try again.',
    expired:'Your session expired. Sign in again.', invalidLogin:'Invalid email or password.', rateLimit:'Too many attempts. Try again in 15 minutes.',
    forbidden:'You do not have access to this action.', notFound:'Record not found.', conflict:'This record already exists.',
    badInput:'Check your entries. Passwords need at least 8 characters.', saved:'Changes saved.',
    jumps:'Jumps', lastJump:'Last jump', ratio:'Knee / ankle', peakMean:'Mean peak flexion (°)',
    asymmetry:'Asymmetry (%)', consent:'Consent', recorded:'Recorded', missing:'Missing', record:'Record',
    consentBy:'Who gave consent (for example, a parent’s name)', consentNote:'Record consent only after it has been obtained.',
    saveConsent:'Record consent', revokeConsent:'Withdraw consent', cancel:'Cancel', addAthlete:'Add athlete',
    createUser:'Create user', createClub:'Create club', create:'Create', changeNote:'Change: median of the last 3 jumps against the first 3. Arrows show direction, not a rating.',
    baseline:'Baseline', provisional:'Provisional baseline: at least 3 jumps are needed.',
    history:'Jump history', back:'Back to team', date:'Date', test:'Test', fps:'FPS', flight:'Flight (ms)',
    height:'Height (cm)', contactL:'Contact flexion L (°)', contactR:'Contact flexion R (°)',
    peakL:'Peak flexion L (°)', peakR:'Peak flexion R (°)', fppaL:'Frontal angle L (°)', fppaR:'Frontal angle R (°)',
    quality:'Visibility', warnings:'Warnings', none:'None', cmj:'Countermovement jump', drop_jump:'Drop jump',
    manual_edges:'frames set by hand', low_fps:'Low frame rate', low_visibility:'Low visibility', no_flight_detected:'No flight detected', uncertain_timing:'Uncertain flight timing', single_leg_view:'Side view: only the better-seen leg is measured',
    flexion:'Peak knee flexion (°)', chartNote:'Oldest to newest. Dashed lines show the baseline.',
    legs:'Left: solid line and circles · Right: dotted line and squares', noData:'No jumps yet. Upload the first jump from the app after consent is recorded.',
    noAthletes:'No athletes yet. Add the first athlete using the form below.', csv:'Download CSV', export:'Export my data (JSON)',
    delete:'Delete my jumps', deleteNote:'All your jumps will be permanently deleted. Your account stays.',
    confirmDelete:'Confirm deletion', deleted:'Jumps deleted.', active:'Active', inactive:'Inactive', status:'Status',
    deactivate:'Deactivate', reactivate:'Reactivate', resetPassword:'Reset password', newPassword:'New password (at least 8 characters)',
    passwordNote:'All current sessions for this user will end.', actions:'Actions', action:'Action', target:'Target', actor:'Actor', detail:'Detail',
    previous:'Previous', next:'Next', noRows:'No records.', guideTitle:'How to read these numbers',
    guide:'Values are estimates from one phone camera. Follow change against the athlete’s own baseline under similar filming conditions. These are numbers to discuss with a coach. Video stays on the phone.',
    login_success:'Successful login', login_failure:'Failed login', user_created:'User created', consent_changed:'Consent changed',
    user_activated:'User activated', user_deactivated:'User deactivated', password_reset:'Password reset',
    csv_export:'CSV export', json_export:'JSON export', data_deleted:'Jumps deleted', club_created:'Club created',
    firstJump:'First jump', baselineValue:'Baseline', latest:'Latest', recent:'Last 3 median', absolute:'Change', percent:'Change (%)', metric:'Metric',
    groups:'Groups', sessions:'Sessions', wellbeingView:'Wellbeing', checkins:'Check-ins',
    liveOn:'live', liveOff:'disconnected', liveConnecting:'connecting…',
    groupName:'Group name', addGroup:'Add group', createGroup:'Create group', renameGroup:'Rename', group:'Group',
    deleteGroup:'Delete', members:'Members', chooseMembers:'Choose members', saveMembers:'Save members',
    confirmDeleteGroup:'Deleting the group turns its sessions into club-wide sessions. This cannot be undone.',
    deleteGroupConfirm:'Yes, delete', noGroups:'No groups yet. Create the first group.', wholeClub:'Whole club',
    sessionTitle:'Title', startsAt:'Starts at', place:'Place', trials:'Trials', notes:'Notes',
    addSession:'Add session', createSession:'Create session', editSession:'Edit', saveSession:'Save session',
    cancelSession:'Cancel', restoreSession:'Restore',
    calendar:'Calendar (.ics)', upcoming:'Upcoming', pastSession:'Past', cancelled:'Cancelled', cancelledBadge:'Cancelled',
    report:'Report', trialsDone:'Done', trials:'Trials', warningsCount:'Warnings', reportTrials:'trial',
    sessionNote:'Sessions are ordered by start date. Cancelled sessions appear under past.',
    noSessions:'No sessions.', day:'Day', wellbeing:'Wellbeing (5–25)', load:'Load', belowUsual:'below usual',
    sleep:'Sleep', fatigue:'Freshness', soreness:'No soreness', stress:'Calm', mood:'Mood', items:'The five items',
    trainingMinutes:'Minutes', rpe:'Perceived exertion', noCheckins:'No check-ins for this day.',
    feedback:'Feedback', writeComment:'Write a comment', comment:'Comment', send:'Send',
    referTo:'About a jump or session', aboutJump:'about jump', aboutSession:'about session', reply:'Reply',
    wellbeingChart:'Wellbeing and load', loadLegend:'Dots: wellbeing · squares: load',
    mySessions:'My sessions', myCheckins:'My check-ins', noJumpRef:'No link', noSessionRef:'No session',
    saveCheckin:'Save check-in', checkinNote:'Scores are 1 to 5, where 5 is best. Say how you feel today.'
  }
};
const $ = id => document.getElementById(id);
const state = {language:'bg', token:null, user:null, view:'team', athletes:[], users:[], clubs:[], audit:[], offset:0, selected:null, jumps:[], summary:null, sort:'name', ascending:true, consent:null, reset:null, deleting:false, groups:[], groupEdit:null, membersEdit:null, sessions:[], sessionEdit:null, sessionReport:null, wellbeing:[], wellbeingDay:null, checkins:[], sessionsMine:[], comments:[], live:'idle', liveGeneration:0};
const t = key => dictionary[state.language][key] ?? key;
const locale = () => state.language === 'bg' ? 'bg-BG' : 'en-GB';
// Shown precision follows what a 30-60 fps phone clip can resolve: whole ms, tenths of a degree or cm.
const DIGITS = {flight_time_ms:0, fps:0, knee_ankle_ratio:2, quality:2};
const format = (value, key) => value == null ? '—' : new Intl.NumberFormat(locale(), {maximumFractionDigits:DIGITS[key] ?? 1}).format(value);
const date = value => value ? new Date(value).toLocaleDateString(locale()) : '—';
function apiDay() {
  const now = new Date();
  const parts = new Intl.DateTimeFormat('en-CA', {timeZone:'UTC', year:'numeric', month:'2-digit', day:'2-digit'}).format(now);
  return parts.replace(/\//g, '-');
}
function node(tag, text, className) {const result = document.createElement(tag); if (text != null) result.textContent = text; if (className) result.className = className; return result;}
function message(key) {$('message').textContent = key ? t(key) : '';}
function button(key, action, className='secondary') {
  const result = node('button', t(key), className); result.type = 'button'; result.dataset.action = key;
  result.addEventListener('click', () => run(action, result)); return result;
}
async function run(action, control) {
  if (control) control.disabled = true;
  try {await action();} catch (error) {message(error.message in dictionary.en ? error.message : 'error');}
  finally {if (control) control.disabled = false;}
}
async function api(path, options={}) {
  const headers = {...options.headers}; if (state.token) headers.Authorization = 'Bearer ' + state.token;
  if (options.body) headers['Content-Type'] = 'application/json';
  const response = await fetch(path, {...options, headers, cache:'no-store'});
  if (!response.ok) {
    const code = response.status;
    if (code === 401 && state.token) {signedOut(); throw new Error('expired');}
    throw new Error(({401:'invalidLogin',403:'forbidden',404:'notFound',409:'conflict',422:'badInput',429:'rateLimit'})[code] || 'error');
  }
  if (options.download) return response.blob();
  return response.status === 204 ? null : response.json();
}
async function allPages(path) {
  const result = [];
  for (let offset=0;;offset+=500) {
    const rows = await api(path + (path.includes('?') ? '&' : '?') + 'limit=500&offset=' + offset);
    result.push(...rows); if (rows.length < 500) return result;
  }
}
function setLive(mode) {
  state.live = mode;
  const el = $('live');
  if (!el) return;
  if (!state.user || mode === 'idle') {el.hidden = true; el.className = 'live'; return;}
  el.hidden = false;
  el.className = 'live ' + (mode === 'on' ? 'on' : 'off');
  el.textContent = mode === 'connecting' ? t('liveConnecting') : (mode === 'on' ? t('liveOn') : t('liveOff'));
}
let liveController = null;
async function streamLive() {
  const generation = ++state.liveGeneration;
  let delay = 1000;
  while (state.token && generation === state.liveGeneration) {
    if (liveController) { try { liveController.abort(); } catch {} liveController = null; }
    setLive('connecting');
    const controller = new AbortController();
    liveController = controller;
    const reader = await fetch('/api/events', {headers:{Authorization:'Bearer '+state.token}, cache:'no-store', signal:controller.signal})
      .then(r => { if (!r.ok) return null; return r.body.getReader(); }).catch(() => null);
    if (!reader) { await new Promise(resolve => setTimeout(resolve, delay)); delay = Math.min(delay * 2, 15000); continue; }
    delay = 1000; setLive('on');
    const decoder = new TextDecoder();
    let buffer = '';
    try {
      while (true) {
        const chunk = await reader.read();
        if (chunk.done) break;
        buffer += decoder.decode(chunk.value, {stream:true});
        let split;
        while ((split = buffer.indexOf('\n\n')) !== -1) {
          const block = buffer.slice(0, split); buffer = buffer.slice(split + 2);
          const data = block.split('\n').filter(line => line.startsWith('data:')).map(line => line.slice(5)).join('\n');
          if (data) {
            let event; try {event = JSON.parse(data);} catch {event = null;}
            if (event) refreshFor(event);
          }
        }
        if (generation !== state.liveGeneration) { controller.abort(); return; }
      }
    } catch { if (generation !== state.liveGeneration) return; }
  }
}
function refreshFor(event) {
  const view = state.view;
  if (!relevant(event, view)) return;
  const active = document.activeElement;
  if (active && active.name === 'body' && active.value.trim()) return;  // keep typed, unsent comment
  const activeId = active && active.id;
  const scroll = window.scrollY;
  if (state.user.role === 'athlete' && event.type === 'comment' && view === 'history' &&
      String(event.athlete_id) === String(state.user.id)) {
    api('/api/me/read', {method:'POST'}).catch(() => {});
  }
  load().then(() => {
    window.scrollTo(0, scroll);
    if (activeId) { const el = document.getElementById(activeId); if (el) el.focus(); }
  }).catch(() => {});
}
function relevant(event, view) {
  if (!state.user || !state.token) return false;
  if (view === 'history') {
    const id = state.user.role === 'athlete' ? state.user.id : state.selected && state.selected.id;
    if (id == null) return false;
    if (event.type === 'jump' || event.type === 'checkin' || event.type === 'comment') return String(event.athlete_id) === String(id);
    return false;
  }
  if (view === 'team') return event.type === 'checkin' || event.type === 'jump' || event.type === 'comment';
  if (view === 'wellbeing') return event.type === 'checkin';
  if (view === 'sessions' || view === 'sessionsMine') return event.type === 'session' || event.type === 'jump';
  return false;
}
function signedOut() {
  state.liveGeneration++;
  Object.assign(state, {token:null,user:null,selected:null,athletes:[],users:[],clubs:[],audit:[],jumps:[],summary:null,consent:null,reset:null,deleting:false,groups:[],groupEdit:null,membersEdit:null,sessions:[],sessionEdit:null,sessionReport:null,wellbeing:[],wellbeingDay:null,checkins:[],sessionsMine:[],comments:[],live:'idle'});
  setLive('idle');
  render();
}
let loadVersion = 0;
async function load(view=state.view) {
  const version = ++loadVersion;
  message('loading'); state.consent=null; state.reset=null; state.deleting=false;
  let data;
  const staff = state.user.role !== 'athlete';
  if (view === 'team') data = {athletes:await allPages('/api/athletes')};
  if (view === 'history') {
    const id = state.user.role === 'athlete' ? state.user.id : state.selected.id;
    const [jumps, summary, checkinsList, commentsList, sessionList] = await Promise.all([
      allPages('/api/jumps?athlete_id=' + id), api('/api/athletes/' + id + '/summary'),
      api('/api/checkins?athlete_id=' + id + '&limit=500'), api('/api/comments?athlete_id=' + id + '&limit=500'),
      api('/api/sessions')]);
    data = {jumps,summary,checkins:checkinsList,comments:commentsList,sessionsMine:sessionList};
  }
  if (view === 'users') {
    const [users,clubs] = await Promise.all([allPages('/api/users'),state.user.role === 'admin' ? api('/api/clubs') : Promise.resolve([])]);
    data = {users,clubs};
  }
  if (view === 'clubs') data = {clubs:await api('/api/clubs')};
  if (view === 'audit') data = {audit:await api('/api/audit?limit=50&offset=' + state.offset)};
  if (view === 'groups') {
    const [groups, athletes] = await Promise.all([api('/api/groups'), allPages('/api/athletes')]);
    data = {groups, athletes};
  }
  if (view === 'sessions') {
    const sessions = await allPages('/api/sessions');
    const groups = staff && state.user.role !== 'admin' ? await api('/api/groups') : (state.user.role === 'admin' ? [] : []);
    data = {sessions, groups};
  }
  if (view === 'sessionsMine') data = {sessionsMine:await allPages('/api/sessions')};
  if (view === 'wellbeing') {
    const day = state.wellbeingDay || apiDay();
    data = {wellbeing:await api('/api/checkins/team?day=' + day), wellbeingDay:day};
  }
  if (view === 'checkins') data = {checkins:await allPages('/api/checkins?athlete_id=' + state.user.id)};
  if (version !== loadVersion || !state.user) return;
  Object.assign(state, data, {view}); message(''); render('h1');
}
function heading(title, subtitle) {
  const wrap = node('div',null,'section-head'); const text = node('div');
  const heading=node('h1',title); heading.tabIndex=-1; text.append(heading); if (subtitle) text.append(node('p',subtitle,'muted')); wrap.append(text); return wrap;
}
function field(form, key, name, type='text', value='') {
  const label = node('label',t(key)); const input = node('input'); input.name=name; input.type=type; input.required=true; input.value=value;
  input.maxLength = type === 'password' ? 1024 : type === 'email' ? 254 : 100;
  if (type === 'password') {input.minLength=8; input.autocomplete='new-password';}
  label.append(input); form.append(label); return input;
}
function selectField(form,key,name,choices) {
  const label=node('label',t(key)); const select=node('select');select.name=name;
  for (const [value,text] of choices) {const option=node('option',text);option.value=value;select.append(option);}
  label.append(select);form.append(label);return select;
}
function submit(form,key,action) {
  const control=node('button',t(key));control.type='submit';form.append(control);
  form.addEventListener('submit',event=>{event.preventDefault();run(()=>action(new FormData(form)),control);});
}
function table(labels, caption) {
  const wrap=node('div',null,'table-scroll');wrap.tabIndex=0;wrap.setAttribute('role','region');wrap.setAttribute('aria-label',caption);
  const grid=node('table');const head=node('thead');const row=node('tr');const body=node('tbody');
  for(const label of labels){const cell=node('th',t(label));cell.scope='col';row.append(cell);}
  head.append(row);grid.append(node('caption',caption),head,body);wrap.append(grid);return {wrap,body,head:row};
}
function details(key,content) {const wrap=node('details');wrap.append(node('summary',t(key)),content);return wrap;}
function userForm(athleteOnly=false) {
  const form=node('form');field(form,'name','name');const email=field(form,'email','email','email');email.autocomplete='off';field(form,'newPassword','password','password');
  if (!athleteOnly && state.user.role==='admin') {
    selectField(form,'role','role',['athlete','coach','admin'].map(role=>[role,t(role)]));
    selectField(form,'club','club_id',state.clubs.map(club=>[club.id,club.name]));
  }
  submit(form,'create',async data=>{
    const body=Object.fromEntries(data);body.role=body.role || 'athlete';if(body.club_id)body.club_id=Number(body.club_id);
    await api('/api/users',{method:'POST',body:JSON.stringify(body)});await load();message('saved');
  });return form;
}
function change(value, key) {return value == null ? '—' : (value>0?'↑ ':value<0?'↓ ':'')+format(Math.abs(value), key);}
function consentEditor(athlete) {
  const wrap=node('section',null,'inline-panel');wrap.append(node('h2',t('consent')+' · '+athlete.name),node('p',t('consentNote')));
  const form=node('form');field(form,'consentBy','consent_by','text',athlete.consent_by || '').id='consent-by';
  submit(form,'saveConsent',async data=>{await api('/api/users/'+athlete.id+'/consent',{method:'PATCH',body:JSON.stringify({consent_recorded:true,consent_by:data.get('consent_by')})});await load();message('saved');});
  if(athlete.consent_recorded)form.append(button('revokeConsent',async()=>{
    if(!form.reportValidity())return;
    await api('/api/users/'+athlete.id+'/consent',{method:'PATCH',body:JSON.stringify({consent_recorded:false,consent_by:new FormData(form).get('consent_by')})});await load();message('saved');
  }));
  form.append(button('cancel',()=>{state.consent=null;render('#consent-'+athlete.id);}));wrap.append(form);return wrap;
}
function team(content) {
  content.append(heading(t('team'),t('changeNote')));
  if(state.consent)content.append(consentEditor(state.consent));
  const columns=['name','jumps','lastJump','ratio','peakMean','asymmetry','consent'];
  const keys=['name','jump_count','last_jump_at','knee_ankle_ratio','knee_flexion_peak_mean','flexion_asymmetry_pct','consent_recorded'];
  const metricKeys=keys.slice(3,6);
  const value=(athlete,key)=>metricKeys.includes(key)?athlete.changes[key].change_absolute:athlete[key];
  const rows=[...state.athletes].sort((a,b)=>{
    const x=value(a,state.sort),y=value(b,state.sort);let order=x==null?(y==null?0:1):y==null?-1:typeof x==='string'?x.localeCompare(y,locale()):Number(x)-Number(y);
    return state.ascending?order:-order;
  });
  const grid=table(columns,t('team'));
  [...grid.head.children].forEach((cell,index)=>{
    const key=keys[index];const control=button(columns[index],()=>{state.ascending=state.sort===key?!state.ascending:true;state.sort=key;render('#sort-'+key);});
    control.id='sort-'+key;
    if(state.sort===key)control.append(document.createTextNode(state.ascending?' ↑':' ↓'));
    cell.replaceChildren(control);cell.setAttribute('aria-sort',state.sort===key?(state.ascending?'ascending':'descending'):'none');
  });
  for(const athlete of rows){
    const row=node('tr');const name=node('td');const link=button('athlete',async()=>{state.selected=athlete;await load('history');},'name-button');link.textContent=athlete.name;name.append(link);
    if(!athlete.active)name.append(node('small',t('inactive')));row.append(name,node('td',format(athlete.jump_count)),node('td',date(athlete.last_jump_at)));
    for(const key of metricKeys)row.append(node('td',change(athlete.changes[key].change_absolute,key),'num'));
    const consent=node('td');const actions=node('div',null,'cell-actions');actions.append(node('span',t(athlete.consent_recorded?'recorded':'missing'),'badge'+(athlete.consent_recorded?'':' pending')),
      button(athlete.consent_recorded?'consent':'record',()=>{state.consent=athlete;render('#consent-by');}));actions.lastElementChild.id='consent-'+athlete.id;consent.append(actions);row.append(consent);grid.body.append(row);
  }
  content.append(rows.length?grid.wrap:node('p',t('noAthletes'),'empty'),details('addAthlete',userForm(true)));
}
const historyColumns=[['created_at','date'],['test','test'],['fps','fps'],['flight_time_ms','flight'],['jump_height_cm','height'],['knee_flexion_contact_l','contactL'],['knee_flexion_contact_r','contactR'],['knee_flexion_peak_l','peakL'],['knee_flexion_peak_r','peakR'],['fppa_peak_l','fppaL'],['fppa_peak_r','fppaR'],['knee_ankle_ratio','ratio'],['flexion_asymmetry_pct','asymmetry'],['quality','quality'],['warnings','warnings']];
function svgNode(tag,attributes,text) {
  const element=document.createElementNS('http://www.w3.org/2000/svg',tag);
  for(const [key,value]of Object.entries(attributes))element.setAttribute(key,String(value));
  if(text!=null)element.textContent=text;return element;
}
function chart(title,fields) {
  const figure=node('figure');figure.append(node('figcaption',t(title)));
  const svg=svgNode('svg',{viewBox:'0 0 480 210',role:'img','aria-label':t(title)});const rows=[...state.jumps].reverse();
  const values=rows.flatMap(row=>fields.map(field=>row[field])).filter(value=>value!=null);
  for(const field of fields){const base=state.summary.metrics[field].baseline;if(base!=null)values.push(base);}
  if(!values.length){figure.append(node('p',t('noData')));return figure;}
  const min=Math.max(0,Math.min(...values)-(Math.max(...values)-Math.min(...values)||1)*.2);
  const max=Math.max(...values)+(Math.max(...values)-Math.min(...values)||1)*.2;
  const y=value=>166-(value-min)/(max-min)*140;
  const first=Date.parse(rows[0].created_at),last=Date.parse(rows[rows.length-1].created_at);
  const x=row=>54+(last>first?(Date.parse(row.created_at)-first)/(last-first):.5)*404;
  for(const value of [min,(min+max)/2,max])svg.append(svgNode('line',{x1:54,x2:458,y1:y(value),y2:y(value),class:'chart-grid'}),svgNode('text',{x:46,y:y(value)+4,'text-anchor':'end','font-size':12,fill:'currentColor'},format(value,fields[0])));
  fields.forEach((field,index)=>{
    const base=state.summary.metrics[field].baseline;
    if(base!=null)svg.append(svgNode('line',{x1:54,x2:458,y1:y(base),y2:y(base),class:'baseline-line'+(index?' series-right':'')}));
    // Split at missing values so a chart never invents a measured segment.
    let points=[];
    const draw=()=>{if(points.length)svg.append(svgNode('polyline',{points:points.join(' '),class:'series-line '+(index?'series-right':'series-left')}));points=[];};
    for(const row of rows){if(row[field]==null){draw();continue;}points.push(x(row)+','+y(row[field]));svg.append(index?svgNode('rect',{x:x(row)-3,y:y(row[field])-3,width:6,height:6,class:'right-dot'}):svgNode('circle',{cx:x(row),cy:y(row[field]),r:3,class:'chart-dot'}));}draw();
  });
  svg.append(svgNode('text',{x:54,y:198,'font-size':12,fill:'currentColor'},date(rows[0].created_at)),svgNode('text',{x:458,y:198,'font-size':12,'text-anchor':'end',fill:'currentColor'},date(rows[rows.length-1].created_at)));
  figure.append(svg);if(fields.length>1)figure.append(node('p',t('legs'),'legend'));return figure;
}
async function download(path,filename) {
  const blob=await api(path,{download:true});const url=URL.createObjectURL(blob);const link=node('a');link.href=url;link.download=filename;document.body.append(link);link.click();link.remove();setTimeout(()=>URL.revokeObjectURL(url),1000);
}
function history(content) {
  const own=state.user.role==='athlete';const athlete=own?state.user:state.selected;
  if(!own)content.append(button('back',()=>load('team'),'secondary back'));
  const head=heading(athlete.name,t('history')+' · '+format(state.summary.jump_count)+' · '+t('firstJump')+': '+date(state.summary.first_jump_at));
  const actions=node('div',null,'actions');actions.append(own?button('export',()=>download('/api/me/export','jumpscan-data.json')):button('csv',()=>download('/api/athletes/'+athlete.id+'/export.csv','athlete-'+athlete.id+'.csv')));
  head.append(actions);content.append(head,node('p',t('chartNote'),'muted'));
  if(!state.summary.baseline_complete)content.append(node('p',t('provisional'),'warning'));
  if(state.jumps.length){
    const charts=node('div',null,'charts');charts.append(chart('ratio',['knee_ankle_ratio']),chart('flexion',['knee_flexion_peak_l','knee_flexion_peak_r']),chart('asymmetry',['flexion_asymmetry_pct']),chart('height',['jump_height_cm']));content.append(charts);
    const baselines=table(['metric','baselineValue','latest','recent','absolute','percent'],t('baseline'));
    for(const [key,label]of [['knee_ankle_ratio','ratio'],['knee_flexion_peak_mean','peakMean'],['flexion_asymmetry_pct','asymmetry']]){
      const metric=state.summary.metrics[key];const row=node('tr');row.append(node('td',t(label)));
      for(const field of ['baseline','latest','last_three_median','change_absolute','change_percent'])row.append(node('td',format(metric[field],field==='change_percent'?null:key),'num'));baselines.body.append(row);
    }content.append(details('baseline',baselines.wrap));
    const grid=table(historyColumns.map(([,label])=>label),t('history'));
    for(const jump of state.jumps){const row=node('tr');for(const [key]of historyColumns){const value=jump[key];const cell=node('td',key==='created_at'?date(value):key==='test'?t(value):key==='warnings'?(value.map(t).join(', ')||t('none')):format(value,key));if(key==='warnings'&&value.length)cell.className='warning';row.append(cell);}grid.body.append(row);}content.append(grid.wrap);
  }else content.append(node('p',t('noData'),'empty'));
  if(state.checkins && state.checkins.length)content.append(wellbeingChart());
  // Feedback thread: athlete sees their own replyable thread; staff see the athlete's thread.
  feedbackSection(content, athlete, state.comments || [], own);
  if(own){
    const area=node('section',null,'inline-panel');
    if(state.deleting){area.append(node('p',t('deleteNote')),button('confirmDelete',async()=>{await api('/api/me/data',{method:'DELETE'});await load('history');message('deleted');},'danger'),button('cancel',()=>{state.deleting=false;render('[data-action=delete]');}));}
    else area.append(button('delete',()=>{state.deleting=true;render('[data-action=confirmDelete]');},'secondary'));
    content.append(area);
  }else content.append(details('addAthlete',userForm(true)));
}
// --- club platform (section 5.4) ---
function apiDayOf(d) {
  const parts = new Intl.DateTimeFormat('en-CA', {timeZone:'UTC', year:'numeric', month:'2-digit', day:'2-digit'}).format(d);
  return parts.replace(/\//g, '-');
}
function inputField(form, key, name, type='text', value='', extra={}) {
  const label = node('label', t(key)); const input = node('input'); input.name = name; input.type = type; input.value = value;
  if (extra.required !== false) input.required = true;
  for (const [k, v] of Object.entries(extra)) { if (k === 'required') continue; input[k] = v; }
  label.append(input); form.append(label); return input;
}
function groups(content) {
  content.append(heading(t('groups')));
  if (state.membersEdit != null) content.append(membersEditor(state.membersEdit));
  const wrap = node('div', null, 'list-scroll');
  if (state.groups.length) {
    for (const group of state.groups) {
      const row = node('div', null, 'group-row');
      const name = node('span', group.name, 'name');
      row.append(name);
      const okMembers = group.member_ids || [];
      row.append(node('span', okMembers.length + ' · ' + t('members'), 'group-count'));
      row.append(button('chooseMembers', () => { state.membersEdit = group; render('#members-'+group.id); }, 'secondary'));
      row.append(button('renameGroup', () => { state.groupEdit = group; render('[data-role=group-name]'); }, 'secondary'));
      row.append(button('deleteGroup', () => { if (window.confirm(t('confirmDeleteGroup'))) run(async () => { await api('/api/groups/'+group.id, {method:'DELETE'}); await load(); message('saved'); }); }, 'danger'));
      row.dataset.groupId = group.id;
      wrap.append(row);
    }
  } else content.append(node('p', t('noGroups'), 'empty'));
  content.append(wrap);
  const form = node('form');
  const nameInput = field(form, 'groupName', 'name');
  nameInput.dataset.role = 'group-name';
  if (state.groupEdit) { nameInput.value = state.groupEdit.name; }
  submit(form, state.groupEdit ? 'create' : 'createGroup', async data => {
    const body = {name: String(data.get('name') || '').trim()};
    if (state.groupEdit) await api('/api/groups/'+state.groupEdit.id, {method:'PATCH', body:JSON.stringify(body)});
    else await api('/api/groups', {method:'POST', body:JSON.stringify(body)});
    state.groupEdit = null; state.membersEdit = null; await load(); message('saved');
  });
  if (state.groupEdit) form.append(button('cancel', () => { state.groupEdit = null; render(); }));
  content.append(details(state.groupEdit ? 'createGroup' : 'addGroup', form));
}
function membersEditor(group) {
  const panel = node('section', null, 'inline-panel');
  panel.append(node('h2', t('chooseMembers') + ' · ' + group.name));
  const form = node('form');
  const box = node('div', null, 'member-panel');
  for (const athlete of state.athletes) {
    const label = node('label', null, 'checkbox');
    const input = node('input');
    input.type = 'checkbox'; input.name = 'member'; input.value = String(athlete.id); input.id = 'members-'+group.id;
    input.checked = (group.member_ids || []).includes(athlete.id);
    label.append(input, document.createTextNode(athlete.name));
    box.append(label);
  }
  const none = state.athletes.length ? null : node('p', t('noAthletes'));
  if (none) box.append(none);
  form.append(box);
  submit(form, 'saveMembers', async data => {
    const ids = data.getAll('member').map(Number);
    await api('/api/groups/'+group.id+'/members', {method:'PUT', body:JSON.stringify({athlete_ids:ids})});
    state.membersEdit = null; await load(); message('saved');
  });
  form.append(button('cancel', () => { state.membersEdit = null; render(); }));
  panel.append(form);
  return panel;
}
function groupNameOf(session) {
  if (session.group_id == null) return t('wholeClub');
  const group = state.groups.find(g => g.id === session.group_id);
  return group ? group.name : String(session.group_id);
}
function sessionFormValues(form) {
  const test = form.get('test'); const trials = Number(form.get('trials'));
  // datetime-local is in the browser's timezone; store an explicit UTC timestamp.
  let startsAt = String(form.get('starts_at')||'');
  if (startsAt && !startsAt.endsWith('Z') && !startsAt.includes('+')) {
    const local = new Date(startsAt);
    if (!isNaN(local)) startsAt = local.toISOString();
  }
  return {title: String(form.get('title')||'').trim(), starts_at: startsAt,
    place: String(form.get('place')||''), test, trials, notes: String(form.get('notes')||''),
    group_id: form.get('group_id') ? Number(form.get('group_id')) : null, cancelled: form.get('cancelled') === 'on'};
}
function sessionForm(existing) {
  const form = node('form');
  const isoLocal = (iso) => { const d = new Date(iso); const off = d.getTimezoneOffset(); return new Date(d.getTime() - off*60000).toISOString().slice(0,16); };
  const title = inputField(form, 'sessionTitle', 'title', 'text', existing ? existing.title : '', {maxLength:100});
  title.id = 'session-title';
  const starts = inputField(form, 'startsAt', 'starts_at', 'datetime-local', existing ? isoLocal(existing.starts_at) : '', {});
  const place = inputField(form, 'place', 'place', 'text', existing ? existing.place : '', {maxLength:100, required:false});
  const testSelect = selectField(form, 'test', 'test', [['cmj', t('cmj')], ['drop_jump', t('drop_jump')]]);
  if (existing) testSelect.value = existing.test;
  const trials = selectField(form, 'trials', 'trials', [[1,1],[2,2],[3,3],[4,4],[5,5]]);
  trials.value = String(existing ? existing.trials : 3);
  const notes = node('textarea'); notes.name = 'notes'; notes.maxLength = 1000; notes.rows = 3;
  if (existing) notes.value = existing.notes || '';
  const notesLabel = node('label', t('notes')); notesLabel.append(notes); form.append(notesLabel);
  if (state.user.role !== 'athlete' && state.groups.length) {
    const groupSel = selectField(form, 'group', 'group_id', [['', t('wholeClub')], ...state.groups.map(g => [String(g.id), g.name])]);
    groupSel.required = false;
    if (existing) groupSel.value = existing.group_id == null ? '' : String(existing.group_id);
  }
  if (existing) {
    const cancel = node('label', null, 'checkbox'); const cb = node('input'); cb.type = 'checkbox'; cb.name = 'cancelled'; cb.checked = !!existing.cancelled;
    cancel.append(cb, document.createTextNode(t('cancelledBadge'))); form.append(cancel);
  }
  return form;
}
function sortSessions(list) {
  return [...list].sort((a, b) => a.starts_at < b.starts_at ? -1 : a.starts_at > b.starts_at ? 1 : (a.id - b.id));
}
function cancelFrom(session) {
  return {title: session.title, starts_at: session.starts_at, place: session.place || '', test: session.test,
    trials: session.trials, notes: session.notes || '', group_id: session.group_id, cancelled: !session.cancelled};
}
function sessions(content) {
  const staff = state.user.role !== 'athlete';
  content.append(heading(t('sessions'), t('sessionNote')));

  if (state.sessionEdit) {
    const panel = node('section', null, 'inline-panel');
    panel.append(node('h2', state.sessionEdit.id ? t('editSession') : t('createSession')));
    const form = sessionForm(state.sessionEdit.id ? state.sessionEdit : null);
    submit(form, state.sessionEdit.id ? 'saveSession' : 'createSession', async data => {
      const body = sessionFormValues(data);
      const isEdit = !!state.sessionEdit.id;
      if (isEdit) { await api('/api/sessions/'+state.sessionEdit.id, {method:'PATCH', body:JSON.stringify(body)}); }
      else { await api('/api/sessions', {method:'POST', body:JSON.stringify(body)}); }
      state.sessionEdit = null; state.sessionReport = null; await load(); message('saved');
    });
    panel.append(form);
    panel.append(button('cancel', () => { state.sessionEdit = null; render(); }));
    content.append(panel);
    return;
  }

  if (staff) content.append(button('addSession', () => { state.sessionEdit = {id: null}; render('#session-title'); }));

  if (!state.sessions.length) { content.append(node('p', t('noSessions'), 'empty')); return; }
  const upcoming = sortSessions(state.sessions.filter(s => !s.cancelled && new Date(s.starts_at) >= new Date()));
  const past = sortSessions(state.sessions.filter(s => s.cancelled || new Date(s.starts_at) < new Date()));
  if (upcoming.length) { content.append(node('h2', t('upcoming'))); content.append(sessionTable(upcoming, staff)); }
  if (past.length) { content.append(node('h2', t('pastSession'))); content.append(sessionTable(past, staff)); }

  const tools = node('div', null, 'actions');
  const calendar = button('calendar', () => download('/api/me/calendar.ics', 'jumpscan.ics')); calendar.id = 'calendar-download';
  tools.append(calendar);
  content.append(tools);

  if (state.sessionReport) {
    const report = state.sessionReport;
    const panel = node('section', null, 'inline-panel report-panel');
    const head = node('div', null, 'inline-actions');
    head.append(node('h2', t('report')), button('cancel', () => { state.sessionReport = null; render(); }));
    panel.append(head);
    panel.append(reportGrid(report));
    content.append(panel);
  }
}
function reportGrid(report) {
  const grid = table(['name','trialsDone','ratio','peakL','peakR','asymmetry','height'], t('report'));
  for (const row of report.athletes) {
    const tr = node('tr');
    tr.append(node('td', row.name));
    tr.append(node('td', row.trials_done + ' / ' + row.trials, 'num'));
    tr.append(node('td', format(row.knee_ankle_ratio, 'knee_ankle_ratio'), 'num'));
    tr.append(node('td', format(row.knee_flexion_peak_l, 'knee_flexion_peak_l'), 'num'));
    tr.append(node('td', format(row.knee_flexion_peak_r, 'knee_flexion_peak_r'), 'num'));
    tr.append(node('td', format(row.flexion_asymmetry_pct, 'flexion_asymmetry_pct'), 'num'));
    tr.append(node('td', format(row.jump_height_cm, 'jump_height_cm'), 'num'));
    grid.body.append(tr);
  }
  return grid.wrap;
}
function sessionTable(rows, staff) {
  const grid = table(['sessionTitle','date','group','test','trials','jumps', ...(staff ? ['actions'] : [])], t('sessions'));
  for (const session of rows) {
    const tr = node('tr');
    const titleTd = node('td', session.title);
    if (session.cancelled) titleTd.append(node('span', ' · ' + t('cancelledBadge'), 'badge pending'));
    tr.append(titleTd);
    tr.append(node('td', new Date(session.starts_at).toLocaleString(locale())));
    tr.append(node('td', groupNameOf(session)));
    tr.append(node('td', t(session.test)));
    tr.append(node('td', String(session.trials), 'num'));
    tr.append(node('td', String(session.jump_count || 0), 'num'));
    if (staff) {
      const cell = node('td'); const actions = node('div', null, 'cell-actions');
      actions.append(button('editSession', () => { state.sessionEdit = session; state.sessionReport = null; render('#session-title'); }));
      actions.append(button('report', async () => { const report = await api('/api/sessions/'+session.id+'/report'); state.sessionReport = report; render('#calendar-download'); }));
      actions.append(button(session.cancelled ? 'restoreSession' : 'cancelSession', async () => { await api('/api/sessions/'+session.id, {method:'PATCH', body:JSON.stringify(cancelFrom(session))}); await load(); message('saved'); }));
      cell.append(actions); tr.append(cell);
    }
    grid.body.append(tr);
  }
  return grid.wrap;
}
function sessionsMine(content) {
  content.append(heading(t('mySessions')));
  if (!state.sessionsMine.length) { content.append(node('p', t('noSessions'), 'empty')); return; }
  const grid = table(['sessionTitle','date','place','test','trials','jumps'], t('mySessions'));
  for (const session of sortSessions(state.sessionsMine)) {
    const tr = node('tr');
    const title = node('td', session.title);
    if (session.cancelled) title.append(node('span', ' · ' + t('cancelledBadge'), 'badge pending'));
    tr.append(title);
    tr.append(node('td', new Date(session.starts_at).toLocaleString(locale())));
    tr.append(node('td', session.place || '—'));
    tr.append(node('td', t(session.test)));
    tr.append(node('td', String(session.trials), 'num'));
    tr.append(node('td', String(session.jump_count || 0), 'num'));
    grid.body.append(tr);
  }
  content.append(grid.wrap, (() => { const b = button('calendar', () => download('/api/me/calendar.ics', 'jumpscan.ics')); return b; })());
}
function wellbeing(content) {
  content.append(heading(t('wellbeingView')));
  const day = state.wellbeingDay || apiDay();
  const picker = node('div', null, 'daypicker');
  const input = node('input'); input.type = 'date'; input.value = day; input.id = 'wellbeing-day';
  picker.append(node('label', t('day')), input);
  content.append(picker);
  const week = node('div', null, 'week');
  for (let i = 0; i < 7; i++) {
    const d = new Date(); d.setUTCDate(d.getUTCDate() - i);
    const dayStr = apiDayOf(d);
    const b = button(null, () => { state.wellbeingDay = dayStr; load('wellbeing'); });
    b.textContent = new Intl.DateTimeFormat(locale(), {weekday:'short', day:'numeric', month:'numeric'}).format(d);
    b.setAttribute('aria-pressed', String(dayStr === day));
    b.classList.add('secondary');
    week.append(b);
  }
  content.append(week);
  input.addEventListener('change', () => { state.wellbeingDay = input.value; load('wellbeing'); });
  const grid = table(['name','wellbeing','load','items'], t('wellbeingView'));
  for (const row of state.wellbeing) {
    const tr = node('tr');
    tr.append(node('td', row.name));
    if (row.checkin == null) {
      tr.append(node('td', '—', 'num'), node('td', '—', 'num'), node('td', '—'));
    } else {
      const c = row.checkin;
      const well = node('td', String(row.wellbeing), 'num rated');
      if (row.below_usual) well.append(node('span', ' · ' + t('belowUsual'), 'below'));
      tr.append(well);
      tr.append(node('td', row.load == null ? '—' : formatNumber(row.load), 'num rated'));
      tr.append(node('td', [c.sleep, c.fatigue, c.soreness, c.stress, c.mood].join(' · '), 'num rated'));
    }
    grid.body.append(tr);
  }
  content.append(grid.wrap);
}
function formatNumber(value) {
  return new Intl.NumberFormat(locale(), {maximumFractionDigits: 0}).format(value);
}
function wellbeingChart() {
  const figure = node('figure');
  figure.append(node('figcaption', t('wellbeingChart')));
  const days = [...state.checkins].sort((a, b) => a.day < b.day ? -1 : a.day > b.day ? 1 : 0);
  const points = days.map(c => ({day: c.day, w: wellbeingSum(c), l: c.rpe != null && c.training_minutes != null ? c.rpe * c.training_minutes : null}));
  const hasW = points.some(p => p.w != null); const hasL = points.some(p => p.l != null);
  if (!points.length || (!hasW && !hasL)) { figure.append(node('p', t('noCheckins'))); return figure; }
  const svg = svgNode('svg', {viewBox:'0 0 480 210', role:'img', 'aria-label':t('wellbeingChart')});
  const wVals = points.map(p => p.w).filter(v => v != null);
  const lVals = points.map(p => p.l).filter(v => v != null);
  const allVals = [...wVals, ...lVals];
  const min = Math.min(...allVals); const max = Math.max(...allVals);
  const span = (max - min) || 1;
  const y = v => 166 - (v - min) / span * 140;
  const x = (i) => points.length === 1 ? 54 + 202 : 54 + i / (points.length - 1) * 404;
  for (const v of [min, (min+max)/2, max]) svg.append(svgNode('line', {x1:54, x2:458, y1:y(v), y2:y(v), class:'chart-grid'}), svgNode('text', {x:46, y:y(v)+4, 'text-anchor':'end', 'font-size':12, fill:'currentColor'}, formatNumber(v)));
  let wPath = []; let lPath = [];
  points.forEach((p, i) => {
    if (p.w != null) { wPath.push(x(i)+','+y(p.w)); svg.append(svgNode('circle', {cx:x(i), cy:y(p.w), r:3, class:'chart-dot'})); }
    if (p.l != null) { lPath.push(x(i)+','+y(p.l)); svg.append(svgNode('rect', {x:x(i)-3, y:y(p.l)-3, width:6, height:6, class:'right-dot'})); }
  });
  if (wPath.length > 1) svg.append(svgNode('polyline', {points:wPath.join(' '), class:'series-line series-left'}));
  if (lPath.length > 1) svg.append(svgNode('polyline', {points:lPath.join(' '), class:'series-line series-right'}));
  if (points.length > 1) svg.append(svgNode('text', {x:54, y:198, 'font-size':12, fill:'currentColor'}, date(points[0].day)), svgNode('text', {x:458, y:198, 'text-anchor':'end', 'font-size':12, fill:'currentColor'}, date(points[points.length-1].day)));
  figure.append(svg);
  if (hasW && hasL) figure.append(node('p', t('loadLegend'), 'legend'));
  return figure;
}
function wellbeingSum(c) {
  const items = [c.sleep, c.fatigue, c.soreness, c.stress, c.mood];
  return items.every(v => v != null) ? items.reduce((a, b) => a + b, 0) : (items.some(v => v != null) ? items.filter(v => v != null).reduce((a, b) => a + b, 0) : null);
}
function checkinRowDetail(c) {
  return [c.sleep, c.fatigue, c.soreness, c.stress, c.mood].map(v => v == null ? '—' : v).join(' · ');
}
function checkins(content) {
  content.append(heading(t('myCheckins'), t('checkinNote')));
  const grid = table(['date','wellbeing','load','items'], t('myCheckins'));
  if (!state.checkins.length) { content.append(node('p', t('noCheckins'), 'empty')); return; }
  for (const c of [...state.checkins].sort((a, b) => a.day < b.day ? -1 : a.day > b.day ? 1 : 0)) {
    const tr = node('tr');
    tr.append(node('td', date(c.day)));
    tr.append(node('td', formatNumber(wellbeingSum(c)), 'num rated'));
    tr.append(node('td', c.rpe != null && c.training_minutes != null ? formatNumber(c.rpe * c.training_minutes) : '—', 'num rated'));
    tr.append(node('td', checkinRowDetail(c), 'num rated'));
    grid.body.append(tr);
  }
  content.append(grid.wrap);
  if (state.checkins.length) content.append(wellbeingChart());
}
function selectInput(labelKey, name, choices) {
  const label = node('label', t(labelKey)); const select = node('select'); select.name = name; select.required = false;
  for (const [value, text] of choices) { const option = node('option', text); option.value = value; select.append(option); }
  label.append(select); return label;
}
function feedbackSection(content, athlete, comments, isOwn) {
  const section = node('section', null, 'inline-panel');
  section.append(node('h2', t('feedback') + ' · ' + athlete.name));
  const thread = node('div', null, 'thread');
  for (const c of comments) {
    const item = node('div', null, 'comment');
    const meta = node('div', c.author_name + ' · ' + t(c.author_role), 'meta');
    meta.append(document.createTextNode(' · ' + new Date(c.created_at).toLocaleString(locale())));
    if (isOwn && c.author_id !== state.user.id && c.read_at == null) meta.append(document.createTextNode(' · ' + t('missing')));
    item.append(meta);
    const jump = state.jumps.find(j => j.id === c.jump_id);
    if (c.jump_id != null) { const r = node('div', t('aboutJump') + ': ' + (jump ? (t(jump.test) + ' ' + date(jump.created_at)) : String(c.jump_id)), 'ref'); item.append(r); }
    const session = (state.sessionsMine || []).find(s => s.id === c.session_id);
    if (c.session_id != null) { const r = node('div', t('aboutSession') + ': ' + (session ? session.title : String(c.session_id)), 'ref'); item.append(r); }
    item.append(node('div', c.body, 'body'));
    thread.append(item);
  }
  if (!comments.length) thread.append(node('p', t('noRows')));
  section.append(thread);
  if (isOwn && state.user.role === 'athlete') api('/api/me/read', {method:'POST'}).catch(() => {});
  const form = node('form', null, 'comment-box');
  const textarea = node('textarea');
  textarea.name = 'body'; textarea.maxLength = 1000; textarea.required = true; textarea.placeholder = t('writeComment');
  textarea.id = 'comment-body';
  const bodyLabel = node('label', t('comment')); bodyLabel.append(textarea);
  form.append(bodyLabel);
  const refs = node('div', null, 'comment-refs');
  const jumpSel = selectInput('referTo', 'jump_id', [['', t('noJumpRef')], ...state.jumps.map(j => [String(j.id), t(j.test) + ' ' + date(j.created_at)])]);
  const sessSel = selectInput('referTo', 'session_id', [['', t('noSessionRef')], ...(state.sessionsMine || []).map(s => [String(s.id), s.title])]);
  refs.append(jumpSel, sessSel);
  form.append(refs);
  const send = node('button', t('send')); send.type = 'submit';
  form.addEventListener('submit', async event => {
    event.preventDefault();
    const body = String(textarea.value).trim(); if (!body) { textarea.focus(); return; }
    const jumpId = form.querySelector('[name=jump_id]').value;
    const sessionId = form.querySelector('[name=session_id]').value;
    const bodyObj = {athlete_id: athlete.id, body};
    if (jumpId) bodyObj.jump_id = Number(jumpId);
    if (sessionId) bodyObj.session_id = Number(sessionId);
    await api('/api/comments', {method:'POST', body:JSON.stringify(bodyObj)});
    await load('history');
    message('saved');
  });
  form.append(send);
  section.append(form);
  content.append(section);
}
function users(content) {
  content.append(heading(t('users')));
  if(state.reset){const form=node('form');field(form,'newPassword','password','password').id='reset-password';form.append(node('p',t('passwordNote')));submit(form,'resetPassword',async data=>{await api('/api/users/'+state.reset.id+'/password',{method:'POST',body:JSON.stringify(Object.fromEntries(data))});if(state.reset.id===state.user.id){signedOut();message('expired');}else{await load();message('saved');}});form.append(button('cancel',()=>{const id=state.reset.id;state.reset=null;render('#reset-'+id);}));const panel=node('section',null,'inline-panel');panel.append(node('h2',t('resetPassword')+' · '+state.reset.name),form);content.append(panel);}
  const grid=table(['name','email','role','club','status','actions'],t('users'));
  for(const user of state.users){const row=node('tr');row.append(node('td',user.name),node('td',user.email),node('td',t(user.role)),node('td',state.clubs.find(club=>club.id===user.club_id)?.name||String(user.club_id)),node('td',t(user.active?'active':'inactive')));
    const cell=node('td');const actions=node('div',null,'cell-actions');
    if(state.user.role==='admin'||user.role==='athlete'){
      if(user.id!==state.user.id)actions.append(button(user.active?'deactivate':'reactivate',async()=>{await api('/api/users/'+user.id,{method:'PATCH',body:JSON.stringify({active:!user.active})});await load();message('saved');}));
      const reset=button('resetPassword',()=>{state.reset=user;render('#reset-password');});reset.id='reset-'+user.id;actions.append(reset);
    }cell.append(actions);row.append(cell);grid.body.append(row);
  }content.append(grid.wrap,details(state.user.role==='admin'?'createUser':'addAthlete',userForm()));
}
function clubs(content) {
  content.append(heading(t('clubs')));const grid=table(['club'],t('clubs'));
  for(const club of state.clubs){const row=node('tr');row.append(node('td',club.name));grid.body.append(row);}content.append(grid.wrap);
  const form=node('form');field(form,'name','name');submit(form,'createClub',async data=>{await api('/api/clubs',{method:'POST',body:JSON.stringify(Object.fromEntries(data))});await load();message('saved');});content.append(details('createClub',form));
}
function audit(content) {
  content.append(heading(t('audit')));const grid=table(['date','actor','action','target','detail'],t('audit'));
  for(const entry of state.audit){const row=node('tr');row.append(node('td',new Date(entry.at).toLocaleString(locale())),node('td',entry.actor_email??(entry.actor_id==null?'—':String(entry.actor_id))),node('td',t(entry.action)),node('td',entry.target),node('td',Object.keys(entry.detail).length?JSON.stringify(entry.detail):'—','audit-detail'));grid.body.append(row);}content.append(state.audit.length?grid.wrap:node('p',t('noRows'),'empty'));
  const paging=node('div',null,'actions pagination');const previous=button('previous',()=>{state.offset=Math.max(0,state.offset-50);return load();});previous.disabled=!state.offset;const next=button('next',()=>{state.offset+=50;return load();});next.disabled=state.audit.length<50;paging.append(previous,next);content.append(paging);
}
function render(focusSelector=null) {
  document.documentElement.lang=state.language;document.title='JumpScan · '+t('title');$('language-label').textContent=t('language');$('language').value=state.language;
  const content=$('content');content.replaceChildren();$('session').replaceChildren();$('guide').replaceChildren(node('h2',t('guideTitle')),node('p',t('guide')));
  if(!state.user){
    content.append(heading(t('title'),t('intro')));const panel=node('section',null,'login');panel.append(node('h2',t('signIn')));
    const form=node('form');form.id='login-form';const email=field(form,'email','email','email');email.id='email';email.autocomplete='username';const password=field(form,'password','password','password');password.id='password';password.minLength=1;password.autocomplete='current-password';
    submit(form,'signIn',async data=>{message('loading');const result=await api('/api/auth/login',{method:'POST',body:JSON.stringify(Object.fromEntries(data))});password.value='';state.token=result.token;state.user=result.user;state.offset=0;streamLive();try{await load(state.user.role==='athlete'?'history':'team');}catch(error){signedOut();throw error;}});panel.append(form);content.append(panel);return;
  }
  $('session').append(node('span',state.user.name+' · '+t(state.user.role)),button('signOut',async()=>{await api('/api/auth/logout',{method:'POST'});loadVersion++;signedOut();message('');}));
  const nav=node('nav');nav.setAttribute('aria-label',t('title'));
  const views=state.user.role==='athlete'?[['history','ownHistory'],['sessionsMine','mySessions'],['checkins','myCheckins']]:[['team','team'],['groups','groups'],['sessions','sessions'],['wellbeing','wellbeingView'],['users','users'],...(state.user.role==='admin'?[['clubs','clubs'],['audit','audit']]:[])];
  for(const [view,key]of views){const control=button(key,()=>load(view));if(state.view===view||(state.view==='history'&&view==='team'))control.setAttribute('aria-current','page');nav.append(control);}content.append(nav);
  ({team,history,users,clubs,audit,groups,sessions,sessionsMine,wellbeing,checkins})[state.view](content);
  setLive(state.live);
  if(focusSelector)content.querySelector(focusSelector)?.focus();
}
$('language').addEventListener('change',event=>{state.language=event.target.value;message('');render();});
render();

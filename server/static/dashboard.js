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
    firstJump:'Първи скок', baselineValue:'Базова стойност', latest:'Последна стойност', recent:'Медиана на последните 3', absolute:'Промяна', percent:'Промяна (%)', metric:'Показател'
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
    firstJump:'First jump', baselineValue:'Baseline', latest:'Latest', recent:'Last 3 median', absolute:'Change', percent:'Change (%)', metric:'Metric'
  }
};
const $ = id => document.getElementById(id);
const state = {language:'bg', token:null, user:null, view:'team', athletes:[], users:[], clubs:[], audit:[], offset:0, selected:null, jumps:[], summary:null, sort:'name', ascending:true, consent:null, reset:null, deleting:false};
const t = key => dictionary[state.language][key] ?? key;
const locale = () => state.language === 'bg' ? 'bg-BG' : 'en-GB';
// Shown precision follows what a 30-60 fps phone clip can resolve: whole ms, tenths of a degree or cm.
const DIGITS = {flight_time_ms:0, fps:0, knee_ankle_ratio:2, quality:2};
const format = (value, key) => value == null ? '—' : new Intl.NumberFormat(locale(), {maximumFractionDigits:DIGITS[key] ?? 1}).format(value);
const date = value => value ? new Date(value).toLocaleDateString(locale()) : '—';
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
function signedOut() {
  Object.assign(state, {token:null,user:null,selected:null,athletes:[],users:[],clubs:[],audit:[],jumps:[],summary:null,consent:null,reset:null,deleting:false});
  render();
}
let loadVersion = 0;
async function load(view=state.view) {
  const version = ++loadVersion;
  message('loading'); state.consent=null; state.reset=null; state.deleting=false;
  let data;
  if (view === 'team') data = {athletes:await allPages('/api/athletes')};
  if (view === 'history') {
    const id = state.user.role === 'athlete' ? state.user.id : state.selected.id;
    const [jumps, summary] = await Promise.all([allPages('/api/jumps?athlete_id=' + id),api('/api/athletes/' + id + '/summary')]);
    data = {jumps,summary};
  }
  if (view === 'users') {
    const [users,clubs] = await Promise.all([allPages('/api/users'),state.user.role === 'admin' ? api('/api/clubs') : Promise.resolve([])]);
    data = {users,clubs};
  }
  if (view === 'clubs') data = {clubs:await api('/api/clubs')};
  if (view === 'audit') data = {audit:await api('/api/audit?limit=50&offset=' + state.offset)};
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
  if(own){
    const area=node('section',null,'inline-panel');
    if(state.deleting){area.append(node('p',t('deleteNote')),button('confirmDelete',async()=>{await api('/api/me/data',{method:'DELETE'});await load('history');message('deleted');},'danger'),button('cancel',()=>{state.deleting=false;render('[data-action=delete]');}));}
    else area.append(button('delete',()=>{state.deleting=true;render('[data-action=confirmDelete]');},'secondary'));
    content.append(area);
  }else content.append(details('addAthlete',userForm(true)));
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
    submit(form,'signIn',async data=>{message('loading');const result=await api('/api/auth/login',{method:'POST',body:JSON.stringify(Object.fromEntries(data))});password.value='';state.token=result.token;state.user=result.user;state.offset=0;try{await load(state.user.role==='athlete'?'history':'team');}catch(error){signedOut();throw error;}});panel.append(form);content.append(panel);return;
  }
  $('session').append(node('span',state.user.name+' · '+t(state.user.role)),button('signOut',async()=>{await api('/api/auth/logout',{method:'POST'});loadVersion++;signedOut();message('');}));
  const nav=node('nav');nav.setAttribute('aria-label',t('title'));
  const views=state.user.role==='athlete'?[['history','ownHistory']]:[['team','team'],['users','users'],...(state.user.role==='admin'?[['clubs','clubs'],['audit','audit']]:[])];
  for(const [view,key]of views){const control=button(key,()=>load(view));if(state.view===view||(state.view==='history'&&view==='team'))control.setAttribute('aria-current','page');nav.append(control);}content.append(nav);
  ({team,history,users,clubs,audit})[state.view](content);
  if(focusSelector)content.querySelector(focusSelector)?.focus();
}
$('language').addEventListener('change',event=>{state.language=event.target.value;message('');render();});
render();

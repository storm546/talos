'use strict';
const dictionary = {
  bg: {
    language:'Език', title:'Анализ на скокове и приземяване', intro:'Историята на отбора и промяната спрямо първите скокове на всеки спортист.',
    signIn:'Вход', signOut:'Изход', email:'Имейл', password:'Парола', name:'Име', role:'Роля', club:'Клуб',
    admin:'Администратор', coach:'Треньор', athlete:'Спортист', team:'Отбор', users:'Потребители', clubs:'Клубове', audit:'Дневник',
    ownHistory:'Моите скокове', loading:'Зареждане…', error:'Заявката не бе изпълнена. Проверете данните и опитайте отново.',
    expired:'Сесията изтече. Влезте отново.', invalidLogin:'Невалиден имейл или парола.', rateLimit:'Твърде много опити. Опитайте след 15 минути.',
    forbidden:'Нямате достъп до това действие.', notFound:'Записът не е намерен.', conflict:'Този запис вече съществува.',
    badInput:'Проверете полетата. Паролата трябва да е поне 8 знака.', saved:'Промените са запазени.',
    jumps:'Скокове', lastJump:'Последен скок', ratio:'Разстояние между коленете спрямо глезените', peakMean:'Средно максимално сгъване (°)',
    asymmetry:'Разлика между коленете (%)', consent:'Съгласие', recorded:'Записано', missing:'Липсва', record:'Запишете',
    consentBy:'Кой дава съгласие (например име на родител)', consentNote:'Запишете само вече получено съгласие.',
    saveConsent:'Запишете съгласие', revokeConsent:'Оттеглете съгласие', cancel:'Отказ', addAthlete:'Добавете спортист',
    createUser:'Създайте потребител', createClub:'Създайте клуб', create:'Създайте', changeNote:'Сравнени са средните по големина стойности от последните и първите три скока. Стрелките показват посока, не оценка.',
    baseline:'Сравнение с първите скокове', provisional:'Нужни са поне три скока за надеждно лично сравнение.',
    history:'История на скоковете', back:'Към отбора', date:'Дата', test:'Тест', fps:'Кадри/сек', flight:'Време във въздуха (мс)',
    height:'Височина (см)', contactL:'Сгъване при контакт Л (°)', contactR:'Сгъване при контакт Д (°)',
    peakL:'Максимално сгъване Л (°)', peakR:'Максимално сгъване Д (°)', fppaL:'Ъгъл в челната равнина Л (°)', fppaR:'Ъгъл в челната равнина Д (°)',
    quality:'Видимост', warnings:'Предупреждения', none:'Няма', cmj:'Скок от място с приклякане', drop_jump:'Стъпване от платформа и веднага скок',
    manual_edges:'кадрите са избрани ръчно', low_fps:'Ниска кадрова честота', low_visibility:'Слаба видимост', no_flight_detected:'Отскокът и приземяването не са потвърдени', uncertain_timing:'Несигурно време на полета', single_leg_view:'Страничен изглед: измерен е само по-добре видимият крак',
    flexion:'Максимално сгъване на коленете (°)', chartNote:'От най-стария към най-новия скок. Пунктираните линии са базовите стойности.',
    legs:'Ляво: плътна линия и кръгчета · Дясно: точкова линия и квадратчета', noData:'Още няма скокове. Качете първия скок от приложението след записване на съгласие.',
    noAthletes:'Няма спортисти. Добавете първия спортист от формуляра по-долу.', csv:'Изтеглете таблица', export:'Изтеглете данните си',
    delete:'Изтрийте моите скокове', deleteNote:'Всички Ваши скокове ще бъдат изтрити окончателно. Профилът Ви остава.',
    confirmDelete:'Потвърдете изтриването', deleted:'Скоковете са изтрити.', active:'Активен', inactive:'Неактивен', status:'Състояние',
    deactivate:'Деактивирайте', reactivate:'Активирайте', resetPassword:'Нова парола', newPassword:'Нова парола (поне 8 знака)',
    passwordNote:'Всички текущи сесии на потребителя ще бъдат прекратени.', actions:'Действия', action:'Действие', target:'Обект', actor:'Извършител', detail:'Подробности',
    previous:'Предишни', next:'Следващи', noRows:'Няма записи.', guideTitle:'Как да четем числата',
    guide:'Стойностите са приблизителни оценки от една камера на телефон. Ако отношението е под 1, коленете са по-близо едно до друго от глезените. Следете промяната спрямо първите скокове на всеки спортист при сходни условия на заснемане. Видеото остава на телефона.',
    login_success:'Успешен вход', login_failure:'Неуспешен вход', user_created:'Създаден потребител', consent_changed:'Променено съгласие',
    user_activated:'Активиран потребител', user_deactivated:'Деактивиран потребител', password_reset:'Сменена парола',
    csv_export:'Изнесена таблица', json_export:'Изнесени данни', data_deleted:'Изтрити скокове', club_created:'Създаден клуб',
    firstJump:'Първи скок', baselineValue:'Първи три скока', latest:'Последна стойност', recent:'Средно по големина от последните три скока', absolute:'Промяна', percent:'Промяна (%)', metric:'Показател',
    groups:'Групи', sessions:'Сесии', wellbeingView:'Самочувствие', checkins:'Отбелязвания',
    liveOn:'на живо', liveOff:'връзката прекъсна', liveConnecting:'свързване…',
    groupName:'Име на групата', addGroup:'Добавете група', createGroup:'Създайте група', renameGroup:'Преименувайте', group:'Група',
    deleteGroup:'Изтрийте', members:'Членове', chooseMembers:'Изберете членове', saveMembers:'Запишете членове',
    confirmDeleteGroup:'Изтриването на групата ще превърне нейните сесии в сесии за целия клуб. Това не може да се отмени.',
    deleteGroupConfirm:'Да, изтрийте', noGroups:'Няма групи. Създайте първата група.', wholeClub:'Целият клуб',
    sessionTitle:'Заглавие', startsAt:'Начало', place:'Място', trials:'Опити', notes:'Бележки',
    addSession:'Добавете сесия', createSession:'Създайте сесия', editSession:'Редактирайте', saveSession:'Запишете сесия',
    cancelSession:'Откажете', restoreSession:'Възстановете',
    calendar:'Календар (.ics)', upcoming:'Предстоящи', pastSession:'Минала', cancelled:'Отказана', cancelledBadge:'Отказана',
    report:'Доклад', trialsDone:'Изпълнени', trials:'Опити', warningsCount:'Предупреждения', reportTrials:'опит',
    sessionNote:'Сесиите са подредени по начална дата. Отказаните сесии се показват в миналите.',
    noSessions:'Няма сесии.', day:'Дата', wellbeing:'Самочувствие (5–25)', load:'Натоварване', belowUsual:'под обичайното',
    sleep:'Сън', fatigue:'Свежест', soreness:'Мускулна болка', stress:'Напрежение', mood:'Настроение', items:'Петте показателя',
    trainingMinutes:'Минути', rpe:'Усещане за усилие (0–10)', noCheckins:'Няма отбелязвания за този ден.',
    feedback:'Бележки от треньора', writeComment:'Напишете коментар', comment:'Коментар', send:'Изпратете',
    referTo:'За кой скок или сесия', aboutJump:'за скок', aboutSession:'за сесия', reply:'Отговорете',
    wellbeingChart:'Самочувствие и натоварване', loadLegend:'Точки: самочувствие · квадратчета: натоварване',
    mySessions:'Моите сесии', myCheckins:'Моите отбелязвания', noJumpRef:'Без връзка', noSessionRef:'Без сесия',
    saveCheckin:'Запишете отбелязването', checkinNote:'Оценките са от 1 до 5, където 5 е най-добре. Пишете как се чувствате днес.',
    training:'Тренировки', nutrition:'Хранене', formView:'Техника', plans:'Планове', exercisesTitle:'Упражнения',
    assistant:'Помощник с изкуствен интелект', sectionLinks:'Раздели', selectAthleteFirst:'Изберете състезател от „Отбор“.',
    weekdays:['Пон','Вт','Ср','Чет','Пет','Съб','Нед'],
    assignedPlan:'Назначен план', weekday:'Ден', exercise:'Упражнение', setsCol:'Серии', repsRange:'Повторения',
    loadKg:'Тежест (кг)', rpeTarget:'Целево усилие (0–10)', setHistory:'Дневник на сериите', setNo:'Серия №', repsDone:'Повторения',
    progressionTitle:'Предложение за прогресия', suggestion:'Предложение', planned:'Планирано',
    increase:'Увеличение', decrease:'Намаление', keep:'Без промяна', suggestedLoad:'Предложена тежест', fromLoad:'От',
    top_of_range_twice:'Горна граница два пъти', below_range_twice:'Под долната граница два пъти',
    need_two_sessions:'Нужни са две тренировки', no_change:'Без промяна',
    weeklyVolume:'Седмичен обем', weekCol:'Седмица', volumeKg:'Обем (кг)', unloadedSets:'Серии без тежест',
    sessionLoadCol:'Тренировъчно натоварване', trainingNote:'Предложенията не се прилагат автоматично. Обсъдете ги с треньора.',
    noTraining:'Още няма тренировъчни данни.', noPlan:'Няма назначен план.', noPlans:'Няма планове.',
    noExercises:'Няма упражнения.', addPlan:'Добавете план', createPlan:'Създайте план', editPlan:'Редактирайте',
    deletePlan:'Изтрийте', savePlan:'Запишете план', assignTo:'Назначете на', startsOn:'Начална дата', weeksCol:'Седмици',
    addItem:'Добавете ред', removeItem:'Премахнете', addExercise:'Добавете упражнение', kind:'Вид', lowerBody:'Долна част',
    strength:'Сила', plyometric:'Плиометрия', mobility:'Подвижност', conditioning:'Кондиция', confirmDeletePlan:'Да изтрия ли плана?',
    foodLog:'Хранителен дневник', meal:'Хранене', breakfast:'Закуска', lunch:'Обяд', dinner:'Вечеря', snack:'Междинно',
    food:'Храна', grams:'Грамове', kcal:'Калории', protein:'Белтъчини (г)', carbs:'Въглехидрати (г)', fat:'Мазнини (г)',
    fibre:'Фибри (г)', dailyTotals:'Общо за деня', nutritionTarget:'Дневна цел', remaining:'Остава', perMeal:'По хранения',
    weeklyAverage:'Средно за 7 дни', bodyweight:'Телесно тегло', trendLine:'Тренд', weightKg:'Тегло (кг)',
    expenditure:'Разход на енергия', expenditureNote:'Адаптивна оценка от Вашите записи, не измерване.',
    programTitle:'Програма', goal:'Цел', maintain:'Поддържане', lose:'Сваляне', gain:'Качване', rate:'Темп (кг/седмица)',
    recommended:'Препоръчано', appliedBadge:'Приложено', notAppliedBadge:'Не е приложено', estimateKcal:'Оценен разход',
    proteinRange:'Препоръчан белтък', setTarget:'Задайте цел', setProgram:'Задайте цел и темп', dayComplete:'Отбележете деня завършен',
    under18:'Под 18 г.', noProgram:'Няма програма. Задайте цел и темп.', notEnoughData:'Недостатъчно записи за оценка.',
    guardrailNote:'Препоръката не минава правилата (под 18 г. или под 1500 ккал) и не е записана — обсъдете с треньора.',
    nutritionNote:'Обсъдете тези стойности с треньора си.', noNutrition:'Още няма данни за хранене.',
    formHistoryTitle:'История на техниката', squat:'Клек', single_leg_squat:'Клек на един крак', repetition:'Повторение',
    depth:'Дълбочина (°)', fppa:'Ъгъл в челната равнина (°)', repStatus:'Състояние', complete:'Пълно', partial:'Частично',
    unclear:'Неясно', repsCount:'Повторения', partialCount:'Частични', unclearCount:'Неясни', worstRep:'Най-слабо',
    side:'Страна', viewLabel:'Изглед', front:'Отпред', sideView:'Отстрани', unclear_reps:'Неясни повторения',
    hipBelowKnee:'Таз под коляното', noForm:'Още няма записи на техника.', formWarnNote:'Частичните и неясните повторения се броят отделно.',
    assistantIntro:'Питайте за тренировки, хранене и тестове. Всяка промяна се потвърждава от Вас.',
    assistantPlaceholder:'Напишете съобщение…', messageLabel:'Съобщение', ask:'Попитайте', assistantReply:'Отговор',
    proposalTitle:'Предложени действия', proposalNote:'Нищо не се записва, докато не потвърдите.', confirm:'Потвърдете',
    assistantUnavailable:'Помощникът с изкуствен интелект не е наличен в момента. Опитайте по-късно.', assistantApplied:'Действията са приложени.',
    proposalGone:'Предложението е използвано или изтекло.', assistantError:'Помощникът не успя да отговори.'
  },
  en: {
    language:'Language', title:'Jump and landing analysis', intro:'See how each athlete’s results have changed since their first jumps.',
    signIn:'Sign in', signOut:'Sign out', email:'Email', password:'Password', name:'Name', role:'Role', club:'Club',
    admin:'Admin', coach:'Coach', athlete:'Athlete', team:'Team', users:'Users', clubs:'Clubs', audit:'Audit log',
    ownHistory:'My jumps', loading:'Loading…', error:'Request failed. Check your entries and try again.',
    expired:'Your session expired. Sign in again.', invalidLogin:'Invalid email or password.', rateLimit:'Too many attempts. Try again in 15 minutes.',
    forbidden:'You do not have access to this action.', notFound:'Record not found.', conflict:'This record already exists.',
    badInput:'Check your entries. Passwords need at least 8 characters.', saved:'Changes saved.',
    jumps:'Jumps', lastJump:'Last jump', ratio:'Knee spacing compared with ankle spacing', peakMean:'Average deepest knee bend (°)',
    asymmetry:'Difference between knees (%)', consent:'Consent', recorded:'Recorded', missing:'Missing', record:'Record',
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
    guide:'Values are estimates from one phone camera. A ratio below 1 means the knees are closer together than the ankles. Compare with the athlete’s first jumps filmed under similar conditions. Video stays on the phone.',
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
    saveCheckin:'Save check-in', checkinNote:'Scores are 1 to 5, where 5 is best. Say how you feel today.',
    training:'Training', nutrition:'Nutrition', formView:'Form', plans:'Plans', exercisesTitle:'Exercises',
    assistant:'AI assistant', sectionLinks:'Sections', selectAthleteFirst:'Choose an athlete from Team first.',
    weekdays:['Mon','Tue','Wed','Thu','Fri','Sat','Sun'],
    assignedPlan:'Assigned plan', weekday:'Day', exercise:'Exercise', setsCol:'Sets', repsRange:'Reps',
    loadKg:'Load (kg)', rpeTarget:'Target RPE', setHistory:'Set log', setNo:'Set #', repsDone:'Reps',
    progressionTitle:'Progression suggestion', suggestion:'Suggestion', planned:'Planned',
    increase:'Increase', decrease:'Decrease', keep:'Keep', suggestedLoad:'Suggested load', fromLoad:'From',
    top_of_range_twice:'Top of range twice', below_range_twice:'Below range twice',
    need_two_sessions:'Two sessions needed', no_change:'No change',
    weeklyVolume:'Weekly volume', weekCol:'Week', volumeKg:'Volume (kg)', unloadedSets:'Sets without load',
    sessionLoadCol:'Session load (RPE)', trainingNote:'Suggestions are not applied automatically. Discuss them with your coach.',
    noTraining:'No training data yet.', noPlan:'No plan assigned.', noPlans:'No plans yet.',
    noExercises:'No exercises yet.', addPlan:'Add plan', createPlan:'Create plan', editPlan:'Edit',
    deletePlan:'Delete', savePlan:'Save plan', assignTo:'Assign to', startsOn:'Start date', weeksCol:'Weeks',
    addItem:'Add row', removeItem:'Remove', addExercise:'Add exercise', kind:'Type', lowerBody:'Lower body',
    strength:'Strength', plyometric:'Plyometric', mobility:'Mobility', conditioning:'Conditioning', confirmDeletePlan:'Delete this plan?',
    foodLog:'Food log', meal:'Meal', breakfast:'Breakfast', lunch:'Lunch', dinner:'Dinner', snack:'Snack',
    food:'Food', grams:'Grams', kcal:'Calories', protein:'Protein (g)', carbs:'Carbs (g)', fat:'Fat (g)',
    fibre:'Fibre (g)', dailyTotals:'Daily totals', nutritionTarget:'Daily target', remaining:'Remaining', perMeal:'By meal',
    weeklyAverage:'7-day average', bodyweight:'Bodyweight', trendLine:'Trend', weightKg:'Weight (kg)',
    expenditure:'Energy expenditure', expenditureNote:'An adaptive estimate from your own logs, not a measurement.',
    programTitle:'Program', goal:'Goal', maintain:'Maintain', lose:'Lose', gain:'Gain', rate:'Rate (kg/week)',
    recommended:'Recommended', appliedBadge:'Applied', notAppliedBadge:'Not applied', estimateKcal:'Estimated expenditure',
    proteinRange:'Protein range', setTarget:'Set target', setProgram:'Set goal and rate', dayComplete:'Mark day complete',
    under18:'Under 18', noProgram:'No program yet. Set a goal and weekly rate.', notEnoughData:'Not enough logged data for an estimate.',
    guardrailNote:'The recommendation was not saved (under 18 or below 1,500 kcal). Discuss it with your coach.',
    nutritionNote:'Use these numbers to start a conversation with your coach.', noNutrition:'No nutrition data yet.',
    formHistoryTitle:'Form history', squat:'Squat', single_leg_squat:'Single-leg squat', repetition:'Repetition',
    depth:'Depth (°)', fppa:'Frontal angle (°)', repStatus:'Status', complete:'Complete', partial:'Partial',
    unclear:'Unclear', repsCount:'Reps', partialCount:'Partial', unclearCount:'Unclear', worstRep:'Worst',
    side:'Side', viewLabel:'View', front:'Front', sideView:'Side', unclear_reps:'Unclear reps',
    hipBelowKnee:'Hips below knee', noForm:'No form sets yet.', formWarnNote:'Partial and unclear repetitions are counted separately.',
    assistantIntro:'Ask about training, nutrition and testing. Every change is confirmed by you.',
    assistantPlaceholder:'Type a message…', messageLabel:'Message', ask:'Ask', assistantReply:'Reply',
    proposalTitle:'Proposed actions', proposalNote:'Nothing is saved until you confirm.', confirm:'Confirm',
    assistantUnavailable:'The AI assistant is unavailable right now. Try again later.', assistantApplied:'The actions were applied.',
    proposalGone:'The proposal was already used or has expired.', assistantError:'The assistant could not reply.'
  }
};
const $ = id => document.getElementById(id);
Object.assign(dictionary.bg, {
  today:'Днес', overview:'Преглед', coachDesk:'Работно табло на треньора', athleteDesk:'Табло на спортиста', adminDesk:'Преглед на клубовете',
  roster:'Списък на спортистите', checkedToday:'Отбелязали се днес', uncheckedToday:'Без отбелязване днес', todayDone:'Отбелязването е записано',
  testingDue:'За довършване', sessionRemaining:'Не са изпълнили всички опити в последната сесия.', noneDue:'Всички опити са изпълнени.',
  lastSession:'Последна сесия', noCompletedSession:'Още няма проведена сесия.', compare:'Промяна при приземяване',
  increasedAsymmetry:'По-голяма разлика между коленете спрямо първите скокове. Спортисти: ',
  noIncrease:'Няма отчетено увеличение на разликата между коленете.', percentagePoints:'процентни пункта',
  todayPlan:'Днешен план', todayRest:'Няма планирани упражнения за днес.', latestJump:'Последен скок',
  dashboardIntro:'Проследявайте скоковете, техниката и тренировките на едно място.', theme:'Сменете темата',
  themeDark:'Тъмна тема', themeLight:'Светла тема', holdTime:'Време на задържане (сек)', inBand:'Време в диапазона (сек)',
  sourceLive:'На живо', sourceVideo:'Запис', sideLeft:'Ляво', sideRight:'Дясно',
  noTargetShort:'Няма зададена цел', openAppCheckin:'Отбележете как се чувствате в приложението JumpScan.',
  mealBreakdown:'Подробности за храненията', kcalUnit:'ккал', gramUnit:'г', descentMs:'Спускане (мс)', ascentMs:'Изправяне (мс)',
  checkinToday:'Как сте днес?', checkinTotal:'Общо за сън, свежест, мускулен комфорт, спокойствие и настроение.',
  attemptsOf:' от ', effortShort:'усилие'
});
Object.assign(dictionary.en, {
  today:'Today', overview:'Overview', coachDesk:'Coach workspace', athleteDesk:'Athlete workspace', adminDesk:'Club overview',
  roster:'Athlete roster', checkedToday:'Checked in today', uncheckedToday:'Not checked in today', todayDone:'Check-in saved',
  testingDue:'Testing to finish', sessionRemaining:'Still have attempts left from the last session.', noneDue:'All attempts are complete.',
  lastSession:'Last session', noCompletedSession:'No past sessions yet.', compare:'Change in landing',
  increasedAsymmetry:'The knees bend less evenly than on the first jumps. Athletes: ',
  noIncrease:'No increase in the difference between knees.', percentagePoints:'percentage points',
  todayPlan:'Today’s plan', todayRest:'Nothing scheduled for today.', latestJump:'Latest jump',
  dashboardIntro:'Track jumps, technique and training in one place.', theme:'Switch theme',
  themeDark:'Dark theme', themeLight:'Light theme', holdTime:'Hold time (s)', inBand:'Time in range (s)',
  sourceLive:'Live', sourceVideo:'Video', sideLeft:'Left', sideRight:'Right',
  noTargetShort:'No target set', openAppCheckin:'Use the JumpScan app to check in.',
  mealBreakdown:'Meal breakdown', kcalUnit:'kcal', gramUnit:'g', descentMs:'Descent (ms)', ascentMs:'Ascent (ms)',
  checkinToday:'How you feel today', checkinTotal:'Total for sleep, freshness, muscle comfort, calm and mood.',
  attemptsOf:' of ', effortShort:'effort'
});
const state = {language:'bg', token:null, user:null, view:'team', athletes:[], users:[], clubs:[], audit:[], offset:0, selected:null, jumps:[], summary:null, sort:'name', ascending:true, consent:null, reset:null, deleting:false, groups:[], groupEdit:null, membersEdit:null, sessions:[], sessionEdit:null, sessionReport:null, wellbeing:[], wellbeingDay:null, checkins:[], sessionsMine:[], comments:[], live:'idle', liveGeneration:0,
  training:null, setLogs:[], exercises:[], plansData:[], nutritionDay:null, nutSummary:null, weightsData:null,
  expenditure:null, program:null, nutTarget:null, foodLogs:[], foods:[], formSets:[], planEdit:null,
  assistant:{open:false, conversationId:null, reply:null, proposal:null, note:null, error:null}};
const t = key => dictionary[state.language][key] ?? key;
const locale = () => state.language === 'bg' ? 'bg-BG' : 'en-GB';
// Shown precision follows what a 30-60 fps phone clip can resolve: whole ms, tenths of a degree or cm.
const DIGITS = {flight_time_ms:0, fps:0, knee_ankle_ratio:2, quality:2};
const format = (value, key) => value == null ? '—' : new Intl.NumberFormat(locale(), {maximumFractionDigits:DIGITS[key] ?? 1}).format(value);
const date = value => value ? new Date(value.length === 10 ? value + 'T12:00:00' : value)
  .toLocaleDateString(locale(), {year:'numeric', month:'2-digit', day:'2-digit'}).replace(/\s*г\.+$/, '') : '—';
const time = value => new Date(value).toLocaleTimeString(locale(), {hour:'2-digit', minute:'2-digit'});
const dateTime = value => value ? date(value) + ', ' + time(value) : '—';
// Two-line date for session tables: date above a small time, so the column stays narrow.
function sessionWhen(iso) {
  const cell = node('td', null, 'nowrap');
  cell.append(document.createTextNode(date(iso)), node('br'), node('small', time(iso)));
  return cell;
}
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
    throw new Error(({401:'invalidLogin',403:'forbidden',404:'notFound',409:'conflict',422:'badInput',429:'rateLimit',503:'assistantUnavailable'})[code] || 'error');
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
  let reconnect = false;
  while (state.token && generation === state.liveGeneration) {
    if (liveController) { try { liveController.abort(); } catch {} liveController = null; }
    setLive('connecting');
    const controller = new AbortController();
    liveController = controller;
    const reader = await fetch('/api/events', {headers:{Authorization:'Bearer '+state.token}, cache:'no-store', signal:controller.signal})
      .then(r => { if (!r.ok) return null; return r.body.getReader(); }).catch(() => null);
    if (!reader) { await new Promise(resolve => setTimeout(resolve, delay)); delay = Math.min(delay * 2, 15000); continue; }
    if (generation !== state.liveGeneration) { controller.abort(); return; }
    delay = 1000; setLive('on');
    if (reconnect) refreshFor({type:'resync'});
    reconnect = true;
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
            if (event && event.type !== 'resync') refreshFor(event);
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
  if (active && active.id === 'assistant-input' && active.value.trim()) return;  // keep typed assistant message
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
  if (event.type === 'resync') return true;
  if (view === 'history') {
    const id = state.user.role === 'athlete' ? state.user.id : state.selected && state.selected.id;
    if (id == null) return false;
    if (event.type === 'jump' || event.type === 'checkin' || event.type === 'comment') return String(event.athlete_id) === String(id);
    return false;
  }
  if (view === 'team') return ['checkin','jump','session'].includes(event.type);
  if (view === 'today') return ['checkin','jump','food','coaching','nutrition_day','set'].includes(event.type) &&
    String(event.athlete_id) === String(state.user.id);
  if (view === 'wellbeing') return event.type === 'checkin';
  if (view === 'sessions' || view === 'sessionsMine') return event.type === 'session' || event.type === 'jump';
  if (view === 'training' || view === 'nutrition' || view === 'form') {
    const target = athleteTarget(); if (!target) return false;
    const map = {training:['set','jump','checkin'], nutrition:['food','weight','coaching','nutrition_day','checkin'], form:['form']};
    return map[view].includes(event.type) && String(event.athlete_id) === String(target.id);
  }
  return false;
}
function signedOut() {
  state.liveGeneration++;
  liveController?.abort();
  Object.assign(state, {todayCheckins:[], lastSession:null, lastReport:null, formCatalogue:[]});
  Object.assign(state, {token:null,user:null,selected:null,athletes:[],users:[],clubs:[],audit:[],jumps:[],summary:null,consent:null,reset:null,deleting:false,groups:[],groupEdit:null,membersEdit:null,sessions:[],sessionEdit:null,sessionReport:null,wellbeing:[],wellbeingDay:null,checkins:[],sessionsMine:[],comments:[],live:'idle',training:null,setLogs:[],exercises:[],plansData:[],nutritionDay:null,nutSummary:null,weightsData:null,expenditure:null,program:null,nutTarget:null,foodLogs:[],foods:[],formSets:[],planEdit:null,assistant:{open:false,conversationId:null,reply:null,proposal:null,note:null,error:null}});
  setLive('idle');
  render();
}
function athleteTarget() {
  return state.user && state.user.role === 'athlete' ? state.user : state.selected;
}
let loadVersion = 0;
async function load(view=state.view) {
  const version = ++loadVersion;
  message('loading'); state.consent=null; state.reset=null; state.deleting=false;
  let data;
  const staff = state.user.role !== 'athlete';
  if (view === 'team') {
    const [athletes, todayCheckins, sessions] = await Promise.all([
      allPages('/api/athletes'), api('/api/checkins/team?day=' + apiDay()), allPages('/api/sessions')]);
    const lastSession = sessions.filter(session => !session.cancelled && Date.parse(session.starts_at) <= Date.now())
      .sort((first, second) => Date.parse(second.starts_at) - Date.parse(first.starts_at))[0];
    const lastReport = lastSession ? await api('/api/sessions/' + lastSession.id + '/report') : null;
    data = {athletes, todayCheckins, lastSession, lastReport};
  }
  if (view === 'today') {
    const id = state.user.id;
    const day = apiDay();
    const [summary, jumps, checkins, nutSummary, plansData] = await Promise.all([
      api('/api/athletes/' + id + '/summary'), api('/api/jumps?athlete_id=' + id + '&limit=1'),
      api('/api/checkins?athlete_id=' + id + '&from=' + day + '&to=' + day),
      api('/api/athletes/' + id + '/nutrition/summary?day=' + day), api('/api/plans')]);
    data = {summary, jumps, checkins, nutSummary, plansData};
  }
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
  if (view === 'training') {
    const target = athleteTarget();
    if (target) {
      const id = target.id;
      const [training, plansData, setLogs, exercises] = await Promise.all([
        api('/api/athletes/' + id + '/training?weeks=4'), api('/api/plans'),
        api('/api/set-logs?athlete_id=' + id + '&limit=500'), api('/api/exercises')]);
      data = {training, plansData, setLogs, exercises};
    } else data = {training:null, plansData:[], setLogs:[], exercises:[]};
  }
  if (view === 'plans') {
    const [plansData, exercises, athletes, groups] = await Promise.all([
      api('/api/plans'), api('/api/exercises'), allPages('/api/athletes'), api('/api/groups')]);
    data = {plansData, exercises, athletes, groups};
  }
  if (view === 'nutrition') {
    const target = athleteTarget();
    if (target) {
      const id = target.id;
      const day = state.nutritionDay || apiDay();
      const [nutSummary, weightsData, expenditure, program, nutTarget, foodLogs] = await Promise.all([
        api('/api/athletes/' + id + '/nutrition/summary?day=' + day), api('/api/athletes/' + id + '/weights'),
        api('/api/athletes/' + id + '/nutrition/expenditure'), api('/api/athletes/' + id + '/nutrition/program'),
        api('/api/athletes/' + id + '/nutrition/target'), api('/api/food-logs?athlete_id=' + id + '&limit=500')]);
      data = {nutSummary, weightsData, expenditure, program, nutTarget, foodLogs, nutritionDay:day};
    } else data = {nutSummary:null, weightsData:null, expenditure:null, program:null, nutTarget:null, foodLogs:[]};
  }
  if (view === 'form') {
    const target = athleteTarget();
    const [formSets, formCatalogue] = target ? await Promise.all([
      api('/api/form?athlete_id=' + target.id + '&limit=100'),
      api('/api/form/exercises').catch(error => { if (error.message === 'notFound') return []; throw error; })]) : [[], []];
    data = {formSets, formCatalogue};
  }
  if (version !== loadVersion || !state.user) return;
  Object.assign(state, data, {view}); message(''); render('h1');
}
function heading(title, subtitle) {
  const wrap = node('div',null,'section-head'); const text = node('div');
  const heading=node('h1',title); heading.tabIndex=-1; text.append(heading); if (subtitle) text.append(node('p',subtitle,'muted')); wrap.append(text); return wrap;
}
function overviewHero(label, title, description) {
  const hero = node('section', null, 'overview-hero');
  hero.append(node('p', label, 'eyebrow'), heading(title, description));
  return hero;
}
function overviewCard(label, value, detail, icon, valueStyle = 'big-value') {
  const card = node('section', null, 'overview-card');
  const glyph = node('span', icon, 'card-glyph'); glyph.setAttribute('aria-hidden', 'true');
  card.append(glyph, node('h2', label));
  if (value && value !== '—') card.append(node('p', value, valueStyle));
  if (detail) card.append(node('p', detail, 'muted'));
  return card;
}
function athleteLink(athlete, name) {
  const link = button('athlete', async () => {
    Object.assign(state, {selected:athlete, nutritionDay:null});
    await load('history');
  }, name ? 'name-button overview-link' : 'name-button roster-link');
  link.textContent = name || athlete.name;
  return link;
}
function teamOverview(content) {
  const records = state.todayCheckins;
  const checked = records.filter(record => record.checkin);
  const due = (state.lastReport?.athletes || []).filter(row => row.trials_done < row.trials);
  const increased = state.athletes.filter(athlete => athlete.active && athlete.changes.flexion_asymmetry_pct.change_absolute >= .05);
  const cards = node('div', null, 'overview-grid team-overview');
  const checkin = overviewCard(t('checkedToday'), format(checked.length) + ' / ' + format(records.length), '', '✓');
  if (records.length) {
    const list = document.createElement('ul'); list.className = 'status-list';
    for (const row of records) {
      const item = node('li');
      item.append(node('span', row.name), node('span', t(row.checkin ? 'recorded' : 'missing'), 'badge' + (row.checkin ? '' : ' pending')));
      list.append(item);
    }
    checkin.append(details('checkins', list));
  }
  const session = overviewCard(t('lastSession'), state.lastSession?.title,
    state.lastSession ? dateTime(state.lastSession.starts_at) : t('noCompletedSession'), '◷', 'card-subject');
  const testing = overviewCard(t('testingDue'), state.lastReport ? format(due.length) : '—',
    state.lastReport ? t(due.length ? 'sessionRemaining' : 'noneDue') : t('noCompletedSession'), '↗');
  for (const row of due) {
    const athlete = state.athletes.find(item => item.id === row.athlete_id);
    if (athlete) {
      const link = athleteLink(athlete, athlete.name);
      link.append(node('span', row.trials_done + t('attemptsOf') + row.trials + ' ' + t('trials').toLocaleLowerCase(locale()), 'change-unit'));
      testing.append(link);
    }
  }
  const flags = overviewCard(t('compare'), null,
    increased.length ? t('increasedAsymmetry') + format(increased.length) : t('noIncrease'), '≋');
  for (const athlete of increased) {
    const link = athleteLink(athlete, athlete.name);
    link.append(node('span', change(athlete.changes.flexion_asymmetry_pct.change_absolute, 'flexion_asymmetry_pct'), 'change-unit'));
    flags.append(link);
  }
  cards.append(checkin, session, testing, flags);
  content.append(cards);
}
function today(content) {
  content.append(overviewHero(t('athleteDesk'), t('today'), state.user.name + '  ·  ' + date(apiDay())));
  athleteSubnav(content, 'today');
  const grid = node('div', null, 'overview-grid today-grid');
  const saved = state.checkins[0];
  const checkin = overviewCard(t('checkinToday'), saved ? wellbeingSum(saved) + '\u00a0/\u00a025' : null,
    t(saved ? 'checkinTotal' : 'openAppCheckin'), '✓');
  const day = apiDay();
  const weekday = (new Date(day + 'T00:00:00Z').getUTCDay() + 6) % 7;
  const scheduled = state.plansData.filter(plan => {
    const distance = (Date.parse(day) - Date.parse(plan.starts_on)) / 86400000;
    return distance >= 0 && distance < plan.weeks * 7 && plan.items.some(item => item.weekday === weekday);
  });
  const training = overviewCard(t('todayPlan'), scheduled[0]?.title, scheduled.length ? '' : t('todayRest'), '≋', 'card-subject');
  for (const plan of scheduled) for (const item of plan.items.filter(item => item.weekday === weekday)) {
    training.append(node('p', item.exercise + ' · ' + item.sets + ' × ' + item.reps +
      (item.reps_max > item.reps ? '–' + item.reps_max : ''), 'muted'));
  }
  const jump = state.jumps[0];
  const jumpCard = overviewCard(t('latestJump'), jump ? t(jump.test) : null, jump ? dateTime(jump.created_at) : t('noData'), '↗', 'card-subject');
  if (jump && (!jump.flight_time_ms || jump.warnings.includes('no_flight_detected'))) {
    jumpCard.append(node('p', t('no_flight_detected'), 'muted'));
  } else if (jump) for (const [field, label] of [['jump_height_cm','height'], ['flight_time_ms','flight'],
    ['flexion_asymmetry_pct','asymmetry']]) {
    const metric = state.summary.metrics[field];
    const row = node('div', null, 'stat-line');
    row.append(node('span', t(label)), node('strong', format(jump[field], field)));
    if (metric?.baseline != null) row.append(node('small', t('baselineValue') + ': ' + format(metric.baseline, field) +
      (jump[field] == null ? '' : ' · ' + change(jump[field] - metric.baseline, field))));
    jumpCard.append(row);
  }
  const food = nutritionSummaryView(state.nutSummary);
  grid.append(checkin, training, food, jumpCard);
  content.append(grid);
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
function change(value, key) {
  if (value == null) return '—';
  const magnitude = format(Math.abs(value), key);
  if (key === 'flexion_asymmetry_pct') return (magnitude === '0' ? '' : value > 0 ? '+' : '−') +
    magnitude + '\u00a0' + t('percentagePoints').replace(' ', '\u00a0');
  return (magnitude === '0' ? '' : value > 0 ? '↑ ' : '↓ ') + magnitude;
}
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
  content.append(overviewHero(t(state.user.role === 'admin' ? 'adminDesk' : 'coachDesk'), t('team'), date(apiDay())));
  teamOverview(content);
  content.append(node('h2', t('roster')), node('p', t('changeNote'), 'muted'));
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
  grid.wrap.classList.add('club-table', 'roster-table');
  [...grid.head.children].forEach((cell,index)=>{
    const key=keys[index];const control=button(columns[index],()=>{state.ascending=state.sort===key?!state.ascending:true;state.sort=key;render('#sort-'+key);});
    control.id='sort-'+key;
    if(state.sort===key)control.append(document.createTextNode(state.ascending?' ↑':' ↓'));
    if (index === 1 || index >= 3 && index <= 5) cell.className = 'num';
    cell.replaceChildren(control);cell.setAttribute('aria-sort',state.sort===key?(state.ascending?'ascending':'descending'):'none');
  });
  for(const athlete of rows){
    const row=node('tr');const name=node('td');name.append(athleteLink(athlete));
    if(!athlete.active)name.append(node('small',t('inactive')));row.append(name,node('td',format(athlete.jump_count),'num'),node('td',date(athlete.last_jump_at)));
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
function chartScale(values, padding, bottom, height) {
  const min = Math.min(...values), max = Math.max(...values);
  const spread = (max - min) || Math.max(1, Math.abs(max) * .02);
  const low = min - spread * padding, high = max + spread * padding;
  const rawStep = (high - low) / 3;
  const power = 10 ** Math.floor(Math.log10(rawStep));
  const step = power * [1, 2, 2.5, 5, 10].find(size => size * power >= rawStep);
  const first = Math.floor(low / step), last = Math.ceil(high / step);
  const ticks = Array.from({length:last - first + 1}, (_, index) => (first + index) * step);
  const decimal = Math.max(0, -Math.floor(Math.log10(step)) + 1);
  const numberFormat = new Intl.NumberFormat(locale(), {maximumFractionDigits:Math.min(decimal, 12)});
  const y = value => bottom - (value / step - first) / (last - first) * height;
  return {ticks, y, label:value => numberFormat.format(value)};
}
function chartAxis(svg, scale) {
  for (const value of scale.ticks) svg.append(
    svgNode('line', {x1:54, x2:458, y1:scale.y(value), y2:scale.y(value), class:'chart-grid'}),
    svgNode('text', {x:46, y:scale.y(value)+4, 'text-anchor':'end', 'font-size':12, fill:'currentColor', class:'axis-tick'}, scale.label(value)));
}
function chart(title,fields) {
  const figure=node('figure');figure.append(node('figcaption',t(title)));
  const svg=svgNode('svg',{viewBox:'0 0 480 210',role:'img','aria-label':t(title)});const rows=[...state.jumps].reverse();
  const values=rows.flatMap(row=>fields.map(field=>row[field])).filter(value=>value!=null);
  for(const field of fields){const base=state.summary.metrics[field].baseline;if(base!=null)values.push(base);}
  if(!values.length){figure.append(node('p',t('noData')));return figure;}
  const scale = chartScale(values, .15, 166, 140);
  const y = scale.y;
  const first=Date.parse(rows[0].created_at),last=Date.parse(rows[rows.length-1].created_at);
  const x=row=>54+(last>first?(Date.parse(row.created_at)-first)/(last-first):.5)*404;
  chartAxis(svg, scale);
  fields.forEach((field,index)=>{
    const base=state.summary.metrics[field].baseline;
    if(base!=null)svg.append(svgNode('line',{x1:54,x2:458,y1:y(base),y2:y(base),class:'baseline-line'+(index?' series-right':'')}));
    // Split at missing values so a chart never invents a measured segment.
    let points=[];
    const draw=()=>{if(points.length)svg.append(svgNode('polyline',{points:points.join(' '),class:'series-line '+(index?'series-right':'series-left')}));points=[];};
    for(const row of rows){if(row[field]==null){draw();continue;}points.push(x(row)+','+y(row[field]));svg.append(index?svgNode('rect',{x:x(row)-3,y:y(row[field])-3,width:6,height:6,class:'right-dot'}):svgNode('circle',{cx:x(row),cy:y(row[field]),r:3,class:'chart-dot'}));}draw();
  });
  svg.append(svgNode('text',{x:first===last?256:54,y:198,'font-size':12,'text-anchor':first===last?'middle':'start',fill:'currentColor'},date(rows[0].created_at)));
  if (date(rows[0].created_at) !== date(rows[rows.length-1].created_at)) svg.append(svgNode('text',{x:458,y:198,'font-size':12,'text-anchor':'end',fill:'currentColor'},date(rows[rows.length-1].created_at)));
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
  head.append(actions);content.append(head);athleteSubnav(content,'history');content.append(node('p',t('chartNote'),'muted'));
  if(!state.summary.baseline_complete)content.append(node('p',t('provisional'),'warning'));
  if(state.jumps.length){
    const charts=node('div',null,'charts');charts.append(chart('ratio',['knee_ankle_ratio']),chart('flexion',['knee_flexion_peak_l','knee_flexion_peak_r']),chart('asymmetry',['flexion_asymmetry_pct']),chart('height',['jump_height_cm']));content.append(charts);
    const baselines=table(['metric','baselineValue','latest','recent','absolute','percent'],t('baseline'));
    for(const [key,label]of [['knee_ankle_ratio','ratio'],['knee_flexion_peak_mean','peakMean'],['flexion_asymmetry_pct','asymmetry']]){
      const metric=state.summary.metrics[key];const row=node('tr');row.append(node('td',t(label)));
    for(const field of ['baseline','latest','last_three_median','change_absolute','change_percent'])row.append(node('td',
      field==='change_absolute' ? change(metric[field],key) : format(metric[field],field==='change_percent'?null:key),'num'));baselines.body.append(row);
    }content.append(details('baseline',baselines.wrap));
    const grid=table(historyColumns.map(([,label])=>label),t('history'));
    [...grid.head.children].slice(2, -1).forEach(cell => { cell.className = 'num'; });
    for(const jump of state.jumps){const row=node('tr');for(const [key]of historyColumns){const value=jump[key];const cell=node('td',key==='created_at'?date(value):key==='test'?t(value):key==='warnings'?(value.map(t).join(', ')||t('none')):format(value,key));if(key==='warnings'&&value.length)cell.className='warning';else if(!['created_at','test','warnings'].includes(key))cell.className='num';row.append(cell);}grid.body.append(row);}content.append(grid.wrap);
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
    const title = state.sessions.find(session => session.id === report.session_id)?.title;
    const heading = node('h2', title ? t('report') + ': ' + title : t('report'));
    heading.id = 'session-report-title';
    heading.tabIndex = -1;
    head.append(heading, button('cancel', () => { state.sessionReport = null; render(); }));
    panel.append(head);
    panel.append(reportGrid(report));
    content.append(panel);
  }
}
function reportGrid(report) {
  const grid = table(['name','trialsDone','ratio','peakL','peakR','asymmetry','height'], t('report'));
  grid.wrap.classList.add('club-table');
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
  // Action column first so buttons stay visible without horizontal scrolling.
  const cells = row => {
    const tr = node('tr');
    if (staff) {
      const cell = node('td'); const actions = node('div', null, 'cell-actions');
      actions.append(button('editSession', () => { state.sessionEdit = row; state.sessionReport = null; render('#session-title'); }));
      actions.append(button('report', async () => { state.sessionReport = await api('/api/sessions/'+row.id+'/report'); render('#session-report-title'); }));
      actions.append(button(row.cancelled ? 'restoreSession' : 'cancelSession', async () => { await api('/api/sessions/'+row.id, {method:'PATCH', body:JSON.stringify(cancelFrom(row))}); await load(); message('saved'); }));
      cell.append(actions); tr.append(cell);
    }
    const titleTd = node('td', row.title);
    if (row.cancelled) titleTd.append(node('span', ' · ' + t('cancelledBadge'), 'badge pending'));
    tr.append(titleTd);
    tr.append(sessionWhen(row.starts_at));
    tr.append(node('td', groupNameOf(row)));
    tr.append(node('td', t(row.test)));
    tr.append(node('td', String(row.trials), 'num'));
    tr.append(node('td', String(row.jump_count || 0), 'num'));
    return tr;
  };
  const grid = table([...(staff ? ['actions'] : []), 'sessionTitle','date','group','test','trials','jumps'], t('sessions'));
  grid.wrap.classList.add('club-table');
  for (const session of rows) grid.body.append(cells(session));
  return grid.wrap;
}
function sessionsMine(content) {
  content.append(heading(t('mySessions')));
  if (!state.sessionsMine.length) { content.append(node('p', t('noSessions'), 'empty')); return; }
  const grid = table(['sessionTitle','date','place','test','trials','jumps'], t('mySessions'));
  grid.wrap.classList.add('club-table');
  for (const session of sortSessions(state.sessionsMine)) {
    const tr = node('tr');
    const title = node('td', session.title);
    if (session.cancelled) title.append(node('span', ' · ' + t('cancelledBadge'), 'badge pending'));
    tr.append(title);
    tr.append(sessionWhen(session.starts_at));
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
  grid.wrap.classList.add('club-table');
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
  // Two sibling charts so wellbeing (5–25) and load (arbitrary units) each get their own axis.
  const days = [...state.checkins].sort((a, b) => a.day < b.day ? -1 : a.day > b.day ? 1 : 0);
  const points = days.map(c => ({day: c.day, w: wellbeingSum(c), l: c.rpe != null && c.training_minutes != null ? c.rpe * c.training_minutes : null}));
  const wrap = node('div', null, 'charts');
  wrap.append(
    seriesChart(t('wellbeing'), points, 'w', {dots:true}),
    seriesChart(t('load'), points, 'l', {dots:false}));
  return wrap;
}
function seriesChart(title, points, field, {dots}) {
  const figure = node('figure');
  figure.append(node('figcaption', title));
  const values = points.map(p => p[field]).filter(v => v != null);
  if (!values.length) { figure.append(node('p', t('noCheckins'))); return figure; }
  const svg = svgNode('svg', {viewBox:'0 0 480 180', role:'img', 'aria-label':title});
  const scale = chartScale(values, .15, 138, 116);
  const y = scale.y;
  const x = i => points.length === 1 ? 54 + 202 : 54 + i / (points.length - 1) * 404;
  chartAxis(svg, scale);
  let path = [];
  points.forEach((p, i) => {
    if (p[field] == null) { if (path.length) { svg.append(svgNode('polyline', {points:path.join(' '), class:'series-line ' + (dots ? 'series-left' : 'series-right')})); path = []; } return; }
    path.push(x(i)+','+y(p[field]));
    if (dots) svg.append(svgNode('circle', {cx:x(i), cy:y(p[field]), r:3, class:'chart-dot'}));
    else svg.append(svgNode('rect', {x:x(i)-3, y:y(p[field])-3, width:6, height:6, class:'right-dot'}));
  });
  if (path.length > 1) svg.append(svgNode('polyline', {points:path.join(' '), class:'series-line ' + (dots ? 'series-left' : 'series-right')}));
  if (points.length > 1) svg.append(svgNode('text', {x:54, y:164, 'font-size':12, fill:'currentColor'}, date(points[0].day)), svgNode('text', {x:458, y:164, 'text-anchor':'end', 'font-size':12, fill:'currentColor'}, date(points[points.length-1].day)));
  figure.append(svg);
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
  grid.wrap.classList.add('club-table');
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
    meta.append(document.createTextNode(' · ' + dateTime(c.created_at)));
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
  for(const entry of state.audit){const row=node('tr');row.append(node('td',dateTime(entry.at)),node('td',entry.actor_email??(entry.actor_id==null?'—':String(entry.actor_id))),node('td',t(entry.action)),node('td',entry.target),node('td',Object.keys(entry.detail).length?JSON.stringify(entry.detail):'—','audit-detail'));grid.body.append(row);}content.append(state.audit.length?grid.wrap:node('p',t('noRows'),'empty'));
  const paging=node('div',null,'actions pagination');const previous=button('previous',()=>{state.offset=Math.max(0,state.offset-50);return load();});previous.disabled=!state.offset;const next=button('next',()=>{state.offset+=50;return load();});next.disabled=state.audit.length<50;paging.append(previous,next);content.append(paging);
}
// --- training, nutrition, form, assistant (SPEC section 6) ---
const MEAL_ORDER = ['breakfast', 'lunch', 'dinner', 'snack'];
function svgChart(title, labels, series) {
  const figure = node('figure'); figure.append(node('figcaption', title));
  const values = series.flatMap(s => s.data.map(d => d.v)).filter(v => v != null);
  if (!values.length) { figure.append(node('p', t('noData'))); return figure; }
  const svg = svgNode('svg', {viewBox:'0 0 480 180', role:'img', 'aria-label':title});
  const scale = chartScale(values, .15, 138, 116);
  const n = labels.length;
  const y = scale.y;
  const x = i => n <= 1 ? 256 : 54 + i / (n - 1) * 404;
  chartAxis(svg, scale);
  for (const s of series) {
    if (s.type === 'line') {
      let path = [];
      const draw = () => { if (path.length > 1) svg.append(svgNode('polyline', {points:path.join(' '), class:'series-line ' + s.cls})); path = []; };
      for (const d of s.data) { if (d.v == null) { draw(); continue; } path.push(x(d.i) + ',' + y(d.v)); }
      draw();
    } else {
      for (const d of s.data) {
        if (d.v == null) continue;
        svg.append(s.cls === 'right-dot'
          ? svgNode('rect', {x:x(d.i)-3, y:y(d.v)-3, width:6, height:6, class:'right-dot'})
          : svgNode('circle', {cx:x(d.i), cy:y(d.v), r:3, class:s.cls || 'chart-dot'}));
      }
    }
  }
  if (n > 1) svg.append(
    svgNode('text', {x:54, y:164, 'font-size':12, fill:'currentColor'}, labels[0]),
    svgNode('text', {x:458, y:164, 'text-anchor':'end', 'font-size':12, fill:'currentColor'}, labels[n-1]));
  figure.append(svg); return figure;
}
function athleteSubnav(content, current) {
  const own = state.user.role === 'athlete';
  const nav = node('nav', null, 'subnav'); nav.setAttribute('aria-label', t('sectionLinks'));
  for (const [v, key] of [...(own ? [['today','today']] : []), ['history', own ? 'ownHistory' : 'history'], ['training', 'training'],
                          ['nutrition', 'nutrition'], ['form', 'formView']]) {
    const b = button(key, () => load(v)); if (current === v) b.setAttribute('aria-current', 'page'); nav.append(b);
  }
  content.append(nav);
}
function beginAthleteView(content, current, subtitle) {
  const athlete = athleteTarget();
  if (!athlete) { content.append(node('p', t('selectAthleteFirst'), 'empty')); return null; }
  if (state.user.role !== 'athlete') content.append(button('back', () => load('team'), 'secondary back'));
  content.append(heading(athlete.name, subtitle));
  athleteSubnav(content, current);
  return athlete;
}
function exerciseName(id) { const e = state.exercises.find(x => x.id === id); return e ? e.name : '#' + id; }
function planCard(plan) {
  const wrap = node('details'); wrap.open = true;
  wrap.append(node('summary', plan.title + ' · ' + date(plan.starts_on) + ' · ' + plan.weeks + ' ' + t('weeksCol')));
  const grid = table(['weekday', 'exercise', 'setsCol', 'repsRange', 'loadKg', 'rpeTarget'], plan.title);
  grid.wrap.classList.add('club-table');
  for (const it of plan.items) {
    const tr = node('tr');
    tr.append(node('td', t('weekdays')[it.weekday] ?? String(it.weekday)));
    tr.append(node('td', it.exercise));
    tr.append(node('td', String(it.sets), 'num'));
    tr.append(node('td', it.reps_max && it.reps_max !== it.reps ? it.reps + '–' + it.reps_max : String(it.reps), 'num'));
    tr.append(node('td', it.load_kg == null ? '—' : format(it.load_kg, 'load_kg'), 'num'));
    tr.append(node('td', it.rpe_target == null ? '—' : format(it.rpe_target, 'rpe_target'), 'num'));
    grid.body.append(tr);
  }
  wrap.append(grid.wrap);
  if (plan.notes) wrap.append(node('p', plan.notes, 'muted'));
  return wrap;
}
function volumeCharts(weeks) {
  const labels = weeks.map(w => date(w.week_start));
  const vol = weeks.map((w, i) => ({i, v: w.volume_kg || null}));
  const load = weeks.map((w, i) => ({i, v: w.session_load || null}));
  const wrap = node('div', null, 'charts');
  wrap.append(
    svgChart(t('volumeKg'), labels, [{type:'line', cls:'series-left', data:vol}, {type:'dots', cls:'chart-dot', data:vol}]),
    svgChart(t('sessionLoadCol'), labels, [{type:'line', cls:'series-right', data:load}, {type:'dots', cls:'right-dot', data:load}]));
  return wrap;
}
function volumeTable(weeks) {
  const grid = table(['weekCol', 'setsCol', 'volumeKg', 'unloadedSets', 'sessionLoadCol'], t('weeklyVolume'));
  grid.wrap.classList.add('club-table');
  for (const w of weeks) {
    const tr = node('tr');
    tr.append(node('td', date(w.week_start)), node('td', String(w.sets), 'num'),
      node('td', formatNumber(w.volume_kg), 'num'), node('td', String(w.unloaded_sets), 'num'),
      node('td', formatNumber(w.session_load), 'num'));
    grid.body.append(tr);
  }
  return grid.wrap;
}
function progressionTable(rows) {
  const section = node('section'); section.append(node('h2', t('progressionTitle')));
  const grid = table(['exercise', 'planned', 'suggestion'], t('progressionTitle'));
  grid.wrap.classList.add('club-table');
  for (const r of rows) {
    const tr = node('tr');
    tr.append(node('td', r.exercise || exerciseName(r.exercise_id)));
    const p = r.planned;
    const reps = p.reps_max && p.reps_max !== p.reps ? p.reps + '–' + p.reps_max : String(p.reps);
    tr.append(node('td', p.sets + '×' + reps + (p.rpe_target != null ? ' · ' + t('effortShort') + ' ' + format(p.rpe_target, 'rpe_target') : '')));
    const cell = node('td');
    cell.append(node('span', t(r.action), 'badge' + (r.action === 'decrease' ? ' pending' : '')));
    cell.append(document.createTextNode(' · ' + t(r.reason)));
    if (r.suggested_load_kg != null) cell.append(node('div',
      t('fromLoad') + ' ' + format(r.from_load_kg, 'load_kg') + ' → ' + format(r.suggested_load_kg, 'load_kg'), 'muted'));
    tr.append(cell); grid.body.append(tr);
  }
  section.append(grid.wrap); return section;
}
function setLogTable(rows) {
  const section = node('section'); section.append(node('h2', t('setHistory')));
  const grid = table(['date', 'exercise', 'setNo', 'repsDone', 'loadKg', 'rpe'], t('setHistory'));
  grid.wrap.classList.add('club-table');
  for (const s of rows) {
    const tr = node('tr');
    tr.append(node('td', date(s.day)), node('td', exerciseName(s.exercise_id)), node('td', String(s.set_no), 'num'),
      node('td', String(s.reps), 'num'), node('td', s.load_kg == null ? '—' : format(s.load_kg, 'load_kg'), 'num'),
      node('td', s.rpe == null ? '—' : format(s.rpe, 'rpe'), 'num'));
    grid.body.append(tr);
  }
  section.append(grid.wrap); return section;
}
function training(content) {
  const athlete = beginAthleteView(content, 'training', t('training'));
  if (!athlete) return;
  content.append(node('p', t('trainingNote'), 'muted'));
  const own = state.user.role === 'athlete';
  const plans = own ? state.plansData : state.plansData.filter(p => p.athlete_id === athlete.id || (p.athlete_id == null));
  if (plans.length) for (const plan of plans) content.append(planCard(plan));
  else content.append(node('p', t('noPlan'), 'empty'));
  const tr = state.training;
  if (tr && tr.weekly_volume && tr.weekly_volume.some(w => w.sets)) {
    content.append(volumeCharts(tr.weekly_volume), volumeTable(tr.weekly_volume));
  }
  if (tr && tr.progression && tr.progression.length) content.append(progressionTable(tr.progression));
  if (state.setLogs.length) content.append(setLogTable(state.setLogs));
  else if (!plans.length) content.append(node('p', t('noTraining'), 'empty'));
}
function planAssignLabel(plan) {
  if (plan.athlete_id != null) { const a = state.athletes.find(x => x.id === plan.athlete_id); return a ? a.name : '#' + plan.athlete_id; }
  if (plan.group_id != null) { const g = state.groups.find(x => x.id === plan.group_id); return g ? g.name : '#' + plan.group_id; }
  return t('wholeClub');
}
function miniNum(name, aria, value, min, max, step) {
  const i = node('input', null, 'mini'); i.type = 'number'; i.name = name; i.value = value === '' || value == null ? '' : String(value);
  i.min = min; i.max = max; if (step) i.step = step; i.required = false; i.setAttribute('aria-label', aria); return i;
}
function miniSelect(name, aria, choices, value) {
  const s = node('select', null, 'mini'); s.name = name; s.setAttribute('aria-label', aria);
  for (const [v, label] of choices) { const o = node('option', label); o.value = String(v); s.append(o); }
  if (value != null) s.value = String(value); return s;
}
function planItemRow(it) {
  const row = node('div', null, 'plan-item');
  row.append(miniSelect('weekday', t('weekday'), t('weekdays').map((l, i) => [i, l]), it ? it.weekday : 0));
  row.append(miniSelect('exercise_id', t('exercise'), state.exercises.map(e => [e.id, e.name]),
    it ? it.exercise_id : (state.exercises[0] && state.exercises[0].id)));
  row.append(miniNum('sets', t('setsCol'), it ? it.sets : 3, 1, 20));
  row.append(miniNum('reps', t('repsRange'), it ? it.reps : 5, 1, 100));
  row.append(miniNum('reps_max', 'max ' + t('repsRange'), it && it.reps_max != null ? it.reps_max : '', 1, 100));
  row.append(miniNum('load_kg', t('loadKg'), it && it.load_kg != null ? it.load_kg : '', 0, 1000, '0.5'));
  row.append(miniNum('rpe_target', t('rpeTarget'), it && it.rpe_target != null ? it.rpe_target : '', 0, 10, '0.5'));
  const rm = button('removeItem', () => row.remove(), 'secondary'); rm.classList.add('mini'); row.append(rm);
  return row;
}
function planItemValues(row) {
  const get = n => row.querySelector('[name=' + n + ']');
  const exId = Number(get('exercise_id').value);
  if (!exId) return null;
  const item = {weekday: Number(get('weekday').value), exercise_id: exId,
    sets: Number(get('sets').value), reps: Number(get('reps').value)};
  const rm = get('reps_max').value; if (rm !== '') item.reps_max = Number(rm);
  const ld = get('load_kg').value; if (ld !== '') item.load_kg = Number(ld);
  const rp = get('rpe_target').value; if (rp !== '') item.rpe_target = Number(rp);
  return item;
}
function planEditor(existing) {
  const edit = existing.id ? existing : null;
  const panel = node('section', null, 'inline-panel');
  panel.append(node('h2', edit ? t('editPlan') : t('addPlan')));
  const form = node('form');
  const title = inputField(form, 'sessionTitle', 'title', 'text', edit ? edit.title : '', {maxLength:100}); title.id = 'plan-title';
  const assign = selectField(form, 'assignTo', 'assign',
    [['club', t('wholeClub')], ...state.groups.map(g => ['g:' + g.id, g.name]), ...state.athletes.map(a => ['a:' + a.id, a.name])]);
  assign.value = edit ? (edit.athlete_id != null ? 'a:' + edit.athlete_id : edit.group_id != null ? 'g:' + edit.group_id : 'club') : 'club';
  inputField(form, 'startsOn', 'starts_on', 'date', edit ? edit.starts_on : apiDay(), {});
  inputField(form, 'weeksCol', 'weeks', 'number', String(edit ? edit.weeks : 4), {min:1, max:52, step:'1'});
  const notes = node('textarea'); notes.name = 'notes'; notes.maxLength = 1000; notes.rows = 2; if (edit) notes.value = edit.notes || '';
  const nl = node('label', t('notes')); nl.append(notes); form.append(nl);
  const itemsWrap = node('div', null, 'plan-items');
  form.append(itemsWrap);
  const addRow = it => itemsWrap.append(planItemRow(it));
  if (edit && edit.items.length) edit.items.forEach(addRow); else addRow(null);
  form.append(button('addItem', () => addRow(null)));
  submit(form, edit ? 'savePlan' : 'createPlan', async data => {
    const items = [...itemsWrap.querySelectorAll('.plan-item')].map(planItemValues).filter(Boolean);
    const assignVal = String(data.get('assign'));
    const body = {title: String(data.get('title') || '').trim(), starts_on: String(data.get('starts_on') || ''),
      weeks: Number(data.get('weeks')), notes: String(data.get('notes') || ''), items,
      group_id: assignVal.startsWith('g:') ? Number(assignVal.slice(2)) : null,
      athlete_id: assignVal.startsWith('a:') ? Number(assignVal.slice(2)) : null};
    if (edit) await api('/api/plans/' + edit.id, {method:'PUT', body:JSON.stringify(body)});
    else await api('/api/plans', {method:'POST', body:JSON.stringify(body)});
    state.planEdit = null; await load(); message('saved');
  });
  form.append(button('cancel', () => { state.planEdit = null; render(); }));
  panel.append(form); return panel;
}
function exerciseManager() {
  const panel = node('section', null, 'inline-panel');
  panel.append(node('h2', t('exercisesTitle')));
  if (state.exercises.length) {
    const grid = table(['exercise', 'kind', 'lowerBody', 'actions'], t('exercisesTitle'));
    grid.wrap.classList.add('club-table');
    for (const e of state.exercises) {
      const tr = node('tr');
      tr.append(node('td', e.name), node('td', t(e.kind)), node('td', e.lower_body ? '✓' : '—', 'num'));
      const cell = node('td'); const act = node('div', null, 'cell-actions');
      act.append(button('deletePlan', async () => { await api('/api/exercises/' + e.id, {method:'DELETE'}); await load(); message('saved'); }, 'danger'));
      cell.append(act); tr.append(cell); grid.body.append(tr);
    }
    panel.append(grid.wrap);
  } else panel.append(node('p', t('noExercises'), 'empty'));
  const form = node('form');
  field(form, 'name', 'name').id = 'exercise-name';
  selectField(form, 'kind', 'kind', [['strength', t('strength')], ['plyometric', t('plyometric')],
    ['mobility', t('mobility')], ['conditioning', t('conditioning')]]);
  const lb = node('label', null, 'checkbox'); const cb = node('input'); cb.type = 'checkbox'; cb.name = 'lower_body';
  lb.append(cb, document.createTextNode(t('lowerBody'))); form.append(lb);
  submit(form, 'addExercise', async data => {
    await api('/api/exercises', {method:'POST', body:JSON.stringify(
      {name: String(data.get('name') || '').trim(), kind: data.get('kind'), lower_body: data.get('lower_body') === 'on'})});
    await load(); message('saved');
  });
  panel.append(details('addExercise', form));
  return panel;
}
function plans(content) {
  content.append(heading(t('plans')));
  if (state.planEdit) { content.append(planEditor(state.planEdit)); return; }
  content.append(button('addPlan', () => { state.planEdit = {id:null}; render('#plan-title'); }));
  if (state.plansData.length) {
    const grid = table(['sessionTitle', 'assignTo', 'startsOn', 'weeksCol', 'actions'], t('plans'));
    grid.wrap.classList.add('club-table');
    for (const plan of state.plansData) {
      const tr = node('tr');
      tr.append(node('td', plan.title), node('td', planAssignLabel(plan)), node('td', date(plan.starts_on)), node('td', String(plan.weeks), 'num'));
      const cell = node('td'); const act = node('div', null, 'cell-actions');
      act.append(button('editPlan', () => { state.planEdit = plan; render('#plan-title'); }));
      act.append(button('deletePlan', () => { if (window.confirm(t('confirmDeletePlan'))) run(async () => { await api('/api/plans/' + plan.id, {method:'DELETE'}); await load(); message('saved'); }); }, 'danger'));
      cell.append(act); tr.append(cell); grid.body.append(tr);
    }
    content.append(grid.wrap);
  } else content.append(node('p', t('noPlans'), 'empty'));
  content.append(exerciseManager());
}
function dayPickerInto(content, day, onPick, id) {
  const picker = node('div', null, 'daypicker');
  const input = node('input'); input.type = 'date'; input.value = day; input.id = id;
  const label = node('label', t('day')); label.htmlFor = id;
  picker.append(label, input); content.append(picker);
  const week = node('div', null, 'week');
  for (let i = 0; i < 7; i++) {
    const d = new Date(); d.setUTCDate(d.getUTCDate() - i); const ds = apiDayOf(d);
    const b = button(null, () => onPick(ds));
    b.textContent = new Intl.DateTimeFormat(locale(), {weekday:'short', day:'numeric', month:'numeric'}).format(d);
    b.setAttribute('aria-pressed', String(ds === day)); b.classList.add('secondary'); week.append(b);
  }
  content.append(week);
  input.addEventListener('change', () => { if (input.value) run(() => onPick(input.value)); });
}
function weightChart(data) {
  const base = (data.trend.length ? data.trend.map(x => x.day) : data.weights.map(x => x.day));
  const labels = base.map(date);
  const idx = {}; base.forEach((d, i) => idx[d] = i);
  const trendSeries = base.map((d, i) => ({i, v: null}));
  data.trend.forEach(tt => { if (tt.day in idx) trendSeries[idx[tt.day]] = {i: idx[tt.day], v: tt.trend_kg}; });
  const kgSeries = base.map((d, i) => ({i, v: null}));
  data.weights.forEach(w => { if (w.day in idx) kgSeries[idx[w.day]] = {i: idx[w.day], v: w.kg}; });
  const fig = svgChart(t('bodyweight'), labels,
    [{type:'dots', cls:'chart-dot', data:kgSeries}, {type:'line', cls:'series-right', data:trendSeries}]);
  fig.append(node('p', t('weightKg') + ' · ' + t('trendLine'), 'legend'));
  return fig;
}
function expenditureChart(exp) {
  const labels = exp.history.map(h => date(h.date));
  const data = exp.history.map((h, i) => ({i, v: h.value_kcal}));
  const fig = svgChart(t('expenditure'), labels, [{type:'line', cls:'series-left', data}, {type:'dots', cls:'chart-dot', data}]);
  fig.append(node('p', t('expenditureNote'), 'legend'));
  return fig;
}
function nutritionSummaryView(s) {
  const section = node('section', null, 'overview-card nutrition-totals');
  section.append(node('h2', t('dailyTotals') + ' · ' + date(s.day)));
  const keys = [['kcal', 'kcal'], ['protein_g', 'protein'], ['carbs_g', 'carbs'], ['fat_g', 'fat'], ['fibre_g', 'fibre']];
  for (const [k, label] of keys) {
    const row = node('div', null, 'target-row');
    const target = s.target?.[k];
    const unit = t(k === 'kcal' ? 'kcalUnit' : 'gramUnit');
    row.append(node('span', t(label).replace(/ \([^)]+\)$/, '')), node('strong', formatNumber(s.totals[k]) +
      (target == null ? '' : ' / ' + formatNumber(target)) + ' ' + unit));
    if (target > 0) {
      const progress = node('progress'); progress.max = target; progress.value = Math.min(target, s.totals[k]);
      progress.setAttribute('aria-label', t(label) + ' · ' + t('nutritionTarget'));
      progress.setAttribute('aria-valuetext', formatNumber(s.totals[k]) + ' / ' + formatNumber(target) + ' ' + unit);
      row.append(progress);
    } else if (k === 'kcal') row.append(node('small', t('noTargetShort')));
    section.append(row);
  }
  if (s.weekly_average && s.weekly_average.days_logged) section.append(node('p',
    t('weeklyAverage') + ' (' + s.weekly_average.days_logged + '): ' +
    keys.map(([k, label]) => t(label) + ' ' + (s.weekly_average[k] == null ? '—' : formatNumber(s.weekly_average[k]))).join(' · '), 'muted'));
  return section;
}
function foodLogView(logs, day) {
  const section = node('section'); section.append(node('h2', t('foodLog') + ' · ' + date(day)));
  const dayLogs = logs.filter(l => l.day === day);
  if (!dayLogs.length) { section.append(node('p', t('noNutrition'), 'empty')); return section; }
  const grid = table(['meal', 'food', 'grams', 'kcal', 'protein', 'carbs', 'fat'], t('foodLog'));
  grid.wrap.classList.add('club-table');
  for (const m of MEAL_ORDER) for (const l of dayLogs.filter(x => x.meal === m)) {
    const tr = node('tr');
    tr.append(node('td', t(l.meal)), node('td', state.language === 'bg' ? l.name_bg : (l.name_en || l.name_bg)),
      node('td', formatNumber(l.grams), 'num'), node('td', formatNumber(l.kcal), 'num'),
      node('td', formatNumber(l.protein_g), 'num'), node('td', formatNumber(l.carbs_g), 'num'), node('td', formatNumber(l.fat_g), 'num'));
    grid.body.append(tr);
  }
  section.append(grid.wrap); return section;
}
function programForm(athlete, prog) {
  const form = node('form');
  const cur = prog && prog.program ? prog.program : null;
  const rec = cur && cur.recommended_target ? cur.recommended_target : null;
  const goal = selectField(form, 'goal', 'goal', [['maintain', t('maintain')], ['lose', t('lose')], ['gain', t('gain')]]);
  if (cur) goal.value = cur.goal;
  inputField(form, 'rate', 'rate_kg_week', 'number', cur ? String(cur.rate_kg_week) : '0', {min:0, max:2, step:'0.05'}).id = 'program-rate';
  inputField(form, 'protein', 'protein_g', 'number', rec ? String(rec.protein_g) : '', {min:0, max:500, step:'1'});
  inputField(form, 'fat', 'fat_g', 'number', rec ? String(rec.fat_g) : '', {min:0, max:500, step:'1'});
  submit(form, 'setProgram', async data => {
    await api('/api/athletes/' + athlete.id + '/nutrition/program', {method:'PUT', body:JSON.stringify(
      {goal: data.get('goal'), rate_kg_week: Number(data.get('rate_kg_week')),
       protein_g: Number(data.get('protein_g')), fat_g: Number(data.get('fat_g'))})});
    await load(); message('saved');
  });
  return form;
}
function programView(prog, athlete) {
  const section = node('section', null, 'inline-panel'); section.append(node('h2', t('programTitle')));
  if (!prog || !prog.program) {
    section.append(node('p', t('noProgram'), 'muted'));
  } else {
    const p = prog.program;
    const meta = node('p');
    meta.append(node('span', t(p.goal), 'badge'));
    meta.append(document.createTextNode(' · ' + t('rate') + ': ' + format(p.rate_kg_week, 'rate') +
      ' · ' + t('estimateKcal') + ': ' + formatNumber(p.expenditure_kcal) + ' ' + t('kcal')));
    section.append(meta);
    const rec = p.recommended_target;
    section.append(node('p', t('recommended') + ': ' + formatNumber(rec.calories) + ' ' + t('kcal') + ' · ' +
      t('protein') + ' ' + formatNumber(rec.protein_g) + ' · ' + t('carbs') + ' ' + formatNumber(rec.carbs_g) +
      ' · ' + t('fat') + ' ' + formatNumber(rec.fat_g)));
    const line = node('p');
    line.append(node('span', t(prog.applied ? 'appliedBadge' : 'notAppliedBadge'), 'badge' + (prog.applied ? '' : ' pending')));
    section.append(line);
    if (!prog.applied && prog.guardrail) section.append(node('p', t('guardrailNote'), 'warning'));
  }
  section.append(details('setProgram', programForm(athlete, prog)));
  return section;
}
function targetView(tgt, athlete) {
  const section = node('section', null, 'inline-panel'); section.append(node('h2', t('nutritionTarget')));
  if (tgt) {
    if (tgt.under_18) section.append(node('span', t('under18'), 'badge pending'));
    if (tgt.estimate_kcal != null) section.append(node('p', t('estimateKcal') + ': ' + formatNumber(tgt.estimate_kcal) + ' ' + t('kcal'), 'muted'));
    if (tgt.protein_range_g) section.append(node('p', t('proteinRange') + ': ' +
      formatNumber(tgt.protein_range_g.min) + '–' + formatNumber(tgt.protein_range_g.max) + ' ' + t('grams'), 'muted'));
  }
  const form = node('form');
  const cur = tgt && tgt.target ? tgt.target : null;
  inputField(form, 'kcal', 'kcal', 'number', cur ? String(cur.kcal) : '', {min:1, max:10000, step:'1'}).id = 'target-kcal';
  inputField(form, 'protein', 'protein_g', 'number', cur && cur.protein_g != null ? String(cur.protein_g) : '', {min:0, max:1000, step:'1', required:false});
  inputField(form, 'carbs', 'carbs_g', 'number', cur && cur.carbs_g != null ? String(cur.carbs_g) : '', {min:0, max:2000, step:'1', required:false});
  inputField(form, 'fat', 'fat_g', 'number', cur && cur.fat_g != null ? String(cur.fat_g) : '', {min:0, max:1000, step:'1', required:false});
  submit(form, 'setTarget', async data => {
    const body = {kcal: Number(data.get('kcal'))};
    for (const k of ['protein_g', 'carbs_g', 'fat_g']) { const v = data.get(k); if (v !== '') body[k] = Number(v); }
    await api('/api/athletes/' + athlete.id + '/nutrition/target', {method:'PUT', body:JSON.stringify(body)});
    await load(); message('saved');
  });
  section.append(details('setTarget', form));
  return section;
}
function nutrition(content) {
  const athlete = beginAthleteView(content, 'nutrition', t('nutrition'));
  if (!athlete) return;
  content.append(node('p', t('nutritionNote'), 'muted'));
  const own = state.user.role === 'athlete';
  const day = state.nutritionDay || apiDay();
  dayPickerInto(content, day, d => { state.nutritionDay = d; return load('nutrition'); }, 'nutrition-day');
  const dayGrid = node('div', null, 'nutrition-grid');
  if (state.nutSummary) dayGrid.append(nutritionSummaryView(state.nutSummary));
  const daily = node('section', null, 'overview-card daily-food');
  daily.append(node('h2', t('foodLog')));
  if (state.nutSummary) for (const meal of MEAL_ORDER) {
    const row = node('div', null, 'stat-line');
    row.append(node('span', t(meal)), node('strong', formatNumber(state.nutSummary.by_meal_kcal[meal]) + ' ' + t('kcalUnit')));
    daily.append(row);
  }
  daily.append(details('mealBreakdown', foodLogView(state.foodLogs, day)));
  if (own) daily.append(button('dayComplete', async () => { await api('/api/me/nutrition/days/' + day + '/complete', {method:'PUT'}); await load(); message('saved'); }, 'primary'));
  dayGrid.append(daily); content.append(dayGrid);
  const charts = node('div', null, 'charts');
  if (state.weightsData && state.weightsData.weights.length) charts.append(weightChart(state.weightsData));
  if (state.expenditure && state.expenditure.history && state.expenditure.history.length) charts.append(expenditureChart(state.expenditure));
  if (charts.children.length) content.append(charts);
  const settings = node('div', null, 'overview-grid');
  settings.append(programView(state.program, athlete), targetView(state.nutTarget, athlete));
  content.append(settings);
}
function formSetCard(set) {
  const wrap = node('section', null, 'inline-panel');
  const exercise = state.formCatalogue.find(entry => entry.id === set.exercise);
  const mode = set.mode || exercise?.mode || 'reps';
  const side = ['L','left'].includes(set.side) ? 'sideLeft' : ['R','right'].includes(set.side) ? 'sideRight' : null;
  wrap.append(node('h2', exercise?.['name_' + state.language] || t(set.exercise)),
    node('p', [side && t(side), t((set.view || exercise?.view) === 'front' ? 'front' : 'sideView'),
      t(set.source === 'live' ? 'sourceLive' : 'sourceVideo'), dateTime(set.created_at)].filter(Boolean).join(' · '), 'muted'));
  const s = set.summary || {};
  if (mode === 'hold') {
    const metrics = node('div', null, 'overview-grid');
    for (const [label, value] of [['holdTime', set.hold_ms ?? s.hold_ms], ['inBand', set.in_band_ms ?? s.in_band_ms]]) {
      metrics.append(overviewCard(t(label), value == null ? '—' : format(value / 1000), '', '◷'));
    }
    wrap.append(metrics);
  } else wrap.append(node('p', t('repsCount') + ': ' + (s.repetition_count ?? '—') + ' · ' + t('partialCount') + ': ' +
    (s.partial_count ?? '—') + ' · ' + t('unclearCount') + ': ' + (s.unclear_count ?? '—'), 'muted'));
  const reps = set.repetitions || [];
  const columns = exercise?.checks?.map(check => [check.key,
    check['label_' + state.language] + (check.unit ? ' (' + check.unit + ')' : '')]) || [
    ['depth',t('depth')], ['fppa_l',t('fppaL')], ['fppa_r',t('fppaR')], ['descent_time_ms',t('descentMs')], ['ascent_time_ms',t('ascentMs')]];
  const measurement = (rep, key) => rep.checks ? rep.checks[key] : rep[key];
  const labels = reps.map(r => '#' + r.index);
  const charts = node('div', null, 'charts');
  for (const [key, label] of columns) {
    const values = reps.map((rep, i) => ({i, v:measurement(rep, key)}));
    if (values.some(entry => entry.v != null)) charts.append(svgChart(label, labels,
      [{type:'line', cls:'series-left', data:values}, {type:'dots', cls:'chart-dot', data:values}]));
  }
  if (charts.children.length) wrap.append(charts);
  const grid = table(['repetition', 'repStatus', ...columns.map(([, label]) => label)], t('formHistoryTitle'));
  grid.wrap.classList.add('club-table');
  [...grid.head.children].slice(2).forEach(cell => { cell.className = 'num'; });
  for (const r of reps) {
    const tr = node('tr'); tr.append(node('td', '#' + r.index));
    const st = node('td'); st.append(node('span', t(r.status), 'badge' + (r.status === 'complete' ? '' : ' pending'))); tr.append(st);
    for (const [key] of columns) tr.append(node('td', format(measurement(r, key), key), 'num'));
    grid.body.append(tr);
  }
  if (reps.length) wrap.append(grid.wrap);
  if (set.warnings && set.warnings.length) wrap.append(node('p', t('warnings') + ': ' + set.warnings.map(w => t(w)).join(', '), 'warning'));
  return wrap;
}
function form(content) {
  const athlete = beginAthleteView(content, 'form', t('formView'));
  if (!athlete) return;
  content.append(node('p', t('formWarnNote'), 'muted'));
  if (!state.formSets.length) { content.append(node('p', t('noForm'), 'empty')); return; }
  for (const set of state.formSets) content.append(formSetCard(set));
}
function proposalView(p) {
  const box = node('div', null, 'proposal');
  box.append(node('h3', t('proposalTitle')));
  const list = node('ul');
  for (const act of p.actions) { const li = node('li'); li.textContent = act.human_summary; list.append(li); }
  box.append(list, node('p', t('proposalNote'), 'muted'));
  const btn = node('button', t('confirm')); btn.type = 'button'; btn.dataset.action = 'confirm';
  btn.addEventListener('click', () => confirmProposal(p.proposal_id, btn));
  box.append(btn);
  return box;
}
async function askAssistant(msg, control) {
  const a = state.assistant; a.error = null; a.note = null; control.disabled = true;
  try {
    const body = {message: msg}; if (a.conversationId) body.conversation_id = a.conversationId;
    const res = await api('/api/assistant', {method:'POST', body:JSON.stringify(body)});
    a.conversationId = res.conversation_id; a.reply = res.reply; a.proposal = res.proposal || null;
  } catch (err) {
    a.reply = null; a.proposal = null;
    a.error = err.message === 'assistantUnavailable' ? 'assistantUnavailable' : 'assistantError';
  } finally { control.disabled = false; a.open = true; renderAssistant(); }
}
async function confirmProposal(id, control) {
  const a = state.assistant; control.disabled = true; a.error = null;
  try {
    await api('/api/assistant/confirm', {method:'POST', body:JSON.stringify({proposal_id: id})});
    a.proposal = null; a.note = 'assistantApplied';
    load().catch(() => {});
  } catch (err) {
    a.note = (err.message === 'conflict' || err.message === 'notFound') ? 'proposalGone' : 'assistantError';
    a.proposal = null;
  } finally { control.disabled = false; a.open = true; renderAssistant(); }
}
function renderAssistant() {
  const host = $('assistant'); if (!host) return;
  host.replaceChildren();
  if (!state.user) return;
  const a = state.assistant;
  const panel = node('details', null, 'assistant-panel'); panel.open = a.open;
  panel.addEventListener('toggle', () => { a.open = panel.open; });
  panel.append(node('summary', t('assistant')));
  const body = node('div', null, 'assistant-body');
  body.append(node('p', t('assistantIntro'), 'muted'));
  const form = node('form', null, 'assistant-form');
  const ta = node('textarea'); ta.id = 'assistant-input'; ta.name = 'assistant-message'; ta.maxLength = 4000;
  ta.required = true; ta.rows = 2; ta.placeholder = t('assistantPlaceholder');
  const label = node('label', t('messageLabel')); label.append(ta); form.append(label);
  const send = node('button', t('ask')); send.type = 'submit'; form.append(send);
  form.addEventListener('submit', e => { e.preventDefault(); const msg = ta.value.trim(); if (!msg) { ta.focus(); return; } askAssistant(msg, send); });
  body.append(form);
  if (a.error) body.append(node('p', t(a.error), 'warning'));
  if (a.note) body.append(node('p', t(a.note), 'muted'));
  if (a.reply) { const r = node('div', null, 'assistant-reply'); r.append(node('h3', t('assistantReply')), node('p', a.reply)); body.append(r); }
  if (a.proposal) body.append(proposalView(a.proposal));
  panel.append(body); host.append(panel);
}
function render(focusSelector=null) {
  document.documentElement.lang=state.language;document.title='JumpScan · '+t('title');$('language-label').textContent=t('language');$('language').value=state.language;
  updateThemeLabel();
  const content=$('content');content.replaceChildren();$('session').replaceChildren();$('guide').replaceChildren(node('h2',t('guideTitle')),node('p',t('guide')));
  $('workspace').hidden = !state.user;
  document.body.classList.toggle('authenticated', Boolean(state.user));
  $('assistant')?.replaceChildren();
  if(!state.user){
    content.append(overviewHero('JumpScan',t('title'),t('dashboardIntro')));const panel=node('section',null,'login');panel.append(node('h2',t('signIn')));
    const form=node('form');form.id='login-form';const email=field(form,'email','email','email');email.id='email';email.autocomplete='username';const password=field(form,'password','password','password');password.id='password';password.minLength=1;password.autocomplete='current-password';
    submit(form,'signIn',async data=>{message('loading');const result=await api('/api/auth/login',{method:'POST',body:JSON.stringify(Object.fromEntries(data))});password.value='';state.token=result.token;state.user=result.user;state.offset=0;streamLive();try{await load(state.user.role==='athlete'?'today':'team');}catch(error){signedOut();throw error;}});panel.append(form);content.append(panel);return;
  }
  $('identity').textContent = state.user.name + ' · ' + t(state.user.role);
  $('session').append(button('signOut',async()=>{await api('/api/auth/logout',{method:'POST'});loadVersion++;signedOut();message('');}));
  const nav=node('nav', null, 'main-nav');nav.setAttribute('aria-label',t('overview'));
  const own = state.user.role === 'athlete';
  const views=own?[['today','overview','↗'],['sessionsMine','mySessions','◷'],['checkins','myCheckins','✓']]:[
    ['team','team','↗'],['plans','plans','≋'],['groups','groups','◉'],['sessions','sessions','◷'],['wellbeing','wellbeingView','✓'],
    ['users','users','◎'],...(state.user.role==='admin'?[['clubs','clubs','◇'],['audit','audit','≡']]:[])];
  const athleteTab = ['history','training','nutrition','form'].includes(state.view);
  for (const [view,key,icon] of views) {
    const control = button(key, () => load(view));
    const glyph = node('span', icon, 'nav-glyph'); glyph.setAttribute('aria-hidden', 'true'); control.prepend(glyph);
    if (state.view === view) control.setAttribute('aria-current', 'page');
    else if (athleteTab && view === (own ? 'today' : 'team')) control.setAttribute('aria-current', 'true');
    nav.append(control);
  }
  content.append(nav);
  const view = node('div', null, 'view');
  content.append(view);
  ({today,team,history,users,clubs,audit,groups,sessions,sessionsMine,wellbeing,checkins,training,nutrition,form,plans})[state.view](view);
  setLive(state.live);
  renderAssistant();
  if(focusSelector)content.querySelector(focusSelector)?.focus();
}
try {
  const saved = localStorage.getItem('jumpscan-appearance');
  document.documentElement.dataset.theme = saved === 'light' ? 'light' : 'dark';
} catch { document.documentElement.dataset.theme = 'dark'; }
const systemTheme = window.matchMedia('(prefers-color-scheme: dark)');
function updateThemeLabel() {
  const dark = document.documentElement.dataset.theme ? document.documentElement.dataset.theme === 'dark' : systemTheme.matches;
  $('theme').setAttribute('aria-label', t(dark ? 'themeLight' : 'themeDark'));
  $('theme').title = t(dark ? 'themeLight' : 'themeDark');
}
$('theme').addEventListener('click', () => {
  const root = document.documentElement;
  root.dataset.theme = (root.dataset.theme || (systemTheme.matches ? 'dark' : 'light')) === 'dark' ? 'light' : 'dark';
  try { localStorage.setItem('jumpscan-appearance', root.dataset.theme); } catch {}
  updateThemeLabel();
});
systemTheme.addEventListener('change', updateThemeLabel);
$('language').addEventListener('change',event=>{state.language=event.target.value;message('');render();});
render();

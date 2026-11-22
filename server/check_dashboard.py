"""Optional host-browser smoke check; needs Playwright and a local seeded server."""

import os
from pathlib import Path

from playwright.sync_api import expect, sync_playwright


def check():
    url = os.environ['JUMPSCAN_DASH_URL']
    output = Path(os.getenv('JUMPSCAN_DASH_SCREENSHOTS', 'logs/uiv2/dashboard'))
    output.mkdir(parents=True, exist_ok=True)
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(executable_path='/usr/bin/google-chrome', headless=True,
                                             args=['--no-sandbox', '--disable-dev-shm-usage'])
        page = browser.new_page(viewport={'width': 1440, 'height': 1000}, device_scale_factor=1)
        page.set_default_timeout(60000)
        errors, external = [], []
        page.on('pageerror', lambda error: errors.append(str(error)))
        page.on('request', lambda request: external.append(request.url) if not request.url.startswith(url) else None)

        def login(account):
            page.goto(url)
            page.locator('#email').fill(account + '@demo.test')
            page.locator('#password').fill('demo1234')
            page.locator('#login-form button').click()
            try:
                page.get_by_role('button', name='Изход', exact=True).wait_for()
            except Exception:
                page.screenshot(path=str(output / 'login-failure.png'), full_page=True)
                print('Login state:', page.locator('#message').inner_text(), 'page errors:', errors, flush=True)
                raise
            page.locator('main nav').first.wait_for()

        def screenshot(name, full_page=False):
            page.screenshot(path=str(output / f'{name}.png'), full_page=full_page)
            assert page.evaluate('document.documentElement.scrollWidth <= window.innerWidth'), name

        def unique_axes():
            for chart in page.locator('figure svg').all():
                labels = chart.locator('.axis-tick').all_text_contents()
                assert len(labels) == len(set(labels)), labels

        for role, account in [('coach', 'coach'), ('athlete', 'athlete1'), ('admin', 'admin')]:
            login(account)
            assert page.locator('.overview-card').count() == 4
            assert all(value.replace('\u00a0', '').replace('/', '').replace(' ', '').isdigit()
                       for value in page.locator('.overview-card .big-value').all_text_contents())
            if role == 'athlete':
                assert page.locator('progress').count() == 4
            for theme in ['light', 'dark']:
                page.evaluate('delete document.documentElement.dataset.theme')
                page.emulate_media(color_scheme=theme, reduced_motion='reduce')
                for language in ['bg', 'en']:
                    page.locator('#language').select_option(language)
                    for width in [1280, 390]:
                        page.set_viewport_size({'width': width, 'height': 844 if width == 390 else 960})
                        page.evaluate('window.scrollTo(0, 0)')
                        screenshot(f'{role}-{language}-{width}-{theme}')
                        assert page.locator('.change-unit').evaluate_all(
                            '(items) => items.every(item => item.scrollWidth <= item.clientWidth + 1)')
            page.locator('#language').select_option('bg')
            page.emulate_media(color_scheme='light')
            page.get_by_role('button', name='Тъмна тема', exact=True).click()
            assert page.locator('html').get_attribute('data-theme') == 'dark'
            page.get_by_role('button', name='Светла тема', exact=True).click()
            page.get_by_role('button', name='Изход', exact=True).click()
            page.locator('#login-form').wait_for()

        page.set_viewport_size({'width': 1280, 'height': 960})

        login('coach')
        assert page.locator('table tbody tr').count() == 6
        page.screenshot(path=str(output / 'team-desktop.png'), full_page=True)
        first_name = page.locator('.roster-link').first.inner_text()
        page.locator('thead button').first.click()
        assert page.evaluate('document.activeElement.id') == 'sort-name'
        assert page.locator('.roster-link').first.inner_text() != first_name
        page.locator('thead button').first.click()
        page.locator('.roster-link').first.click()
        page.locator('svg').nth(3).wait_for()
        assert page.evaluate('document.activeElement.tagName') == 'H1'
        assert page.locator('svg').count() == 6
        # 28 days of check-ins and 56 morning readiness jumps from the wave 2 seed (SPEC 9).
        assert page.locator('svg rect').count() == 27
        assert page.locator('table').last.locator('tbody tr').count() == 66
        page.screenshot(path=str(output / 'athlete-desktop.png'), full_page=True)
        page.locator('#language').select_option('en')
        page.get_by_role('button', name='Download CSV', exact=True).wait_for()
        with page.expect_download() as result:
            page.get_by_role('button', name='Download CSV', exact=True).click()
        assert result.value.suggested_filename.endswith('.csv')
        page.set_viewport_size({'width': 390, 'height': 844})
        page.screenshot(path=str(output / 'athlete-phone.png'), full_page=True)
        assert page.evaluate('document.documentElement.scrollWidth <= window.innerWidth')
        page.get_by_role('button', name='Back to team', exact=True).click()
        page.get_by_role('heading', name='Team', exact=True).wait_for()
        page.screenshot(path=str(output / 'team-phone.png'), full_page=True)
        assert page.evaluate('document.documentElement.scrollWidth <= window.innerWidth')
        page.set_viewport_size({'width': 1440, 'height': 1000})
        page.locator('#language').select_option('bg')
        page.locator('tbody').get_by_role('button', name='Съгласие', exact=True).first.click()
        assert page.evaluate('document.activeElement.id') == 'consent-by'
        page.get_by_role('button', name='Отказ', exact=True).click()
        assert page.evaluate('document.activeElement.id.startsWith("consent-")')
        page.locator('tbody').get_by_role('button', name='Съгласие', exact=True).first.click()
        page.get_by_label('Кой дава съгласие (например име на родител)').fill('Demo Parent')
        page.get_by_role('button', name='Запишете съгласие', exact=True).click()
        page.get_by_text('Промените са запазени.', exact=True).wait_for()
        page.get_by_role('button', name='Потребители', exact=True).click()
        page.get_by_role('heading', name='Потребители', exact=True).wait_for()
        assert page.get_by_role('button', name='Деактивирайте', exact=True).count() == 6
        page.get_by_role('button', name='Изход', exact=True).click()
        page.locator('#login-form').wait_for()

        # Club platform: groups, sessions, wellbeing, feedback (section 5.4).
        login('coach')
        page.get_by_role('button', name='Групи', exact=True).click()
        page.get_by_role('heading', name='Групи', exact=True).wait_for()
        page.locator('.group-row').first.wait_for()
        assert page.locator('.group-row').count() == 2
        page.locator('summary', has_text='Добавете група').click()
        page.locator('form input[name=name]').fill('Браузър група')
        page.get_by_role('button', name='Създайте група', exact=True).click()
        page.locator('.group-row', has_text='Браузър група').wait_for()
        page.locator('.group-row', has_text='Браузър група').get_by_role('button', name='Изберете членове', exact=True).click()
        assert page.locator('.member-panel input[type=checkbox]').count() == 6
        page.get_by_role('button', name='Запишете членове', exact=True).click()
        page.locator('.member-panel').wait_for(state='hidden')
        page.get_by_text('Промените са запазени.', exact=True).wait_for()
        page.get_by_role('button', name='Сесии', exact=True).click()
        page.get_by_role('heading', name='Сесии', exact=True).wait_for()
        page.get_by_role('button', name='Календар (.ics)', exact=True).wait_for()
        page.locator('tbody').get_by_role('button', name='Доклад', exact=True).first.click()
        page.locator('.report-panel table').wait_for()
        assert page.evaluate('document.activeElement.id') == 'session-report-title'
        page.set_viewport_size({'width': 390, 'height': 844})
        assert page.locator('.report-panel .table-scroll').evaluate('(table) => table.scrollWidth > table.clientWidth')
        assert page.locator('.report-panel th').first.bounding_box()['width'] >= 60
        assert page.evaluate('document.documentElement.scrollWidth <= window.innerWidth')
        page.set_viewport_size({'width': 1440, 'height': 1000})
        page.get_by_role('button', name='Самочувствие', exact=True).click()
        page.get_by_role('heading', name='Самочувствие', exact=True).wait_for()
        page.locator('table tbody tr').first.wait_for()
        assert page.locator('table tbody tr').count() == 6  # one row per club athlete
        assert page.get_by_text('под обичайното', exact=True).count() <= 1  # at most one; depends on the day's window
        # athlete page: feedback thread visible for staff
        page.get_by_role('button', name='Отбор', exact=True).click()
        page.locator('.roster-link').first.click()
        page.locator('.comment-box').wait_for()
        # Training, nutrition and form sections for the selected athlete (SPEC section 6).
        page.locator('.subnav').get_by_role('button', name='Тренировки', exact=True).click()
        page.get_by_role('button', name='Към отбора', exact=True).wait_for()
        page.get_by_role('heading', name='Дневник на сериите', exact=True).wait_for()
        # Workouts (SPEC 9): the seeded finished workout with its date, duration, sets and volume.
        workouts = page.locator('section', has=page.get_by_role('heading', name='Тренировки (сесии)', exact=True))
        assert workouts.locator('th').all_text_contents()[:5] == ['Дата', 'Тренировка', 'Време', 'Серии', 'Обем (кг)']
        cells = workouts.locator('tbody tr').first.locator('td').all_text_contents()
        assert workouts.locator('tbody tr').count() >= 1 and 'Демо тренировка' in cells and '64 мин' in cells, cells
        assert int(cells[3]) > 0 and int(cells[4].replace('\u00a0', '').replace(' ', '')) > 0, cells
        workouts.scroll_into_view_if_needed()
        screenshot('athlete-workouts-bg')
        page.locator('.subnav').get_by_role('button', name='Хранене', exact=True).click()
        page.locator('.daypicker').wait_for()
        page.locator('figure svg').first.wait_for()
        unique_axes()
        # SPEC 7.3: adherence, the 30-day analytics chart and goal progress in the coach view.
        page.get_by_role('heading', name='Придържане към целта', exact=True).wait_for()
        page.get_by_role('heading', name='Напредък към целта', exact=True).wait_for()
        assert page.locator('figcaption', has_text='Прием за 30 дни').count() == 1
        assert page.locator('.adherence p').first.inner_text().split(' / ')[0].isdigit()
        page.screenshot(path=str(output / 'coach-nutrition.png'), full_page=True)
        # SPEC 7.4: the coach sets the goal from the same view.
        panel = page.locator('.goal-progress')
        panel.locator('summary', has_text='Задайте цел за тегло').click()
        panel.locator('select[name=phase]').select_option('maintain')
        assert panel.locator('input[name=end_date]').is_disabled()
        panel.locator('input[name=protein_g]').fill('130')
        panel.locator('input[name=fat_g]').fill('60')
        panel.get_by_role('button', name='Задайте цел за тегло', exact=True).click()
        page.locator('.goal-progress .badge', has_text='Поддържане').wait_for()
        page.locator('.subnav').get_by_role('button', name='Техника', exact=True).click()
        page.get_by_role('button', name='Към отбора', exact=True).wait_for()
        # staff plan and exercise management
        page.get_by_role('button', name='Планове', exact=True).click()
        page.get_by_role('heading', name='Планове', exact=True).wait_for()
        page.get_by_role('heading', name='Упражнения', exact=True).wait_for()
        page.get_by_role('button', name='Добавете план', exact=True).click()
        page.locator('#plan-title').wait_for()
        assert page.evaluate('document.activeElement.id') == 'plan-title'
        page.locator('.plan-item').first.wait_for()
        page.get_by_role('button', name='Отказ', exact=True).click()
        # assistant panel present on every page
        assert page.locator('.assistant-panel').count() == 1
        page.get_by_role('button', name='Изход', exact=True).click()
        page.locator('#login-form').wait_for()

        login('athlete1')
        page.get_by_role('button', name='Моите сесии', exact=True).wait_for()
        page.get_by_role('button', name='Моите отбелязвания', exact=True).click()
        page.get_by_role('heading', name='Моите отбелязвания', exact=True).wait_for()
        page.locator('table').first.wait_for()
        page.locator('.main-nav').get_by_role('button', name='Преглед', exact=True).click()
        page.locator('.subnav').get_by_role('button', name='Моите скокове', exact=True).click()
        page.locator('.comment-box').wait_for()
        assert page.get_by_role('textbox', name='Коментар').count() == 1
        page.get_by_role('button', name='Изход', exact=True).click()
        page.locator('#login-form').wait_for()

        login('admin')
        page.get_by_role('button', name='Клубове', exact=True).click()
        page.get_by_role('heading', name='Клубове', exact=True).wait_for()
        page.get_by_text('Създайте клуб', exact=True).first.click()
        page.get_by_label('Име', exact=True).fill('Browser Demo Club')
        page.get_by_role('button', name='Създайте клуб', exact=True).click()
        page.get_by_role('cell', name='Browser Demo Club', exact=True).wait_for()
        page.get_by_role('button', name='Потребители', exact=True).click()
        page.get_by_role('heading', name='Потребители', exact=True).wait_for()
        assert page.locator('table tbody tr').count() == 10
        page.get_by_role('button', name='Дневник', exact=True).click()
        page.get_by_role('heading', name='Дневник', exact=True).wait_for()
        assert page.locator('table tbody tr').count() > 0
        page.screenshot(path=str(output / 'admin-audit.png'), full_page=True)
        page.get_by_role('button', name='Изход', exact=True).click()
        page.locator('#login-form').wait_for()

        # SPEC 8: club leaderboard and per-athlete records (the seed opts in athlete1 and athlete5).
        login('coach')
        page.get_by_role('button', name='Класация', exact=True).click()
        page.locator('.board-table').wait_for()
        assert sorted(page.locator('.board-table tbody td:nth-child(2)').all_text_contents()) == ['Александра И.', 'Даниел С.']
        assert page.locator('.board-hidden').inner_text().endswith(': 4')
        assert page.locator('.board-lanes .flight-lane').count() == 2
        page.locator('.board-controls').get_by_role('button', name='Време във въздуха', exact=True).click()
        page.locator('.board-controls button[aria-pressed=true]', has_text='Време във въздуха').wait_for()
        assert page.locator('.board-table tbody td.num').nth(1).inner_text().endswith('мс')
        page.locator('.board-controls').get_by_role('button', name='30 дни', exact=True).click()
        page.locator('.board-controls button[aria-pressed=true]', has_text='30 дни').wait_for()
        page.locator('#board-group').select_option(label='Състезатели')
        page.locator('.board-table tbody tr').first.wait_for()
        assert page.locator('.board-table tbody tr').count() == 1
        page.locator('#board-group').select_option(label='Целият клуб')
        page.locator('.board-controls').get_by_role('button', name='Височина на скока', exact=True).click()
        page.locator('.board-controls button[aria-pressed=true]', has_text='Височина на скока').wait_for()
        for theme in ['dark', 'light']:
            page.evaluate(f'document.documentElement.dataset.theme = "{theme}"')
            for language in ['bg', 'en']:
                page.locator('#language').select_option(language)
                for width in [1280, 390]:
                    page.set_viewport_size({'width': width, 'height': 960})
                    screenshot(f'coach-leaderboard-{language}-{width}-{theme}', full_page=True)
        page.locator('#language').select_option('bg')
        page.set_viewport_size({'width': 1280, 'height': 960})
        page.get_by_role('button', name='Отбор', exact=True).click()
        page.get_by_role('button', name='Мартин Колев', exact=True).first.click()
        page.locator('.subnav').get_by_role('button', name='Рекорди', exact=True).click()
        page.locator('.record-status').wait_for()
        page.get_by_role('button', name='Потвърдете участието (под 18 г.)', exact=True).click()
        page.get_by_role('button', name='Оттеглете потвърждението', exact=True).wait_for()
        assert page.locator('.record-hero .flight-lane').count() >= 2
        assert page.locator('.record-hero .flight-glyph').count() >= 2
        assert page.locator('.club-table tbody tr').count() >= 1
        assert page.get_by_text('Скокове с предупреждение, които не се броят: 1', exact=True).is_visible()
        for theme in ['dark', 'light']:
            page.evaluate(f'document.documentElement.dataset.theme = "{theme}"')
            for language in ['bg', 'en']:
                page.locator('#language').select_option(language)
                for width in [1280, 390]:
                    page.set_viewport_size({'width': width, 'height': 960})
                    screenshot(f'coach-records-{language}-{width}-{theme}', full_page=True)
        page.locator('#language').select_option('bg')
        page.set_viewport_size({'width': 1280, 'height': 960})
        page.get_by_role('button', name='Изход', exact=True).click()
        page.locator('#login-form').wait_for()

        # SPEC 9 wave 2: coach readiness board, morning digest and nudges (the seed has every state).
        login('coach')
        page.locator('.main-nav').get_by_role('button', name='Готовност', exact=True).click()
        page.locator('.rd-table').wait_for()
        assert page.locator('.rd-table tbody tr').count() == 6
        statuses = set(page.locator('.rd-table .rd-status').all_text_contents())
        assert {'Добре', 'Нормално', 'Внимание', 'Още се учи'} <= statuses, statuses
        assert page.locator('.rd-digest').get_by_text('Няма отчет: Мартин Колев', exact=True).is_visible()
        assert page.locator('.rd-digest').get_by_text('Под обичайното: Борис Петров', exact=True).is_visible()
        page.locator('#digest-phrase').click()
        page.locator('.rd-digest').get_by_text('Локалният модел не е наличен; показан е списъкът с факти.').wait_for()
        page.locator('#rd-group').select_option(label='Състезатели')
        expect(page.locator('.rd-table tbody tr')).to_have_count(3)
        page.locator('#rd-group').select_option(label='Целият клуб')
        expect(page.locator('.rd-table tbody tr')).to_have_count(6)
        page.locator('#rd-sort').select_option('name')
        assert page.evaluate('document.activeElement.id') == 'rd-sort'
        for theme in ['dark', 'light']:
            page.evaluate(f'document.documentElement.dataset.theme = "{theme}"')
            for language in ['bg', 'en']:
                page.locator('#language').select_option(language)
                for width in [1280, 390]:
                    page.set_viewport_size({'width': width, 'height': 960})
                    screenshot(f'coach-board-{language}-{width}-{theme}', full_page=True)
        page.locator('#language').select_option('bg')
        page.set_viewport_size({'width': 1280, 'height': 960})
        page.locator('#nudge-all').click()
        page.get_by_text('Напомнянето е изпратено.', exact=True).wait_for()
        assert page.locator('#nudge-all').is_disabled()
        assert page.locator('.rd-table').get_by_text('Напомнено днес', exact=True).count() == 1
        page.locator('#nudge-all').wait_for()
        screenshot('coach-board-nudged-bg-1280-light', full_page=True)
        page.get_by_role('button', name='Изход', exact=True).click()
        page.locator('#login-form').wait_for()

        login('athlete1')
        page.get_by_role('button', name='Класация', exact=True).click()
        page.locator('.board-table').wait_for()
        assert page.locator('.board-table tr.own-row').count() == 1
        assert page.locator('#leaderboard-opt-in').is_checked()
        assert page.locator('.board-hidden').count() == 0
        page.locator('#leaderboard-opt-in').uncheck()
        page.locator('.board-table tr.own-row').wait_for(state='detached')
        assert not page.locator('#leaderboard-opt-in').is_checked()
        page.locator('#leaderboard-opt-in').check()
        page.locator('.board-table tr.own-row').wait_for()
        page.evaluate('document.documentElement.dataset.theme = "dark"')
        screenshot('athlete-leaderboard-bg-1280-dark', full_page=True)
        page.locator('.main-nav').get_by_role('button', name='Преглед', exact=True).click()
        page.locator('.subnav').get_by_role('button', name='Рекорди', exact=True).click()
        page.locator('.record-hero .flight-lane').first.wait_for()
        assert page.locator('.record-status').count() == 0
        screenshot('athlete-records-bg-1280-dark', full_page=True)
        page.get_by_role('button', name='Изход', exact=True).click()
        page.locator('#login-form').wait_for()

        login('athlete1')
        page.locator('.overview-hero').wait_for()
        assert page.get_by_role('button', name='Отбор', exact=True).count() == 0
        # Athlete training, nutrition and form sections plus the assistant panel (SPEC section 6).
        page.locator('.subnav').get_by_role('button', name='Тренировки', exact=True).click()
        page.get_by_role('heading', name='Дневник на сериите', exact=True).wait_for()
        page.locator('.subnav').get_by_role('button', name='Хранене', exact=True).click()
        page.locator('.daypicker').wait_for()
        page.locator('figure svg').first.wait_for()
        unique_axes()
        assert page.evaluate('document.documentElement.scrollWidth <= window.innerWidth')
        page.locator('.subnav').get_by_role('button', name='Техника', exact=True).click()
        # On a freshly seeded database the technique ledger may be empty; then the empty note is a match,
        # while seeded form sets carry a "Клек" heading. Assert whichever the seed produced.
        try:
            page.get_by_text('Още няма записи на техника.', exact=True).wait_for(timeout=3000)
        except Exception:
            page.get_by_role('heading', name='Клек', exact=True).first.wait_for()
            page.get_by_role('cell', name='#1', exact=True).first.wait_for()
        page.route('**/api/form/exercises', lambda route: route.fulfill(json=[{
            'id': 'demo_hold', 'name_bg': 'Тестово задържане', 'name_en': 'Demo hold', 'mode': 'hold',
            'sided': True, 'view': 'side', 'checks': [{'key': 'angle', 'label_bg': 'Ъгъл', 'label_en': 'Angle', 'unit': '°'}]}]))
        page.route('**/api/form?*', lambda route: route.fulfill(json=[{
            'exercise': 'demo_hold', 'mode': 'hold', 'source': 'live', 'side': 'L', 'view': 'side',
            'created_at': '2026-11-19T10:00:00Z', 'hold_ms': 8400, 'in_band_ms': 6200, 'warnings': [],
            'repetitions': [{'index': 1, 'status': 'complete', 'checks': {'angle': 64.0}}]}]))
        page.locator('.subnav').get_by_role('button', name='Техника', exact=True).click()
        page.get_by_role('heading', name='Тестово задържане').wait_for()
        assert page.get_by_text('6,2', exact=True).is_visible()
        assert page.get_by_role('columnheader', name='Ъгъл (°)').is_visible()
        unique_axes()
        screenshot('athlete-form-catalogue-bg', full_page=True)
        page.unroute('**/api/form/exercises')
        page.unroute('**/api/form?*')
        assert page.locator('.assistant-panel').count() == 1
        page.locator('.assistant-panel > summary').click()
        page.locator('#assistant-input').wait_for()
        page.locator('.subnav').get_by_role('button', name='Моите скокове', exact=True).click()
        page.locator('svg').nth(3).wait_for()
        with page.expect_download() as result:
            page.get_by_role('button', name='Изтеглете данните си', exact=True).click()
        assert result.value.suggested_filename.endswith('.json')
        page.get_by_role('button', name='Изтрийте моите скокове', exact=True).click()
        assert page.evaluate('document.activeElement.dataset.action') == 'confirmDelete'
        page.get_by_text('Всички Ваши скокове ще бъдат изтрити окончателно. Профилът Ви остава.', exact=True).wait_for()
        page.get_by_role('button', name='Отказ', exact=True).click()
        assert page.evaluate('document.activeElement.dataset.action') == 'delete'
        assert page.locator('svg').count() == 6
        page.get_by_role('button', name='Изтрийте моите скокове', exact=True).click()
        page.get_by_role('button', name='Потвърдете изтриването', exact=True).click()
        page.get_by_text('Скоковете са изтрити.', exact=True).wait_for()
        assert page.locator('svg').count() == 0
        assert not errors, errors
        assert not external, external
        browser.close()
        print('Browser: coach, admin, athlete workflows; BG/EN; desktop/phone; zero page errors or external requests; screenshots saved.')


if __name__ == '__main__':
    check()

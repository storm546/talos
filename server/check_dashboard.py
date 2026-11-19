"""Optional host-browser smoke check; needs Playwright and a local seeded server."""

import os
from pathlib import Path

from playwright.sync_api import sync_playwright


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
        assert page.locator('svg rect').count() == 17
        assert page.locator('table').last.locator('tbody tr').count() == 10
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
        page.get_by_role('button', name='Запиши съгласие', exact=True).click()
        page.get_by_text('Промените са запазени.', exact=True).wait_for()
        page.get_by_role('button', name='Потребители', exact=True).click()
        page.get_by_role('heading', name='Потребители', exact=True).wait_for()
        assert page.get_by_role('button', name='Деактивирай', exact=True).count() == 6
        page.get_by_role('button', name='Изход', exact=True).click()
        page.locator('#login-form').wait_for()

        # Club platform: groups, sessions, wellbeing, feedback (section 5.4).
        login('coach')
        page.get_by_role('button', name='Групи', exact=True).click()
        page.get_by_role('heading', name='Групи', exact=True).wait_for()
        page.locator('.group-row').first.wait_for()
        assert page.locator('.group-row').count() == 2
        page.locator('summary', has_text='Добави група').click()
        page.locator('form input[name=name]').fill('Браузър група')
        page.get_by_role('button', name='Създай група', exact=True).click()
        page.locator('.group-row', has_text='Браузър група').wait_for()
        page.locator('.group-row', has_text='Браузър група').get_by_role('button', name='Избери членове', exact=True).click()
        assert page.locator('.member-panel input[type=checkbox]').count() == 6
        page.get_by_role('button', name='Запиши членове', exact=True).click()
        page.get_by_role('button', name='Сесии', exact=True).click()
        page.get_by_role('heading', name='Сесии', exact=True).wait_for()
        page.get_by_role('button', name='Календар (.ics)', exact=True).wait_for()
        page.locator('tbody').get_by_role('button', name='Доклад', exact=True).first.click()
        page.locator('.report-panel table').wait_for()
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
        page.locator('.subnav').get_by_role('button', name='Хранене', exact=True).click()
        page.locator('.daypicker').wait_for()
        page.locator('figure svg').first.wait_for()
        unique_axes()
        page.screenshot(path=str(output / 'coach-nutrition.png'), full_page=True)
        page.locator('.subnav').get_by_role('button', name='Техника', exact=True).click()
        page.get_by_role('button', name='Към отбора', exact=True).wait_for()
        # staff plan and exercise management
        page.get_by_role('button', name='Планове', exact=True).click()
        page.get_by_role('heading', name='Планове', exact=True).wait_for()
        page.get_by_role('heading', name='Упражнения', exact=True).wait_for()
        page.get_by_role('button', name='Добави план', exact=True).click()
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
        page.get_by_text('Създай клуб', exact=True).first.click()
        page.get_by_label('Име', exact=True).fill('Browser Demo Club')
        page.get_by_role('button', name='Създай клуб', exact=True).click()
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
        page.get_by_text('Още няма записи на техника.', exact=True).wait_for()
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
            page.get_by_role('button', name='Изтегли моите данни (JSON)', exact=True).click()
        assert result.value.suggested_filename.endswith('.json')
        page.get_by_role('button', name='Изтрий моите скокове', exact=True).click()
        assert page.evaluate('document.activeElement.dataset.action') == 'confirmDelete'
        page.get_by_text('Всички ваши скокове ще бъдат изтрити окончателно. Профилът ви остава.', exact=True).wait_for()
        page.get_by_role('button', name='Отказ', exact=True).click()
        assert page.evaluate('document.activeElement.dataset.action') == 'delete'
        assert page.locator('svg').count() == 6
        page.get_by_role('button', name='Изтрий моите скокове', exact=True).click()
        page.get_by_role('button', name='Потвърди изтриването', exact=True).click()
        page.get_by_text('Скоковете са изтрити.', exact=True).wait_for()
        assert page.locator('svg').count() == 0
        assert not errors, errors
        assert not external, external
        browser.close()
        print('Browser: coach, admin, athlete workflows; BG/EN; desktop/phone; zero page errors or external requests; screenshots saved.')


if __name__ == '__main__':
    check()

"""Optional host-browser smoke check; needs Playwright and a local seeded server."""

import os
from pathlib import Path

from playwright.sync_api import sync_playwright


def check():
    url = os.environ['JUMPSCAN_DASH_URL']
    output = Path('/root/jumpscan/logs/dash')
    output.mkdir(parents=True, exist_ok=True)
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(executable_path='/opt/google/chrome/chrome', headless=True,
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
            page.get_by_role('navigation').wait_for()

        login('coach')
        assert page.locator('table tbody tr').count() == 6
        page.screenshot(path=str(output / 'team-desktop.png'), full_page=True)
        first_name = page.locator('.name-button').first.inner_text()
        page.locator('thead button').first.click()
        assert page.evaluate('document.activeElement.id') == 'sort-name'
        assert page.locator('.name-button').first.inner_text() != first_name
        page.locator('thead button').first.click()
        page.locator('.name-button').first.click()
        page.locator('svg').nth(3).wait_for()
        assert page.evaluate('document.activeElement.tagName') == 'H1'
        assert page.locator('svg').count() == 4
        assert page.locator('svg rect').count() == 10
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
        assert page.get_by_text('под обичайното', exact=True).count() == 1
        # athlete page: feedback thread visible for staff
        page.get_by_role('button', name='Отбор', exact=True).click()
        page.locator('.name-button').first.click()
        page.locator('.comment-box').wait_for()
        page.get_by_role('button', name='Изход', exact=True).click()
        page.locator('#login-form').wait_for()

        login('athlete1')
        page.get_by_role('button', name='Моите сесии', exact=True).wait_for()
        page.get_by_role('button', name='Моите отбелязвания', exact=True).click()
        page.locator('table').wait_for()
        page.get_by_role('button', name='Моите скокове', exact=True).click()
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
        page.locator('svg').nth(3).wait_for()
        assert page.get_by_role('button', name='Отбор', exact=True).count() == 0
        with page.expect_download() as result:
            page.get_by_role('button', name='Изтегли моите данни (JSON)', exact=True).click()
        assert result.value.suggested_filename.endswith('.json')
        page.get_by_role('button', name='Изтрий моите скокове', exact=True).click()
        assert page.evaluate('document.activeElement.dataset.action') == 'confirmDelete'
        page.get_by_text('Всички ваши скокове ще бъдат изтрити окончателно. Профилът ви остава.', exact=True).wait_for()
        page.get_by_role('button', name='Отказ', exact=True).click()
        assert page.evaluate('document.activeElement.dataset.action') == 'delete'
        assert page.locator('svg').count() == 4
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

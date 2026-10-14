// Optional QA using an already-installed Playwright/Chrome. No browser download.
// PLAYWRIGHT_MODULE=/absolute/path/to/playwright/index.mjs CHROME_PATH=/.../Chrome
// QA_DIR=/absolute/temp/output node test/browser-check.mjs
import { createServer } from 'node:http';
import { readFile, mkdir } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import assert from 'node:assert/strict';
const { chromium } = await import(pathToFileURL(process.env.PLAYWRIGHT_MODULE).href);
const root = resolve('dist'), headers = JSON.parse(await readFile(join(root, 'headers.json'), 'utf8'));
const requests = [], pageErrors = [];
const server = createServer(async (req, res) => {
  requests.push(req.url);
  const path = new URL(req.url, 'http://localhost').pathname;
  const name = path === '/pairscooter/' ? 'index.html' : path.slice('/pairscooter/'.length);
  if (!['index.html', 'app.js', 'styles.css', 'recovery.html'].includes(name)) { res.writeHead(404); res.end(); return; }
  const types = { html: 'text/html', css: 'text/css', js: 'text/javascript' };
  res.writeHead(200, { ...headers, 'Content-Type': types[name.split('.').pop()] }); res.end(await readFile(join(root, name)));
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const url = `http://127.0.0.1:${server.address().port}/pairscooter/`;
const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH, headless: true });
try {
  await mkdir(process.env.QA_DIR, { recursive: true });
  for (const width of [1280, 768, 390, 320]) {
    const context = await browser.newContext({ viewport: { width, height: 900 } });
    await context.addInitScript(() => {
      // Consent/cancel path only. This never touches the real browser USB chooser.
      navigator.usb.requestDevice = async () => { throw new DOMException('No device selected', 'NotFoundError'); };
    });
    const page = await context.newPage(); page.on('pageerror', error => pageErrors.push(error.message));
    await page.goto(url); await page.waitForFunction(() => !!document.querySelector('#usb-consent'));
    assert.equal(await page.locator('#environment').isVisible(), false);
    assert.equal(await page.locator('#connect').isDisabled(), true);
    await page.locator('#usb-consent').check(); assert.equal(await page.locator('#connect').isEnabled(), true);
    await page.locator('#connect').click();
    await page.waitForFunction(() => !document.querySelector('#error').hidden);
    assert.match(await page.locator('#error').innerText(), /No phone selected/);
    assert.equal(await page.locator('#capture-panel').isVisible(), false);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
    await page.screenshot({ path: join(process.env.QA_DIR, `connect-${width}.png`), fullPage: true });
    // Each panel at each width: layout/accessibility inspection without faking a
    // successful capture or exposing any real owner data.
    for (const id of ['capture-panel', 'working-panel', 'save-panel', 'recovery']) {
      await page.evaluate(id => {
        for (const panel of document.querySelectorAll('.panel')) panel.hidden = panel.id !== id;
        document.querySelector('#error').hidden = true;
      }, id);
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, `${id} width ${width}`);
      if (width === 390 || width === 1280) await page.screenshot({ path: join(process.env.QA_DIR, `${id}-${width}.png`), fullPage: true });
    }
    if (width === 1280) {
      const before = requests.length;
      await page.evaluate(async () => {
        const script = document.createElement('script'); script.src = './unreviewed.js'; document.head.append(script);
        try { await fetch('./exfil-probe'); } catch { /* expected CSP rejection */ }
      });
      await page.waitForTimeout(100);
      assert(!requests.slice(before).some(x => /unreviewed|exfil/.test(x)));
      await page.goto(`${url}recovery.html`); assert.equal(await page.locator('h1').innerText(), 'Get your phone back to normal.');
    }
    await context.close();
  }
  const swContext = await browser.newContext();
  await swContext.addInitScript(() => Object.defineProperty(navigator.serviceWorker, 'controller', { get: () => ({}) }));
  const swPage = await swContext.newPage(); await swPage.goto(url);
  assert.match(await swPage.locator('#environment').innerText(), /service worker/);
  await swPage.locator('#usb-consent').check(); assert.equal(await swPage.locator('#connect').isDisabled(), true);
  await swContext.close();
  // The legacy origin retains its own journal and recovery path; it must never
  // silently redirect or start a new capture on the shared main-site origin.
  for (const hostname of ['jjricks.com', 'pairscooter.jjricks.com']) {
    const context = await browser.newContext();
    const legacy = hostname === 'jjricks.com';
    await context.addInitScript(({ legacy }) => {
      navigator.usb.requestDevice = async () => { throw new DOMException('No device selected', 'NotFoundError'); };
      if (legacy) localStorage.setItem('volt.owner-capture.recovery.v1', JSON.stringify({ version: 1,
        token: 'a'.repeat(64), serial: 'fixture-phone', original: '', stage: 'preparing' }));
    }, { legacy });
    await context.route('**/*', async route => {
      const path = new URL(route.request().url()).pathname;
      const name = path.endsWith('/') ? 'index.html' : path.split('/').pop();
      if (!['index.html', 'app.js', 'styles.css', 'recovery.html'].includes(name)) { await route.abort(); return; }
      const type = { html: 'text/html', js: 'text/javascript', css: 'text/css' }[name.split('.').pop()];
      await route.fulfill({ status: 200, headers: { ...headers, 'Content-Type': type }, body: await readFile(join(root, name)) });
    });
    const page = await context.newPage(); page.on('pageerror', error => pageErrors.push(error.message));
    const target = `https://${hostname}/${legacy ? 'pairscooter/' : ''}`;
    await page.goto(target);
    assert.equal(page.url(), target);
    assert.equal(await page.locator('#legacy-site').isVisible(), legacy);
    if (legacy) {
      assert.equal(await page.locator('#recovery').isVisible(), true);
      assert.equal(await page.locator('#recover').isEnabled(), true);
      assert.equal(await page.locator('#connect').isDisabled(), true);
      await page.locator('#recover').click();
      await page.waitForFunction(() => !document.querySelector('#error').hidden);
      assert.match(await page.locator('#error').innerText(), /No phone selected/);
      assert.equal(await page.evaluate(() => JSON.parse(localStorage.getItem('volt.owner-capture.recovery.v1')).serial), 'fixture-phone');
      assert.equal(page.url(), target);
    } else {
      await page.locator('#usb-consent').check();
      assert.equal(await page.locator('#connect').isEnabled(), true);
      assert.equal(await page.evaluate(() => localStorage.getItem('volt.owner-capture.recovery.v1')), null);
    }
    await context.close();
  }
  assert.deepEqual(pageErrors, []);
  console.log(JSON.stringify({ widths: [1280, 768, 390, 320], allPanelsFit: true, usbConsentAndCancel: true, unreviewedScriptBlocked: true, uploadBlocked: true, serviceWorkerBlocked: true, legacyRecoveryPreserved: true, dedicatedOriginEnabled: true, pageErrors, screenshots: process.env.QA_DIR }, null, 2));
} finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }

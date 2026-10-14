import { build } from 'esbuild';
import { mkdir, readFile, writeFile, readdir } from 'node:fs/promises';
import { createHash } from 'node:crypto';
await mkdir('dist', { recursive: true });
await build({ entryPoints: ['src/main.js'], bundle: true, outfile: 'dist/app.js', format: 'iife', target: ['chrome120'], minify: true, legalComments: 'external', sourcemap: false });
const script = await readFile('dist/app.js');
const integrity = `sha256-${createHash('sha256').update(script).digest('base64')}`;
const policy = `default-src 'none'; script-src '${integrity}'; style-src 'self'; img-src 'self'; connect-src 'none'; font-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'; worker-src 'none'; frame-src 'none'`;
const html = (await readFile('index.html', 'utf8')).replace('__CSP__', policy).replace('__INTEGRITY__', integrity);
await writeFile('dist/index.html', html);
await writeFile('dist/styles.css', await readFile('styles.css'));
await writeFile('dist/recovery.html', await readFile('recovery.html'));
await writeFile('dist/headers.json', JSON.stringify({
  'Content-Security-Policy': `${policy}; frame-ancestors 'none'`,
  'Permissions-Policy': 'usb=(self), camera=(), microphone=(), geolocation=()',
  'Referrer-Policy': 'no-referrer', 'X-Content-Type-Options': 'nosniff', 'X-Frame-Options': 'DENY',
  'Cache-Control': 'no-store', 'Cross-Origin-Opener-Policy': 'same-origin'
}, null, 2) + '\n');
// Preserve MIT notices for every bundled transitive dependency; no CDN fetches.
let licenses = 'Volt USB owner setup — bundled third-party licenses\n\n';
for (const scope of ['@yume-chan']) {
  for (const name of await readdir(`node_modules/${scope}`)) {
    const base = `node_modules/${scope}/${name}`;
    const pkg = JSON.parse(await readFile(`${base}/package.json`, 'utf8'));
    const files = await readdir(base), license = files.find(x => /^license(?:\.txt|\.md)?$/i.test(x));
    if (!license) throw new Error(`Missing license: ${name}`);
    licenses += `=== ${scope}/${name} ${pkg.version} ===\n${await readFile(`${base}/${license}`, 'utf8')}\n\n`;
  }
}
await writeFile('dist/THIRD-PARTY-NOTICES.txt', licenses);
console.log('Built dist/ with bundled dependencies, exact script CSP/SRI, and hosting headers.');

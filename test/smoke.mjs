// Røyktest av launcheren på emulator (kjøres i GitHub Actions).
// Setter den som startskjerm, sjekker hjemskjerm, applisten med søk, appmeny, favoritter,
// pauseskjerm, widgetsiden og varselfilteret. Tar skjermbilder underveis (skjerm-*.png).
import { execSync } from 'node:child_process';

const PKG = 'no.oystein.launcher';
const sh = (c) => execSync(c, { encoding: 'utf8', maxBuffer: 64 << 20 }).trim();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
function fail(msg) {
  console.error('FEIL: ' + msg);
  try { console.error(sh(`adb logcat -d -s AndroidRuntime:E | tail -60`)); } catch {}
  shot('feil');
  process.exit(1);
}
function shot(name) {
  try { sh(`adb shell screencap -p /sdcard/s.png && adb pull /sdcard/s.png skjerm-${name}.png`); } catch {}
}

function dump() {
  for (let i = 0; i < 5; i++) {
    try {
      sh('adb shell uiautomator dump /sdcard/ui.xml');
      return sh('adb shell cat /sdcard/ui.xml');
    } catch { }
  }
  return '';
}
function nodes(xml) {
  const out = [];
  for (const m of xml.matchAll(/<node [^>]*>/g)) {
    const a = m[0];
    const text = (a.match(/ text="([^"]*)"/) || [])[1] || '';
    const cls = (a.match(/ class="([^"]*)"/) || [])[1] || '';
    const b = (a.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/) || []).slice(1).map(Number);
    if (b.length === 4) out.push({ text: text.replace(/&amp;/g, '&'), cls, x: (b[0] + b[2]) >> 1, y: (b[1] + b[3]) >> 1 });
  }
  return out;
}
async function waitText(re, ms = 15000) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    const n = nodes(dump()).find((n) => re.test(n.text));
    if (n) return n;
    await sleep(700);
  }
  return null;
}
async function need(re, what, ms) {
  const n = await waitText(re, ms);
  if (!n) fail(`fant ikke ${what} (${re}). Synlig tekst: ` + nodes(dump()).map((n) => n.text).filter(Boolean).join(' | '));
  return n;
}
const tap = (n) => sh(`adb shell input tap ${n.x} ${n.y}`);
const longPress = (n) => sh(`adb shell input swipe ${n.x} ${n.y} ${n.x} ${n.y} 1200`);
const size = sh('adb shell wm size').match(/(\d+)x(\d+)/).slice(1).map(Number);
const [W, H] = size;
const swipeLeft = () => sh(`adb shell input swipe ${W * 0.85 | 0} ${H / 2 | 0} ${W * 0.1 | 0} ${H / 2 | 0} 250`);
const swipeRight = () => sh(`adb shell input swipe ${W * 0.1 | 0} ${H / 2 | 0} ${W * 0.85 | 0} ${H / 2 | 0} 250`);
const home = async () => { sh('adb shell input keyevent KEYCODE_HOME'); await sleep(1500); };
function assertAlive() {
  let pid = '';
  try { pid = sh(`adb shell pidof ${PKG}`); } catch {}
  if (!pid) fail('launcheren kjører ikke (krasj?)');
  const crash = sh(`adb logcat -d -s AndroidRuntime:E`);
  if (crash.includes(PKG)) fail('krasj i logcat');
}

// Tilganger som ellers gis i innstillingene på telefonen.
sh(`adb shell appops set ${PKG} GET_USAGE_STATS allow`);
sh(`adb shell cmd notification allow_listener ${PKG}/${PKG}.NotificationFilterService`);
sh(`adb shell appwidget grantbind --package ${PKG}`);
sh(`adb shell cmd package set-home-activity ${PKG}/.MainActivity`);
sh('adb logcat -c');
sh('adb shell input keyevent KEYCODE_WAKEUP');
sh('adb shell wm dismiss-keyguard || true');
await home();

// 1. Hjemskjerm
await need(/^\d{1,2}:\d{2}$/, 'klokke', 30000);
await need(/(mandag|tirsdag|onsdag|torsdag|fredag|lørdag|søndag) \d+\./, 'dato');
await need(/Sveip til venstre/, 'hint om favoritter');
shot('1-hjem');
console.log('Hjemskjerm OK');

// 2. Applisten med søk
swipeLeft();
{
  const end = Date.now() + 15000;
  let field = null;
  while (!field && Date.now() < end) { field = nodes(dump()).find((n) => n.cls === 'android.widget.EditText'); if (!field) await sleep(700); }
  if (!field) fail('fant ikke søkefelt. Synlig tekst: ' + nodes(dump()).map((n) => n.text).filter(Boolean).join(' | '));
}
await need(/^Innstillinger$|^Settings$|^Chrome$|^Clock$/, 'apper i listen');
shot('2-apper');
sh('adb shell input text sett');
const settingsApp = await need(/^Settings$/, 'søketreff «Settings»');
shot('3-sok');
console.log('Appliste og søk OK');

// 3. Appmeny: legg Settings til på hjemskjermen og slå på pauseskjerm
longPress(await need(/^Settings$/, 'Settings'));
tap(await need(/Legg til på hjemskjermen/, 'menyvalg favoritt'));
await sleep(800);
longPress(await need(/^Settings$/, 'Settings'));
tap(await need(/^Pauseskjerm: av$/, 'menyvalg pauseskjerm'));
await sleep(800);
// Endre navn
longPress(await need(/^Settings$/, 'Settings'));
tap(await need(/^Endre navn$/, 'menyvalg endre navn'));
await sleep(800);
sh('adb shell input keyevent KEYCODE_MOVE_END');
for (let i = 0; i < 10; i++) sh('adb shell input keyevent KEYCODE_DEL');
sh('adb shell input text Oppsett');
tap(await need(/^Lagre$/, 'Lagre'));
await need(/^Oppsett$/, 'nytt navn i listen');
console.log('Appmeny OK');

// 4. Favoritt på hjemskjermen + pauseskjerm
await home();
const fav = await need(/^Oppsett$/, 'favoritt på hjemskjermen');
shot('4-hjem-favoritt');
tap(fav);
await need(/Pust rolig/, 'pauseskjerm');
shot('5-pause');
await need(/^Åpne Oppsett$/, 'Åpne-knapp etter nedtelling', 25000);
tap(await need(/^Avbryt$/, 'Avbryt'));
await need(/^Oppsett$/, 'tilbake på hjemskjermen');
console.log('Pauseskjerm OK');

// 5. Widgetsiden
swipeRight();
const pick = await need(/^Velg widget$/, 'Velg widget');
shot('6-widget-tom');
tap(pick);
await sleep(1500);
const list = nodes(dump()).filter((n) => n.text && !/^(Velg widget|Lukk)$/.test(n.text));
console.log('Widgeter:', list.map((n) => n.text).join(' | '));
if (!list.length) fail('ingen widgeter i listen');
const choice = list.find((n) => /Clock|Klokke|Calendar|Kalender/i.test(n.text)) || list[0];
tap(choice);
await sleep(4000);
let ui = nodes(dump());
if (!ui.some((n) => n.text === 'Bytt widget')) {
  // Widgeten åpnet et oppsettvindu – prøv å bekrefte eller gå tilbake.
  shot('7-widget-oppsett');
  sh('adb shell input keyevent KEYCODE_BACK');
  await sleep(1500);
}
shot('7-widget');
console.log('Widgetside OK');

// 6. Innstillinger
await home();
await need(/^\d{1,2}:\d{2}$/, 'klokke');
longPress({ x: W / 2 | 0, y: H * 0.88 | 0 });
await need(/^Innstillinger$/, 'innstillinger');
await need(/^Standard startskjerm$/, 'tilgangsliste');
shot('8-innstillinger');
await home();
console.log('Innstillinger OK');

// 7. Varselfilter: demp varsler fra Shell (det er den som poster varsler fra adb).
await sleep(1500);
sh(`adb shell am broadcast -a ${PKG}.TEST_MUTE --es pkg com.android.shell -p ${PKG} || true`);
await sleep(1500);
sh(`adb shell cmd notification post -t Testvarsel smoke "Hei fra testen"`);
await sleep(3000);
const active = sh('adb shell dumpsys notification --noredact');
const stillThere = /pkg=com\.android\.shell[^\n]*\n[\s\S]{0,400}smoke/.test(active) && active.includes('Testvarsel');
await home();
const mutedLine = await waitText(/dempe(t|de) varsel/, 8000);
shot('9-varsler');
if (!mutedLine) fail('varselet ble ikke dempet/samlet' + (stillThere ? ' (ligger fortsatt i varsellinjen)' : ''));
tap(mutedLine);
await need(/Testvarsel/, 'det dempede varselet i listen');
shot('10-varselliste');
console.log('Varselfilter OK');

assertAlive();
console.log('Røyktest OK');

// Røyktest av launcheren på emulator (kjøres i GitHub Actions).
// Setter den som startskjerm, sjekker hjemskjerm, applisten med søk, appmeny, favoritter,
// pauseskjerm, widgetsiden og varselfilteret. Tar skjermbilder underveis (skjerm-*.png).
import { execSync } from 'node:child_process';

const PKG = 'no.oystein.launcher';
const sh = (c) => execSync(c, { encoding: 'utf8', maxBuffer: 64 << 20 }).trim();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
function fail(msg) {
  console.error('FEIL: ' + msg);
  try { console.error('Launcher-logg:\n' + sh(`adb logcat -d -s Launcher:D`)); } catch {}
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
    const desc = (a.match(/ content-desc="([^"]*)"/) || [])[1] || '';
    const b = (a.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/) || []).slice(1).map(Number);
    if (b.length === 4) out.push({ text: text.replace(/&amp;/g, '&'), cls, desc, x: (b[0] + b[2]) >> 1, y: (b[1] + b[3]) >> 1 });
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
// Ingen «appwidget grantbind»: testen går gjennom Androids tillatelsesdialog slik brukeren gjør.
// Rolletjenesten er ikke alltid klar rett etter oppstart av emulatoren, så prøv flere ganger.
for (let i = 0; ; i++) {
  try { sh(`adb shell cmd package set-home-activity ${PKG}/.MainActivity`); break; } catch {}
  try { sh(`adb shell cmd role add-role-holder android.app.role.HOME ${PKG}`); break; } catch {}
  if (i >= 10) fail('kunne ikke sette launcheren som startskjerm');
  await sleep(3000);
}
sh('adb logcat -c');
sh('adb shell input keyevent KEYCODE_WAKEUP');
sh('adb shell wm dismiss-keyguard || true');
await home();

const QUICK = process.env.QUICK === '1';

// 1. Hjemskjerm
await need(/^\d{1,2}:\d{2}$/, 'klokke', 30000);
await need(/(mandag|tirsdag|onsdag|torsdag|fredag|lørdag|søndag) \d+\./, 'dato');
await need(/Sveip til venstre/, 'hint om favoritter');
shot('1-hjem');
console.log('Hjemskjerm OK');

// Snarveiene i hjørnene: telefon nede til venstre, kamera nede til høyre.
const topActivity = () => {
  try { return sh(`adb shell dumpsys activity activities | grep -m1 -E "topResumedActivity|mResumedActivity"`); } catch { return ''; }
};
async function needDesc(desc) {
  const end = Date.now() + 10000;
  while (Date.now() < end) {
    const n = nodes(dump()).find((n) => n.desc === desc);
    if (n) return n;
    await sleep(700);
  }
  fail(`fant ikke ikonet «${desc}» på hjemskjermen`);
}
if (!QUICK) {
  const phone = await needDesc('Telefon');
  const sms = await needDesc('Meldinger');
  const camera = await needDesc('Kamera');
  if (!(phone.x < W / 3 && Math.abs(sms.x - W / 2) < W / 8 && camera.x > W * 2 / 3 && phone.y > H * 0.7)) {
    fail(`ikonene står feil: telefon (${phone.x},${phone.y}), meldinger (${sms.x}), kamera (${camera.x})`);
  }
  tap(sms);
  await sleep(2500);
  const afterSms = topActivity();
  console.log('Etter meldinger:', afterSms);
  if (!/messag|mms|sms/i.test(afterSms)) fail('meldingsappen åpnet seg ikke');
  await home();
  tap(phone);
  await sleep(2500);
  const afterPhone = topActivity();
  console.log('Etter telefon:', afterPhone);
  if (!/dialer|contacts|phone/i.test(afterPhone)) fail('telefonappen åpnet seg ikke');
  await home();
  tap(await needDesc('Kamera'));
  await sleep(3000);
  const afterCamera = topActivity();
  console.log('Etter kamera:', afterCamera);
  if (!/camera|permissioncontroller/i.test(afterCamera)) fail('kameraet åpnet seg ikke');
  await home();
  console.log('Telefon og kamera OK');
}

// 2. Applisten med søk
swipeLeft();
{
  const end = Date.now() + 15000;
  let retried = false;
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

if (QUICK) {
  // Rask sjekk av det krympede release-bygget: hjem, appliste og widgetside uten krasj.
  await home();
  await sleep(1500); // la tastaturet lukke seg først
  let page = null;
  for (let i = 0; i < 3 && !page; i++) {
    swipeRight();
    page = await waitText(/^(Legg til widget|Rediger)$/, 6000);
  }
  if (!page) fail('fant ikke widgetsiden i release-bygget');
  shot('release-widget');
  await home();
  assertAlive();
  console.log('Release-bygget OK');
  process.exit(0);
}

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

// 4b. Grupper: legg Oppsett i gruppen «System» fra hjemskjermen.
longPress(await need(/^Oppsett$/, 'Oppsett på hjemskjermen'));
tap(await need(/^Legg i gruppe$/, 'menyvalg gruppe'));
await sleep(1000);
sh('adb shell input text System');
tap(await need(/^Lag gruppe$/, 'Lag gruppe'));
await home();
const groupHeader = await need(/^System$/, 'gruppen på hjemskjermen');
if (nodes(dump()).some((n) => n.text === 'Oppsett')) fail('appen i gruppen skal være skjult til gruppen åpnes');
tap(groupHeader);
await need(/^Oppsett$/, 'appen i den åpne gruppen');
shot('5b-gruppe-hjem');
swipeLeft();
await sleep(1000);
const listNodes = nodes(dump()).filter((n) => n.text);
const gi = listNodes.findIndex((n) => n.text === 'System');
const first = listNodes.findIndex((n) => /^(Calendar|Camera|Chrome|Clock)$/.test(n.text));
if (gi < 0 || first < 0 || gi > first) fail('gruppen skal stå øverst i applisten: ' + listNodes.map((n) => n.text).join(' | '));
shot('5c-gruppe-liste');
// Bokstavlisten: dra ned til siste bokstav og sjekk at listen hopper dit.
// Tastaturet kan dukke opp rett etter at siden vises, så vent og prøv på nytt med ferske posisjoner.
await sleep(2000);
let letters = [];
let last = null;
let jumped = false;
for (let attempt = 0; attempt < 3 && !jumped; attempt++) {
  letters = nodes(dump()).filter((n) => /^[A-ZÆØÅ#]$/.test(n.text) && n.x > W * 0.8);
  if (letters.length < 3) { await sleep(1000); continue; }
  last = letters.reduce((a, b) => (b.y > a.y ? b : a));
  sh(`adb shell input swipe ${last.x} ${letters[0].y} ${last.x} ${last.y} 600`);
  await sleep(1200);
  const after = nodes(dump()).filter((n) => n.text && n.x < W * 0.8);
  jumped = after.some((n) => n.text.toUpperCase().startsWith(last.text));
  if (!jumped) console.log(`Forsøk ${attempt + 1}: listen viser ` + after.map((n) => n.text).join(' | '));
}
shot('5d-bokstaver');
if (letters.length < 3) fail('fant ikke bokstavlisten: ' + nodes(dump()).map((n) => n.text).filter(Boolean).join(' | '));
if (!jumped) fail(`listen hoppet ikke til «${last.text}»`);
console.log(`Bokstavliste OK (${letters.map((n) => n.text).join('')})`);
await home();
console.log('Grupper OK');

// 5. Widgetsiden: legg til to widgeter, rediger og endre høyde.
swipeRight();
shot('6-widget-tom');
// Antall widgeter launcheren har lagret (fra loggen).
const savedWidgets = () => {
  try { return sh(`adb logcat -d -s Launcher:D`).split('\n').filter((l) => l.includes('finishWidget')).length; } catch { return 0; }
};
// Som på iPhone: hold inne for å redigere; «+ Legg til» (eller «Legg til widget» på tom side) åpner panelet.
async function enterEdit() {
  for (let i = 0; i < 3; i++) {
    if (nodes(dump()).some((n) => n.text === 'Ferdig')) return;
    longPress({ x: W / 2 | 0, y: H * 0.55 | 0 });
    if (await waitText(/^Ferdig$/, 4000)) return;
  }
  fail('kom ikke i redigering. Synlig tekst: ' + nodes(dump()).map((n) => n.text).filter(Boolean).join(' | '));
}
async function openWidgetSheet() {
  for (let i = 0; i < 3; i++) {
    const ui = nodes(dump());
    if (ui.some((n) => n.text === 'Velg widget')) return;
    const add = ui.find((n) => /^(\+ Legg til|Legg til widget)$/.test(n.text));
    if (add) tap(add); else longPress({ x: W / 2 | 0, y: H * 0.55 | 0 });
    if (await waitText(/^Velg widget$/, 4000)) return;
  }
  fail('fikk ikke opp widgetpanelet. Synlig tekst: ' + nodes(dump()).map((n) => n.text).filter(Boolean).join(' | '));
}
async function searchWidgets(query) {
  const end = Date.now() + 10000;
  let field = null;
  while (!field && Date.now() < end) { field = nodes(dump()).find((n) => n.cls === 'android.widget.EditText'); if (!field) await sleep(700); }
  if (!field) fail('fant ikke søkefeltet i widgetpanelet');
  tap(field);
  await sleep(500);
  sh(`adb shell input text ${query.replace(/ /g, '%s')}`);
  await sleep(800);
}
// Legger til en widget. Svarer true når launcheren har lagret den.
async function addWidget(re, query) {
  const before = savedWidgets();
  await openWidgetSheet();
  await searchWidgets(query);
  const choice = await need(re, 'widgeten i panelet');
  console.log('Velger widget:', choice.text);
  tap(choice);
  const end = Date.now() + 20000;
  let retried = false;
  while (Date.now() < end) {
    const ui = nodes(dump());
    const texts = ui.map((n) => n.text).filter(Boolean);
    if (savedWidgets() > before) { await sleep(1000); return true; }
    if (texts.includes('Ingen widgeter') && Date.now() > end - 12000) return false;
    // Androids dialog «Tillat at Launcher oppretter moduler …»: kryss av for «alltid» og trykk Opprett.
    const create = ui.find((n) => /^(Create|CREATE|Opprett|OPPRETT)$/.test(n.text));
    if (create) {
      shot('7-widget-tillatelse');
      const always = ui.find((n) => /Always allow|Tillat alltid/i.test(n.text));
      if (always) tap(always);
      tap(create);
      await sleep(1500);
      continue;
    }
    if (texts.includes('Velg widget')) {
      if (!retried && Date.now() > end - 16000) {
        retried = true;
        console.log('Widgetlisten er fortsatt åpen, trykker på nytt');
        tap(ui.find((n) => re.test(n.text)) || choice);
      }
      await sleep(700);
      continue;
    }
    if (texts.some((t) => /^(mandag|tirsdag|onsdag|torsdag|fredag|lørdag|søndag) \d+\./.test(t))) {
      console.log('Noder:', ui.filter((n) => n.text || n.desc).map((n) => `${n.text || n.desc}@${n.x},${n.y}`).join(' | '));
      console.log('Øverste aktivitet:', topActivity());
      fail('launcheren hoppet til hjemskjermen under widgetoppsettet (skal bli på widgetsiden)');
    }
    const ours = texts.some((t) => /^(Legg til widget|Ingen widgeter|\+ Legg til|Ferdig)$/.test(t)) ||
      (ui.length > 0 && texts.length === 0); // widgetsiden med bare widgeter har ingen egne tekster
    if (texts.length && !ours) {
      // Widgetens eget oppsett: bekreft hvis det finnes en knapp for det, ellers tilbake.
      console.log('Oppsettskjerm for widgeten:', texts.join(' | '));
      shot('7-widget-oppsett');
      const ok = ui.find((n) => /^(Done|DONE|Save|SAVE|OK|Ferdig|Lagre|Add|Legg til)$/.test(n.text));
      if (ok) {
        tap(ok);
      } else {
        // Trykk Tilbake bare hvis oppsettet fortsatt er åpent; ellers havner det hos launcheren.
        await sleep(1000);
        const still = nodes(dump()).map((n) => n.text).filter(Boolean);
        if (still.length && !still.some((t) => /^(Legg til widget|Ingen widgeter|\+ Legg til|Ferdig)$/.test(t)) &&
            !still.some((t) => /^(mandag|tirsdag|onsdag|torsdag|fredag|lørdag|søndag) \d+\./.test(t))) {
          console.log('Lukker widgetens oppsett med Tilbake');
          sh('adb shell input keyevent KEYCODE_BACK');
        } else {
          console.log('Oppsettet lukket seg selv');
        }
      }
      await sleep(2000);
      continue;
    }
    await sleep(700);
  }
  console.log('Synlig tekst:', nodes(dump()).map((n) => n.text).filter(Boolean).join(' | '));
  return savedWidgets() > before;
}
// Klokkewidgeten har eget oppsett (tester den veien); Chrome-widgetene har det ikke.
let added = 0;
for (const [re, q] of [[/^Clock – Digital$/, 'digital'], [/^Chrome – Chrome search$/, 'chrome search'], [/^Chrome – Chrome Dino$/, 'dino']]) {
  if (added >= 2) break;
  if (await addWidget(re, q)) added++;
  console.log('Widgeter lagt til:', added);
  await sleep(1000);
}
if (added < 2) fail('fikk ikke lagt til to widgeter');
shot('7-widget');
await enterEdit();
await sleep(800);
const removes = nodes(dump()).filter((n) => n.desc === 'Fjern');
console.log('Widgeter på siden:', removes.length);
if (removes.length < 2) fail('forventet to widgeter på siden, fant ' + removes.length);
// Gjør den første widgeten mindre (dra hjørnet), og flytt den andre ned under den.
const handles = nodes(dump()).filter((n) => n.text === '◢');
if (handles.length < 2) fail('fant ikke hjørnehåndtakene');
const h0 = handles.reduce((a, b) => (b.y < a.y ? b : a));
const topHandle = () => nodes(dump()).filter((n) => n.text === '◢').reduce((a, b) => (b.y < a.y ? b : a));
const xBefore = h0.x;
sh(`adb shell input swipe ${h0.x} ${h0.y} ${h0.x - (W / 2 | 0)} ${h0.y} 800`);
await sleep(1500);
shot('7a-widget-mindre');
const fjern = nodes(dump()).filter((n) => n.desc === 'Fjern').sort((a, b) => b.y - a.y);
const lower = fjern[0];
sh(`adb shell input swipe ${lower.x + 80} ${lower.y + 60} ${lower.x + 80} ${lower.y + 260} 900`);
await sleep(1500);
shot('7b-widget-rediger');
tap(await need(/^Ferdig$/, 'Ferdig'));
// Plasseringen skal være lagret: gå hjem og tilbake, og sjekk at den første widgeten er smalere.
await home();
swipeRight();
await sleep(1000);
await enterEdit();
await sleep(1500);
const xAfter = topHandle().x;
console.log(`Øverste widget: hjørnet x ${xBefore} → ${xAfter}`);
if (xAfter > xBefore - W / 4) fail('ny størrelse ble ikke lagret');
shot('7c-widget-lagret');
tap(await need(/^Ferdig$/, 'Ferdig'));
assertAlive();
// Søk i widgetpanelet.
await openWidgetSheet();
{
  await searchWidgets('dino');
  await need(/^Chrome – Chrome Dino$/, 'søketreff «Dino»');
  const texts = nodes(dump()).map((n) => n.text);
  if (texts.some((t) => /^Clock – /.test(t))) fail('søket filtrerte ikke bort andre widgeter: ' + texts.filter(Boolean).join(' | '));
  shot('7d-widget-sok');
  tap(await need(/^Lukk$/, 'Lukk'));
  await sleep(800);
}
console.log('Launcher-logg:\n' + sh(`adb logcat -d -s Launcher:D`));
console.log('Widgetside OK');

// 6. Innstillinger
await home();
await need(/^\d{1,2}:\d{2}$/, 'klokke');
// Tom plass mellom favorittene og snarveiene nederst.
longPress({ x: W / 2 | 0, y: H * 0.75 | 0 });
await need(/^Innstillinger$/, 'innstillinger');
await need(/^Standard startskjerm$/, 'tilgangsliste');
shot('8-innstillinger');
// Skjul «Meldinger»-snarveien og sjekk at den forsvinner fra hjemskjermen.
let smsRow = null;
for (let i = 0; i < 4 && !smsRow; i++) {
  smsRow = nodes(dump()).find((n) => n.text === 'Meldinger');
  if (!smsRow) { sh(`adb shell input swipe ${W / 2 | 0} ${H * 0.7 | 0} ${W / 2 | 0} ${H * 0.35 | 0} 400`); await sleep(800); }
}
if (!smsRow) fail('fant ikke snarveien «Meldinger» i innstillingene');
const toggle = nodes(dump()).filter((n) => n.text === 'på' && Math.abs(n.y - smsRow.y) < 30)[0];
if (!toggle) fail('fant ikke av/på for «Meldinger»');
tap(toggle);
await need(/^av$/, 'snarveien slått av');
shot('8b-snarvei-av');
await home();
await needDesc('Telefon');
if (nodes(dump()).some((n) => n.desc === 'Meldinger')) fail('«Meldinger» vises fortsatt på hjemskjermen');
console.log('Innstillinger og snarveier OK');

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

// 8. Festede snarveier (nettapper/PWA): be om å feste en snarvei slik Chrome gjør.
await home();
sh(`adb shell am broadcast -a ${PKG}.TEST_PIN -p ${PKG}`);
const pinned = await waitText(/^Testsnarvei$/, 15000);
console.log('Launcher-logg (snarvei):\n' + sh(`adb logcat -d -s Launcher:D | grep -i -E "shortcut|pin"`));
if (!pinned) fail('den festede snarveien dukket ikke opp på hjemskjermen');
shot('11-snarvei');
tap(pinned);
await sleep(3000);
const afterShortcut = topActivity();
console.log('Etter snarvei:', afterShortcut);
if (/no\.oystein\.launcher\/\.MainActivity/.test(afterShortcut)) fail('snarveien åpnet ingenting');
await home();
longPress(await need(/^Testsnarvei$/, 'snarveien på hjemskjermen'));
tap(await need(/^Fjern snarvei$/, 'menyvalget «Fjern snarvei»'));
await sleep(2000);
if (nodes(dump()).some((n) => n.text === 'Testsnarvei')) fail('snarveien ble ikke fjernet');
console.log('Festede snarveier OK');

assertAlive();
console.log('Røyktest OK');

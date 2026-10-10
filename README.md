# Launcher

Minimalistisk Android-startskjerm (Kotlin + Jetpack Compose, pakke `no.oystein.launcher`), laget som erstatning for
Minimalist Phone. Svart bakgrunn, kun tekst, ingen ikoner.

## Sider

| Sveip | Side |
|---|---|
| – | **Hjemskjerm**: klokke, dato, skjermtid i dag, antall dempede varsler og favorittene dine. Nederst snarveier til telefon, meldinger og kamera; rekkefølge og hvilke som vises velges i innstillingene |
| Til venstre | **Appliste** med søk. Enter/«Gå» åpner første treff. Dra fingeren langs bokstavene til høyre for å hoppe til en forbokstav. Tannhjulet oppe til høyre åpner innstillingene |
| Til høyre | **Widgeter** i et rutenett med 4 kolonner, redigert som på iPhone: **hold fingeren inne** for å redigere (widgetene vrikker). Dra en widget for å flytte den, hjørnet ◢ for å endre størrelse, og − for å fjerne. «+ Legg til» åpner et panel nedenfra med søk og forhåndsvisning av alle widgeter. «Ferdig» avslutter |

- **Hold inne på en app i applisten:** legg til/fjern på hjemskjermen, endre navn, skjul, pauseskjerm, daglig grense,
  demp varsler, appinfo, avinstaller.
- **Sortere hjemskjermen:** hold inne på en app på hjemskjermen og dra den opp eller ned (den uthevs mens du holder).
  I sorteringsmodus har hver app og gruppe ⋯ helt til høyre som åpner menyen over. «Ferdig», Tilbake eller Hjem låser.
- **Grupper:** hold inne på en app → «Legg i gruppe». Grupper står øverst i alfabetisk rekkefølge, før enkeltappene,
  både i applisten og på hjemskjermen (der med favorittene som ligger i gruppen). Trykk på en gruppe for å åpne/lukke
  den, hold inne for å endre navn eller slette den (appene beholdes).
- **Hold inne på tom plass** på hjemskjermen: innstillinger (tilganger, favorittrekkefølge, widget, pauseskjerm,
  grenser, dempede apper, skjulte apper, skjermtid i dag).
- **Sveip ned** hvor som helst på hjemskjermen (også over favorittene og nederst): varselpanelet. Trykk på klokken for alarmer, på datoen for kalenderen.
- **Nettapper (PWA) og snarveier:** «Legg til på startskjermen»/«Installer» i Chrome legger snarveien i applisten og
  på hjemskjermen. Hold inne på den og velg «Fjern snarvei» for å fjerne den. Snarveier som ble lagt til mens en annen
  startskjerm var standard, følger ikke med – legg dem til på nytt fra Chrome.
- **Hjem-knappen** går alltid tilbake til hjemskjermen. Det gjør også opplåsing.

**Pauseskjerm:** for valgte apper vises en nedtelling (standard 10 sek) før «Åpne» dukker opp. Når dagens grense
for en app er nådd, er nedtellingen tre ganger så lang (minst 30 sek). Gjelder når appen åpnes fra launcheren.

**Varselfilter:** varsler fra valgte apper fjernes fra varsellinjen og samles under «N dempede varsler» på hjemskjermen.
Trykk for å åpne, hold inne for å fjerne. Pågående varsler (musikk, navigasjon, samtaler, alarmer) slippes gjennom.
Android spiller av lyd før launcheren får varselet, så slå også av lyd for appen (Innstillinger → Varsler) hvis det
skal være helt stille.

Alle innstillinger lagres med DataStore.

## Installere

GitHub Actions bygger APK-en, tester den på en emulator og publiserer den under **Releases** (`launcher-v1.0.N`).

```bash
adb install -r Launcher-1.0.N.apk
adb shell cmd package set-home-activity no.oystein.launcher/.MainActivity   # valgfritt, ellers velg i appen
```

Eller last ned APK-en på telefonen og åpne den. Deretter, i launcherens innstillinger (hold inne på hjemskjermen):

1. **Standard startskjerm** → velg Launcher.
2. **Varseltilgang** → slå på. Er bryteren grå («begrenset innstilling»), gå til Appinfo for Launcher → ⋮ →
   «Tillat begrensede innstillinger» først. (Gjelder ikke når appen er installert med adb.)
3. **Brukstilgang** → slå på (trengs for skjermtid og grenser).

Nye versjoner installeres over den gamle (samme signeringsnøkkel, `app/launcher.keystore`), og innstillingene beholdes.

## Utvikling

```bash
cd launcher && gradle assembleDebug        # krever Android SDK (compileSdk 35)
adb install -r app/build/outputs/apk/debug/app-debug.apk
node test/smoke.mjs                        # røyktest mot tilkoblet enhet/emulator (bruker adb)
```

Røyktesten bruker en egen mottaker som bare finnes i debug-bygget (`src/debug`) for å slå på varseldemping.

# JARVIS – Phase 1

## APK bauen
1. Android Studio (Ladybug oder neuer) installieren, Ordner `JARVIS` öffnen, Gradle-Sync abwarten.
2. Menü: Build → Build APK(s). Die APK liegt danach unter `app/build/outputs/apk/debug/app-debug.apk`.
3. APK aufs Handy kopieren und installieren (Installation aus unbekannten Quellen erlauben).

## Phase 1 enthält
Setup-Assistent mit automatischer Berechtigungsführung, Room-Datenbank (Erinnerungen + Memory-Tabelle),
Foreground Service, Boot Receiver, Erinnerungen (AlarmManager, einmalig/täglich/wöchentlich),
JARVIS-Anruf (High-Priority + Full-Screen-Intent, TTS beim Annehmen), Tool-Registry als Grundlage für Phase 3.

## Noch nicht enthalten
Google-Login/Gmail/Kalender-Tools (Phase 2), KI/Websuche/Sprache/Memory-UI (Phase 3).

## Ohne Android Studio (nur Handy/Browser)
1. Auf github.com ein kostenloses Konto und ein neues Repository anlegen.
2. Projektinhalt hochladen (inkl. versteckter Ordner `.github`). Am einfachsten: Repository → Code → Codespaces (im Browser), ZIP hochladen, im Terminal `unzip JARVIS-Phase1.zip && cp -r JARVIS/. . && rm -r JARVIS JARVIS-Phase1.zip`, dann committen und pushen.
3. Reiter „Actions" → „Build APK" abwarten (ca. 5 Min.) → unten bei „Artifacts" `JARVIS-apk` herunterladen, entpacken, installieren.

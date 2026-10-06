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

## Version 0.2
KI: Google Gemini (kostenloser Schlüssel von aistudio.google.com/apikey). Stimme: Gemini-TTS + Roboter-Effekt, Fallback Handy-Stimme.
JARVIS-Anrufe sind echte Gespräche (klingeln, annehmen, abwechselnd sprechen, auflegen).
Gmail: Google-Cloud-Projekt mit Android-OAuth-Client nötig. Paketname: de.jarvis.app, SHA-1 des mitgelieferten Schlüssels:
A7:27:B3:4A:1F:59:D4:DE:C4:73:52:00:BD:99:A0:9F:18:FF:92:29
Wichtig: Repository auf "Private" stellen (der Signaturschlüssel liegt im Repo). Alte Version vorher deinstallieren (neue Signatur).

## Version 0.3
Stimme: Edge-Neural (gratis, ohne Schlüssel) + optional Google Chirp / ElevenLabs / Gemini / Handy, danach KI-Effekt.
KI-Routing: schnell (Flash-Lite) für einfache Fragen, Flash/Pro für schwere Aufgaben und Anhänge.
Anrufe über Android-Telecom (selbstverwaltet) mit Anrufstil-Benachrichtigung; Auflegen immer sichtbar.
Chat: lange Texte, Markdown, Foto/PDF/Text/Audio-Anhänge, Tastatur-sicheres Layout.

## Version 0.4
KI: Groq (kostenlos, überall) + Gemini, Websuche ohne Schlüssel, echte Fehlermeldungen.
Handy-Steuerung: Apps öffnen, YouTube-Suche, Navigation, Bedienungshilfe (lesen/tippen/scrollen).
Standard-Assistent (Voice Interaction Service), Sprache Deutsch/Englisch, weitere Edge-Stimmen.

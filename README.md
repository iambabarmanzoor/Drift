# Drift

A light, premium home screen for Android car head units, built for 1280×720 screens.

Designed and built by **Babar Manzoor**. © 2026 Babar Manzoor. All rights reserved.

## What it does

- **Music card:** song, artist, cover and progress from your music player, with big previous / play-pause / next buttons. Works best with players that share full song details, such as VLC or Poweramp.
- **Drift Music:** a built-in player for your USB stick and the unit's storage. Search, favourites and playlists you can name and edit, a spinning record when a song has no cover, and it picks up where you left off when the car starts. Search and typing work only when parked.
- **Clock and date.**
- **Shortcuts:** 3 pages of 4 apps.
- **Speed:** read from the unit's GPS.
- **Appearance:** 7 accent colours, music card on the left or right, road motion that follows your speed and a moving glow on the dock.
- **Recent apps button.**
- **Updates:** installs over the internet, so no USB stick is needed after the first install.

## Install

1. Download `Drift.apk` from the latest [release](../../releases/latest).
2. Copy it to a USB stick and open it on the head unit.
3. Press Home and choose **Drift**, then **Always**.
4. Open **Settings → Permissions** in Drift and switch everything on.

Requires Android 6.0 or newer and a landscape screen. It's designed for 1280×720 and scales to other sizes.

## Safety of updates

- **Signed builds only.** Every release is signed with the author's private key, which is never published. Android refuses to install an update that isn't signed with the same key as the Drift already on the unit. Nobody else can push code to devices running Drift, even if they copy or change this code.
- **Fingerprint check.** Drift checks the downloaded file against its published fingerprint (SHA-256) before installing.
- **You confirm every update.** Android always shows its install screen first.

## Building

Source is in `src/`. `tools/build.sh` compiles with ECJ and dx, packages with `tools/build.py` (no Gradle or aapt needed), and signs the APK. The signing key isn't part of this repository.

## Licence

No licence is granted. The code is published to read, not to copy, modify or redistribute. Fonts are Inter and Orbitron, under the SIL Open Font License.

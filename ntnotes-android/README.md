# NT Notes for Android

Native Android app (Kotlin + Jetpack Compose) for the same notes as the desktop app.
Same Firebase project, same data: `users/{uid}/notes/{noteId}` in Realtime Database, newest `updatedAt` wins,
deletes are tombstones. Sign in with email/password or Google; the same account shows the same notes on every device.

## One-time Firebase setup (about 5 minutes)

1. Firebase console > project **ntnotes-58411** > Project settings > **Add app > Android**.
   * Package name: `app.ntnotes.android`
   * Debug signing certificate SHA-1 (this is the key committed in `app/ntnotes.jks`):
     `2A:33:AF:48:8F:D6:92:EE:BC:F9:9B:21:3A:53:A0:6E:67:CE:F6:09`
2. Authentication > Sign-in method: Email/Password and **Google** must both be enabled
   (they already are if the desktop app works).
3. **Download `google-services.json` after steps 1 and 2** and save it as `app/google-services.json`
   (it must contain an `oauth_client` entry of type 3; that is what Google sign-in uses).
4. Realtime Database rules: no change. The app writes exactly the seven fields the desktop rules allow
   (`id, title, body, createdAt, updatedAt, deleted, deviceId`).

## Build the APK

**GitHub (no Android Studio needed):** create a new repository, upload this whole folder (including
`app/google-services.json`), open the **Actions** tab > *Build NT Notes APK* > *Run workflow*.
Download `ntnotes-apk` from the finished run, unzip, and install `app-release.apk` on the phone
(allow "install unknown apps" for your browser/file manager).
If you do not want to commit `google-services.json`, paste its contents into a repository secret named
`GOOGLE_SERVICES_JSON` instead.

**Android Studio:** open this folder, let it sync, Build > Build APK(s), or `./gradlew assembleRelease`.
Output: `app/build/outputs/apk/release/app-release.apk`.

## Why it is fast

* The UI only ever reads the local SQLite database (WAL, indexed, list rows carry a 300-character preview,
  never whole notes), so startup and scrolling never wait for the network. Search is a single indexed query.
* Typing stays in Compose state; it is written to SQLite 0.6 s after you stop (and immediately when you leave
  the note or the app), then uploaded in the background.
* Firebase disk persistence is on, so after the first sync only changed notes are transferred, over one live
  websocket. Remote changes appear in the list (and in an open note you have not edited) within moments.
* Release build is R8-minified and ships Compose's baseline profiles (via `profileinstaller`).

## Sync rules (same as the desktop app)

* Newest `updatedAt` wins, per note. An older local edit never overwrites a newer remote note
  (uploads are Firebase transactions = compare-and-swap).
* Every edit is queued in SQLite first (`dirty` flag), so offline edits survive restarts and upload when
  the connection returns; failures retry with 1 s ... 60 s backoff.
* After sign-in the app merges the server's notes, then uploads local notes that are missing or newer.
* Status dot: Synced / Syncing / Offline.

## Source layout

`core/Rules.kt` pure sync rules | `data/NoteStore.kt` SQLite | `sync/SyncEngine.kt` Firebase sync |
`auth/AuthRepo.kt` email + Google | `Graph.kt` session wiring | `ui/` Compose screens.

## Notes

* `app/ntnotes.jks` (password `ntnotes-android`) is committed on purpose so the SHA-1 never changes. Fine for
  personal use; make a private key before publishing on Google Play.
* Changing the package name: edit `ntnotes.applicationId` in `gradle.properties` and register that name in Firebase.

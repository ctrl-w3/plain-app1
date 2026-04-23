# PlainApp + Cloudflare Tunnel — Implementation Handover

This document is a complete record of what the user asked for and exactly what was built, so the next agent (or the user porting this to another app) can pick up without re-discovering anything.

---

## 1. What the user wanted

- App: **PlainApp** (Android, Kotlin, Gradle, Compose).
- Goal: Make the phone's built-in web console reachable from the internet through their own domain (`shakti.buzz`, registered at GoDaddy), with **no extra apps installed on the phone** — no Termux, no separate cloudflared installer.
- Constraint: Everything must be **embedded in the APK**.
- Accepted Android caveats: foreground notification (Android requires it for any 24/7 service) and the user must turn off battery optimization on their phone.
- Later asks (in order):
  1. Open up CORS so any origin / any browser worldwide can talk to the phone via the public domain.
  2. Add a **detailed log** of *everything* the tunnel does so failures can be diagnosed (permissions, network, DNS, cloudflared output, etc.).
  3. Add a **Copy button** to copy the entire log to clipboard.
- Build environment: **GitHub Actions** (Gradle 9.4.1, AGP 9, JDK 17). User has no Android SDK locally; Replit also has no Android SDK. All "compile testing" was done by the user pushing to GitHub and reading CI logs.

---

## 2. The approach (high level)

1. **Bundle the official `cloudflared` binary** inside the APK as a native library (`libcloudflared.so`) per ABI. This is the only way to ship an executable on modern Android — files inside `nativeLibraryDir` are extracted with execute permission; binaries placed anywhere else cannot be `ProcessBuilder`-exec'd on Android 10+.
2. **Download cloudflared at build time** with a Gradle task (`downloadCloudflared`) that pulls `cloudflared-linux-arm64` and `cloudflared-linux-arm` from GitHub releases and renames them to `libcloudflared.so` under `build/generated/cloudflared/jniLibs/<abi>/`.
3. Configure AGP to package those `.so` files with `useLegacyPackaging = true` and `extractNativeLibs="true"` in the manifest, so they end up on disk (executable) rather than memory-mapped from the APK.
4. Add a **foreground service** (`CloudflareTunnelService`) that launches the binary with `ProcessBuilder`, streams its stdout into a custom logger, and restarts it with exponential backoff if it dies.
5. Add a **Compose settings page** (`CloudflareTunnelPage`) — token field, hostname field, enable/auto-start switches, status line, "Save & Apply", "Open dashboard", "Open my public URL", and "View detailed log".
6. Add a **log viewer page** (`CloudflareTunnelLogPage`) with auto-refresh, monospaced text, and "Copy full log" + "Clear" buttons.
7. Add **pre-flight network checks** (`TunnelPreflight`) and a **failure-line classifier** (`TunnelDiagnostics`) so the log explains *why* a connection fails in plain English (DNS not pointing at Cloudflare, port 7844 blocked, origin server off, tunnel token wrong, no Public Hostname rule, etc.).
8. Open up CORS in Ktor (`HttpModule.kt`) so any origin can reach the web server — needed because the public hostname is a different origin than the local LAN address.

---

## 3. Files added / changed

### Added

| Path | Purpose |
|---|---|
| `app/src/main/java/com/ismartcoding/plain/services/CloudflareTunnelService.kt` | Foreground service that runs the cloudflared process, streams its output, retries with backoff, posts notification, classifies failures. |
| `app/src/main/java/com/ismartcoding/plain/services/CloudflareTunnelManager.kt` | Tiny start/stop/restart helper used from UI and auto-start hook. |
| `app/src/main/java/com/ismartcoding/plain/services/TunnelLogger.kt` | Append-only log file at `filesDir/cloudflared/tunnel.log`, auto-trimmed at 512 KB. Has `i/w/e/d` levels, device snapshot, `read(maxLines)`, and `readAll()`. Mirrors to logcat via `LogCat`. |
| `app/src/main/java/com/ismartcoding/plain/services/TunnelPreflight.kt` | Runs DNS lookups, TCP connects to `region1.v2.argotunnel.com:7844` / `api.cloudflare.com:443`, hostname-points-to-Cloudflare check, public HTTPS HEAD probe (reads `Cf-Ray`/status), local origin reachability check on `127.0.0.1:8080`. All results logged via `TunnelLogger`. |
| `app/src/main/java/com/ismartcoding/plain/services/TunnelDiagnostics.kt` | Pattern-matches cloudflared stdout lines and emits plain-English explanations for known failure modes (bad token, no ingress rule, port 7844 blocked, origin refused, TLS, panic, etc.). |
| `app/src/main/java/com/ismartcoding/plain/ui/page/web/CloudflareTunnelPage.kt` | Settings screen. |
| `app/src/main/java/com/ismartcoding/plain/ui/page/web/CloudflareTunnelLogPage.kt` | Log viewer with auto-refresh, "Copy full log", "Clear". Uses `Toast` to confirm copy with character count. |
| `app/src/main/res/values/strings_cloudflare.xml` | All UI strings used by the two pages and the service notification. |

### Modified

| Path | Change |
|---|---|
| `app/build.gradle.kts` | Added `import java.io.File` / `java.io.FileInputStream` / `java.net.URI` / `java.util.Properties` / Kotlin imports at top. Added `ndk { abiFilters += listOf("arm64-v8a","armeabi-v7a") }`. Added `packaging { jniLibs { useLegacyPackaging = true ; keepDebugSymbols += "**/*.so" } }`. Added `sourceSets.main.jniLibs.srcDir(file("${project.layout.buildDirectory.get().asFile}/generated/cloudflared/jniLibs"))`. Added `downloadCloudflared` task and wired it via `androidComponents.onVariants { ... }` to run before `merge*JniLibFolders` and `merge*NativeLibs`. |
| `app/src/main/AndroidManifest.xml` | Added `android:extractNativeLibs="true"` on `<application>`, declared `<service android:name=".services.CloudflareTunnelService" android:foregroundServiceType="specialUse|dataSync" android:exported="false">` with `property` for `specialUse` subtype, and added the start/stop broadcast actions. |
| `app/src/main/java/com/ismartcoding/plain/Constants.kt` | Added `ACTION_START_CLOUDFLARE_TUNNEL` and `ACTION_STOP_CLOUDFLARE_TUNNEL`. |
| `app/src/main/java/com/ismartcoding/plain/preferences/Preferences.kt` | Appended four new preferences: `CloudflareTunnelTokenPreference` (string), `CloudflareTunnelEnabledPreference` (bool), `CloudflareTunnelHostnamePreference` (string), `CloudflareTunnelAutoStartPreference` (bool, default true). |
| `app/src/main/java/com/ismartcoding/plain/ui/nav/Routing.kt` | Added `object CloudflareTunnel` and `object CloudflareTunnelLog`. |
| `app/src/main/java/com/ismartcoding/plain/ui/page/MainNavGraph.kt` | Added `composable<Routing.CloudflareTunnel> { CloudflareTunnelPage(navController) }` and the same for the log page. |
| `app/src/main/java/com/ismartcoding/plain/ui/page/web/WebSettingsPage.kt` | Added a list item that navigates to `Routing.CloudflareTunnel`. |
| `app/src/main/java/com/ismartcoding/plain/services/HttpServerStartHelper.kt` | After web server starts successfully, if `CloudflareTunnelEnabledPreference` && `CloudflareTunnelAutoStartPreference` are both true, call `CloudflareTunnelManager.start(service)`. |
| `app/src/main/java/com/ismartcoding/plain/web/HttpModule.kt` | Replaced the restrictive `allowHost("...")` block with `anyHost()` + `allowNonSimpleContentTypes` + `allowHeadersPrefixed("c-")` + the standard methods. **Did NOT set** `allowCredentials = true` — Ktor 3 / browsers reject the wildcard origin combined with credentials. PlainApp's auth uses its own `c-*` headers, not cookies, so this is correct. |

---

## 4. Critical gotchas (the bumps the user actually hit)

These all caused real GitHub Actions failures during this session — call them out to the next agent.

1. **Gradle 9 Kotlin DSL doesn't auto-import `java.io.*` / `java.net.*` inside `doLast` blocks.**
   Add explicit `import java.io.File`, `import java.net.URI` at the top of `build.gradle.kts`. Don't use `java.io.File(...)` qualified syntax — it fails to resolve.

2. **`URL(string)` constructor is deprecated in JDK 17+.**
   Use `URI(string).toURL()` instead.

3. **AGP 9 forbids `Provider` types in `jniLibs.srcDir(...)`.**
   Error message: *"You cannot add Provider instances to the Android SourceSet API."* Pass a plain `File` from `file("${project.layout.buildDirectory.get().asFile}/...")` instead of `layout.buildDirectory.dir(...)` (which returns a `Provider<Directory>`).

4. **Gradle 9 configuration cache rejects script-object references in `doLast` bodies.**
   Error: *"cannot serialize Gradle script object references"*. Move all maps/constants used by the task **inside** the `tasks.register("...") { ... }` block as locals, then capture them into the `doLast`. Don't reference top-level `val` declarations from `doLast`.

5. **`PFilledButton` requires `onClick` as a named/positional argument, NOT a trailing lambda.**
   Signature is `(text, onClick, modifier, icon, type, buttonSize, isLoading, enabled)`. Trailing-lambda form attaches the lambda to `enabled: Boolean` and the compiler errors with *"Argument type mismatch: actual type is '() -> Job', but 'Boolean' was expected"*. Always write `PFilledButton(text = ..., onClick = { ... })`.

6. **`PTextField` requires `readOnly` as the FIRST parameter** in this codebase. Easy to miss.

7. **`extractNativeLibs="true"` is needed to `ProcessBuilder`-exec a bundled binary** on Android 10+. AGP will warn that this attribute "should not be specified in the source manifest" — that warning is harmless; ignore it. Without `extractNativeLibs=true`, the `.so` is memory-mapped from the APK and you get `EACCES` on exec.

8. **`useLegacyPackaging = true`** in `packaging.jniLibs` is needed alongside `extractNativeLibs=true`, otherwise AGP still compresses libs in the APK.

9. **Foreground service type:** Android 14+ requires a declared type. We try `SPECIAL_USE` first (with `<property name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" value="cloudflare-tunnel"/>` in the manifest) and fall back to `DATA_SYNC`, then to plain `startForeground`. Some OEMs reject `SPECIAL_USE` so the fallback chain matters.

10. **CORS + `allowCredentials = true` is incompatible with `anyHost()`** per the browser CORS spec. If you want any-origin access, drop `allowCredentials`.

---

## 5. End-to-end flow (so the next agent understands the runtime)

1. User opens **Web Console settings → Cloudflare Tunnel**.
2. Pastes their tunnel token (from Cloudflare Zero Trust → Networks → Tunnels → Create tunnel) into the token field, types `phone.shakti.buzz` (or whatever subdomain) into the hostname field, toggles Enable, taps **Save & Apply**.
3. `CloudflareTunnelManager.start(context)` sends an Intent to start `CloudflareTunnelService`.
4. Service does:
   - `TunnelLogger.init(this)` and writes a device snapshot (manufacturer, model, SDK, ABIs, app version, every file in `nativeLibraryDir` with size + exec flag).
   - Logs notification permission + battery-optimization status + active network transport (wifi/cellular/etc.).
   - Reads token from prefs, refuses to launch if empty.
   - Calls `TunnelPreflight.run(context)` which logs DNS / TCP / hostname-points-to-CF / HTTPS HEAD-with-Cf-Ray / local origin reachability.
   - `locateBinary()` looks in `applicationInfo.nativeLibraryDir` for `libcloudflared.so`, logs every candidate path with size + exec flag.
   - Builds a `ProcessBuilder` command: `cloudflared tunnel --no-autoupdate --edge-ip-version auto --protocol http2 --loglevel debug --transport-loglevel debug run --token <hidden>`.
   - Starts the process, streams stdout/stderr (merged) line-by-line into `TunnelLogger.d("cloudflared", ...)`.
   - Each line is also passed through `TunnelDiagnostics.classify(line)` — if it matches a known failure pattern, an `ERROR` line `DIAGNOSIS → <plain English>` is appended and `lastError` is updated.
   - When a line containing "Registered tunnel connection" appears, status flips to `RUNNING` and a green-marker line is logged.
   - Process exit triggers `runWithRetry` to wait, then re-launch with exponential backoff (2s → 60s cap).
5. **Log viewer** (Settings → Cloudflare Tunnel → "View detailed log") auto-reads `tunnel.log` every 2s, shows last 500 lines monospaced, has a **Copy full log** button (calls `TunnelLogger.readAll()` so the *entire* file is copied, not the truncated view) and a **Clear** button. Toast confirms copy with character count.

---

## 6. What the user must do outside the app (one-time)

- GoDaddy → DNS for `shakti.buzz` → change nameservers to the two Cloudflare nameservers shown when adding the domain to Cloudflare. Wait 5–60 min.
- Cloudflare → Zero Trust → Networks → Tunnels → Create tunnel → copy the token (long string starting with `eyJ...`).
- In the same tunnel: Public Hostname → Add → Subdomain `phone`, Domain `shakti.buzz`, Service `HTTP` `localhost:8080` (or whichever port the PlainApp Web Console is on).
- On phone: install the new APK, paste token + hostname, enable, **disable battery optimization** for PlainApp, and on Xiaomi/Realme/Vivo/Oppo also enable **Autostart**.

---

## 7. How the user builds

`.github/workflows/build-apk.yml` (already existed in repo) runs `./gradlew :app:assembleGithubDebug` on push and uploads the APK as a build artifact. The user has no local Android SDK — every code change is verified by pushing and reading the CI log. There is no Replit-hosted server for this project; the repl's "Status Page" workflow just shows a static info page.

---

## 8. Status as of the last commit

User confirmed: **"I have checked it beautifully working"** — the feature is complete and shipping in the user's APK.

Last commit: `34e2886` — "Improve cloudflared tunnel logging and file copying functionality".

Remaining warnings in CI (all non-blocking):
- `srcDir` deprecation (use `directories` mutable set) — cosmetic.
- `buildDir` getter deprecation — cosmetic.
- AGP warning about `extractNativeLibs` in source manifest — required for our use case, ignore.
- `kotlin.disallowKotlinSourceSets=false` experimental warning — pre-existing in repo, not from this work.

---

## 9. To replicate this in another Android app

Minimum recipe:

1. Drop `CloudflareTunnelService.kt`, `CloudflareTunnelManager.kt`, `TunnelLogger.kt`, `TunnelPreflight.kt`, `TunnelDiagnostics.kt`, the two Compose pages, and `strings_cloudflare.xml` into the project (adjust package names + replace `MainApp.instance` / `LogCat` / `NotificationHelper` / `coIO` / preference helpers / `PFilledButton`+`PTextField`+`PSwitch`+`PScaffold` etc. with the equivalents in the target codebase).
2. Add the `downloadCloudflared` task + `jniLibs.srcDir` + `useLegacyPackaging` block + `extractNativeLibs="true"` manifest attribute. Restrict ABIs to `arm64-v8a` + `armeabi-v7a` (cloudflared has no x86 Linux build that runs on Android x86 emulators).
3. Declare the foreground service in the manifest with type `specialUse|dataSync` and the matching `<property>` element for the special-use subtype.
4. Hook auto-start wherever the target app starts its own web server.
5. Open CORS on the target app's HTTP server to `anyHost()` (without `allowCredentials`) if external browsers need to talk to it.
6. Ship.

The four most painful gotchas — Gradle-9 `Provider` rejection, configuration-cache `doLast` capture rules, `URL` deprecation, and `PFilledButton` trailing-lambda mistake — are documented in section 4 above. Hit those right the first time and the build is clean.

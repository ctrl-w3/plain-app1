# PlainApp

Open-source Android (Kotlin) app that turns your phone into a self-hosted web console. Built with Gradle; the project's output is an APK installed on a phone — there is no Replit-hosted web server. The "Status Page" workflow on this Repl just shows a static info page about the project.

## Build

```
./build-apk.sh
```

A GitHub Actions workflow at `.github/workflows/build-apk.yml` auto-builds a debug APK on every push.

## Cloudflare Tunnel (built into the app)

Recently added so the web console can be reached from anywhere on the internet through your own domain (e.g. `shakti.buzz`), with **no extra apps installed** on the phone.

Files involved:
- `app/src/main/java/com/ismartcoding/plain/services/CloudflareTunnelService.kt` — foreground service that runs the bundled `cloudflared` binary.
- `app/src/main/java/com/ismartcoding/plain/services/CloudflareTunnelManager.kt` — start/stop helper.
- `app/src/main/java/com/ismartcoding/plain/ui/page/web/CloudflareTunnelPage.kt` — settings UI (Web Console → Cloudflare Tunnel).
- `app/src/main/res/values/strings_cloudflare.xml` — UI strings.
- Preferences in `Preferences.kt`: `CloudflareTunnelTokenPreference`, `CloudflareTunnelEnabledPreference`, `CloudflareTunnelHostnamePreference`, `CloudflareTunnelAutoStartPreference`.
- `app/build.gradle.kts` — `downloadCloudflared` Gradle task downloads the official `cloudflared` Linux ARM/ARM64 binaries into `build/generated/cloudflared/jniLibs/<abi>/libcloudflared.so` so they ship inside the APK as native libraries (the only Android-allowed way to ship an executable).
- `AndroidManifest.xml` — `extractNativeLibs="true"` (required to exec the binary at runtime), `CloudflareTunnelService` declared, broadcast actions wired.
- `HttpModule.kt` — CORS opened up (`anyHost()`) so external origins can talk to the server through the public domain.

### How a user uses it
1. Cloudflare account (free) → Zero Trust → Networks → Tunnels → Create tunnel → copy token.
2. Public Hostname → subdomain `phone.shakti.buzz` → service `http://localhost:8080`.
3. In PlainApp: Web Console settings → **Cloudflare Tunnel** → paste token → enable.
4. Disable battery optimization for PlainApp.

### Caveats
Phone must stay on; aggressive OEM battery killers (Xiaomi/Realme/Vivo/Oppo) may suspend the tunnel — autostart whitelist + battery optimization off is required.

#!/usr/bin/env python3
import http.server
import socketserver
import os

PORT = int(os.environ.get("PORT", 5000))

HTML = """<!doctype html>
<html><head><meta charset="utf-8"><title>PlainApp - Android Project</title>
<style>
body{font-family:system-ui,sans-serif;max-width:760px;margin:40px auto;padding:0 20px;line-height:1.6;color:#222}
code{background:#f4f4f4;padding:2px 6px;border-radius:4px}
.box{background:#f9fafb;border:1px solid #e5e7eb;border-radius:10px;padding:20px;margin:20px 0}
h1{margin-top:0}
</style></head><body>
<h1>PlainApp - Android Project</h1>
<p>This is an <b>Android (Kotlin)</b> project. The output is an APK installed on a phone, so there is no web server to host on Replit.</p>
<div class="box">
<h3>Build the APK</h3>
<p>Use the included script:</p>
<pre><code>./build-apk.sh</code></pre>
<p>Or use the GitHub Actions workflow that auto-builds debug APKs on push.</p>
</div>
<div class="box">
<h3>New: Cloudflare Tunnel built into the app</h3>
<p>Open the app -> Web Console settings -> <b>Cloudflare Tunnel</b>, paste your tunnel token, and your phone's web console becomes reachable from anywhere over your custom domain (for example <code>shakti.buzz</code>).</p>
</div>
</body></html>"""

class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(HTML.encode("utf-8"))
    def log_message(self, *a, **k): pass

with socketserver.TCPServer(("0.0.0.0", PORT), H) as httpd:
    print(f"Status page on http://0.0.0.0:{PORT}")
    httpd.serve_forever()

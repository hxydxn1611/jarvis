"""
JARVIS Desktop Workshop Bridge
Connects your mobile Jarvis on Samsung Galaxy M34 to this PC workstation / Antigravity workspace.
Supports local LAN access and Worldwide Cloudflare Tunneling.
"""

import sys
import os
import subprocess
import json
import urllib.request
import threading
import re
from http.server import HTTPServer, BaseHTTPRequestHandler

PORT = 8888
CLOUDFLARED_EXE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "cloudflared.exe")
CLOUDFLARED_DOWNLOAD_URL = "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe"

class JarvisBridgeHandler(BaseHTTPRequestHandler):

    def _send_cors_headers(self):
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type')

    def do_OPTIONS(self):
        self.send_response(200)
        self._send_cors_headers()
        self.end_headers()

    def do_GET(self):
        self.send_response(200)
        self.send_header('Content-type', 'application/json')
        self._send_cors_headers()
        self.end_headers()
        response = {
            "status": "ONLINE",
            "station": "Desktop Workshop // Antigravity Core",
            "message": "Standing by for remote commands, Sir."
        }
        self.wfile.write(json.dumps(response).encode())

    def do_POST(self):
        content_length = int(self.headers.get('Content-Length', 0))
        post_data = self.rfile.read(content_length)
        
        try:
            payload = json.loads(post_data.decode('utf-8'))
            command = payload.get("command", "")
            
            print(f"\n[JARVIS REMOTE DIRECTIVE RECEIVED]: {command}")
            
            # Execute desktop shell / project command
            result = subprocess.run(
                ["powershell", "-Command", command],
                capture_output=True,
                text=True,
                timeout=60
            )
            
            response = {
                "success": result.returncode == 0,
                "stdout": result.stdout.strip(),
                "stderr": result.stderr.strip(),
                "summary": f"Executed on PC workstation: {command}"
            }
        except Exception as e:
            response = {
                "success": False,
                "error": str(e)
            }

        self.send_response(200)
        self.send_header('Content-type', 'application/json')
        self._send_cors_headers()
        self.end_headers()
        self.wfile.write(json.dumps(response).encode())

def ensure_cloudflared():
    if not os.path.exists(CLOUDFLARED_EXE):
        print("[SYS] Downloading Cloudflare Tunnel binary (cloudflared.exe)...")
        urllib.request.urlretrieve(CLOUDFLARED_DOWNLOAD_URL, CLOUDFLARED_EXE)
        print("[SYS] Cloudflare Tunnel binary ready.")

def start_tunnel():
    ensure_cloudflared()
    print("[SYS] Launching Cloudflare Tunnel for worldwide cellular 5G access...")
    proc = subprocess.Popen(
        [CLOUDFLARED_EXE, "tunnel", "--url", f"http://127.0.0.1:{PORT}"],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1
    )
    for line in iter(proc.stdout.readline, ''):
        match = re.search(r'https://[a-zA-Z0-9-]+\.trycloudflare\.com', line)
        if match:
            tunnel_url = match.group(0)
            print("\n" + "=" * 70)
            print("🚀 J.A.R.V.I.S. WORLDWIDE SECURE TUNNEL ACTIVE!")
            print(f"Public URL: {tunnel_url}")
            print("Paste this URL into your Jarvis Android App Settings (Bridge URL)")
            print("to control your PC from anywhere in the world on mobile data!")
            print("=" * 70 + "\n")
            break

def run_server(enable_tunnel=False):
    if enable_tunnel:
        tunnel_thread = threading.Thread(target=start_tunnel, daemon=True)
        tunnel_thread.start()

    server_address = ('0.0.0.0', PORT)
    httpd = HTTPServer(server_address, JarvisBridgeHandler)
    print("=" * 60)
    print(f"J.A.R.V.I.S. DESKTOP WORKSHOP BRIDGE ONLINE ON PORT {PORT}")
    print("Local IP: http://192.168.1.4:8888")
    print("Your Samsung Galaxy M34 can now dispatch project tasks to this PC.")
    print("=" * 60)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n[SYS] Desktop Bridge shut down.")

if __name__ == "__main__":
    use_tunnel = "--tunnel" in sys.argv
    run_server(enable_tunnel=use_tunnel)


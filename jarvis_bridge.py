"""
JARVIS Desktop Workshop Bridge
Connects your mobile Jarvis on Samsung Galaxy M34 to this PC workstation / Antigravity workspace.
"""

import sys
import subprocess
import json
from http.server import HTTPServer, BaseHTTPRequestHandler

PORT = 8888

class JarvisBridgeHandler(BaseHTTPRequestHandler):

    def do_GET(self):
        self.send_response(200)
        self.send_header('Content-type', 'application/json')
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
                "stdout": result.stdout,
                "stderr": result.stderr,
                "summary": f"Executed on PC workstation: {command}"
            }
        except Exception as e:
            response = {
                "success": False,
                "error": str(e)
            }

        self.send_response(200)
        self.send_header('Content-type', 'application/json')
        self.end_headers()
        self.wfile.write(json.dumps(response).encode())

def run_server():
    server_address = ('0.0.0.0', PORT)
    httpd = HTTPServer(server_address, JarvisBridgeHandler)
    print("=" * 60)
    print(f"J.A.R.V.I.S. DESKTOP WORKSHOP BRIDGE ONLINE ON PORT {PORT}")
    print("Your Samsung Galaxy M34 can now dispatch project tasks to this PC.")
    print("=" * 60)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n[SYS] Desktop Bridge shut down.")

if __name__ == "__main__":
    run_server()


import http.server
import socketserver
import urllib.parse
import urllib.request
import json
import base64
import hashlib
import os
import webbrowser
import sys

PORT = 8080
REDIRECT_URI = f"http://localhost:{PORT}/callback"

# Select region
region = sys.argv[1] if len(sys.argv) > 1 else "us"
mcp_host = "https://mcpeu.coros.com" if region == "eu" else "https://mcpus.coros.com"

print(f"=== COROS MCP OAuth Local Test ({region.upper()}) ===")
print(f"Target MCP Server: {mcp_host}")

# 1. Dynamic Client Registration
print("\n[1/3] Registering dynamic client with COROS...")
reg_data = json.dumps({
    "client_name": "Laps Local OAuth Test",
    "redirect_uris": [REDIRECT_URI],
    "grant_types": ["authorization_code", "refresh_token"],
    "response_types": ["code"],
    "token_endpoint_auth_method": "none",
    "scope": "mcp.tools offline_access"
}).encode("utf-8")

reg_req = urllib.request.Request(
    f"{mcp_host}/connect/register",
    data=reg_data,
    headers={"Content-Type": "application/json", "User-Agent": "Mozilla/5.0"}
)

try:
    reg_resp = urllib.request.urlopen(reg_req)
    client_info = json.loads(reg_resp.read().decode("utf-8"))
    client_id = client_info["client_id"]
    print(f"SUCCESS: Registered client_id = {client_id}")
except Exception as e:
    print(f"Registration failed: {e}")
    sys.exit(1)

# 2. PKCE generation
verifier = base64.urlsafe_b64encode(os.urandom(32)).rstrip(b"=").decode("utf-8")
challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode("utf-8")).digest()).rstrip(b"=").decode("utf-8")

params = {
    "response_type": "code",
    "client_id": client_id,
    "redirect_uri": REDIRECT_URI,
    "scope": "mcp.tools offline_access",
    "code_challenge": challenge,
    "code_challenge_method": "S256",
    "state": "local_test_state"
}

auth_url = f"{mcp_host}/oauth2/authorize?" + urllib.parse.urlencode(params)
print("\n[2/3] Opening browser for official COROS authorization...")
print(f"URL: {auth_url}\n")

auth_code = None

class CallbackHandler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        global auth_code
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path == "/callback":
            qs = urllib.parse.parse_qs(parsed.query)
            if "code" in qs:
                auth_code = qs["code"][0]
                self.send_response(200)
                self.send_header("Content-type", "text/html")
                self.end_headers()
                self.wfile.write(b"<h1>Authorization Successful!</h1><p>You can return to the terminal.</p>")
            else:
                self.send_response(400)
                self.end_headers()
                self.wfile.write(b"<h1>Error</h1><p>No code returned.</p>")
    def log_message(self, format, *args):
        pass

# Start local server and open browser
with socketserver.TCPServer(("", PORT), CallbackHandler) as httpd:
    webbrowser.open(auth_url)
    print("Waiting for callback on http://localhost:8080/callback ...")
    while auth_code is None:
        httpd.handle_request()

print(f"\n[3/3] Received authorization code: {auth_code[:15]}...")
print("Exchanging authorization code for Bearer Token...")

token_data = urllib.parse.urlencode({
    "grant_type": "authorization_code",
    "client_id": client_id,
    "code": auth_code,
    "redirect_uri": REDIRECT_URI,
    "code_verifier": verifier
}).encode("utf-8")

token_req = urllib.request.Request(
    f"{mcp_host}/oauth2/token",
    data=token_data,
    headers={"Content-Type": "application/x-www-form-urlencoded", "User-Agent": "Mozilla/5.0"}
)

try:
    token_resp = urllib.request.urlopen(token_req)
    tokens = json.loads(token_resp.read().decode("utf-8"))
    print("\nSUCCESS! Tokens received:")
    print("Access Token:", tokens.get("access_token")[:25] + "...")
    print("Token Type:", tokens.get("token_type"))
    print("Expires In:", tokens.get("expires_in"))
    print("Refresh Token:", tokens.get("refresh_token")[:25] + "..." if tokens.get("refresh_token") else "None")
except urllib.error.HTTPError as e:
    print(f"Token exchange failed: HTTP {e.code}")
    print(e.read().decode("utf-8"))

#!/usr/bin/env python3
from http.server import HTTPServer, SimpleHTTPRequestHandler
import os

class CORSRequestHandler(SimpleHTTPRequestHandler):
    def end_headers(self):
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET')
        self.send_header('Cache-Control', 'no-store, no-cache, must-revalidate')
        return super().end_headers()

if __name__ == '__main__':
    os.chdir('/Users/chhay/Documents/titan-project/titan-edge-ai')
    server = HTTPServer(('localhost', 8096), CORSRequestHandler)
    print('=' * 80)
    print('🚀 Titan Edge AI Server Running')
    print('=' * 80)
    print(f'\n📡 Server: http://localhost:8096')
    print(f'🌐 Open in browser: http://localhost:8096/index.html')
    print(f'\n✅ Model files loaded:')
    print(f'   - model_weights.json')
    print(f'   - scaler_params.json')
    print(f'   - index.html')
    print(f'\nPress Ctrl+C to stop\n')
    server.serve_forever()

"""将已构建Vue页面代理到专用压测API，避免浏览器联调误写开发库。"""
from http.client import HTTPConnection
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit
import perf

DIST = perf.ROOT / 'frontend' / 'dist'


class Preview(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(DIST), **kwargs)

    def api(self):
        connection = HTTPConnection('127.0.0.1', 18080, timeout=30)
        data = self.rfile.read(int(self.headers.get('Content-Length', '0')))
        headers = {k:v for k,v in self.headers.items() if k.lower() not in ('host','connection','transfer-encoding')}
        try:
            connection.request(self.command, self.path, body=data, headers=headers)
            response = connection.getresponse()
            body = response.read()
            self.send_response(response.status)
            for key, value in response.getheaders():
                if key.lower() not in ('connection','transfer-encoding','content-length'):
                    self.send_header(key,value)
            self.send_header('Content-Length',str(len(body))); self.end_headers(); self.wfile.write(body)
        finally:
            connection.close()

    def do_GET(self):
        if self.path.startswith('/api/'):
            self.api()
        else:
            relative = urlsplit(self.path).path.lstrip('/')
            if not relative or not Path(relative).suffix:
                self.path = '/index.html'
            super().do_GET()

    def do_POST(self): self.api() if self.path.startswith('/api/') else self.send_error(404)
    def do_PUT(self): self.api() if self.path.startswith('/api/') else self.send_error(404)
    def log_message(self, format, *args): pass  # 不记录请求参数或身份材料。


if __name__=='__main__':
    perf.connect().close()
    if not (DIST/'index.html').exists(): raise RuntimeError('请先执行Vue构建')
    print('独立浏览器联调入口 http://127.0.0.1:15173', flush=True)
    ThreadingHTTPServer(('127.0.0.1',15173),Preview).serve_forever()

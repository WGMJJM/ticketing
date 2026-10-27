"""仅绑定本机的测试 SMTP 收件箱，不向外部发送邮件或输出验证码。"""
import argparse
from email import policy
from email.parser import BytesParser
import hashlib
import re
import socketserver
import environment as verification


class Handler(socketserver.StreamRequestHandler):
    def handle(self):
        self.wfile.write(b'220 localhost test mailbox\r\n')
        recipient = None
        while line := self.rfile.readline(65536):
            command = line.decode('ascii', errors='replace').strip()
            verb = command.split(' ', 1)[0].upper()
            if verb in ('EHLO', 'HELO'):
                self.wfile.write(b'250-localhost\r\n250 8BITMIME\r\n')
            elif verb == 'MAIL':
                recipient = None
                self.wfile.write(b'250 OK\r\n')
            elif verb == 'RCPT':
                match = re.search(r'<([^<>]+)>', command)
                recipient = match.group(1).lower() if match else None
                self.wfile.write(b'250 OK\r\n' if recipient else b'501 Invalid recipient\r\n')
            elif verb == 'DATA' and recipient:
                self.wfile.write(b'354 End with .\r\n')
                data = bytearray()
                while part := self.rfile.readline(65536):
                    if part == b'.\r\n':
                        break
                    data.extend(part[1:] if part.startswith(b'..') else part)
                message = BytesParser(policy=policy.default).parsebytes(data)
                body = message.get_body(preferencelist=('plain',)) if message.is_multipart() else message
                match = re.search(r'验证码为：([0-9]{6})', body.get_content())
                if not match:
                    self.wfile.write(b'550 Invalid test message\r\n')
                    continue
                inbox = verification.PRIVATE / 'mail'
                inbox.mkdir(exist_ok=True)
                name = hashlib.sha256(recipient.encode('utf-8')).hexdigest() + '.code'
                (inbox / name).write_text(match.group(1), encoding='ascii')
                self.wfile.write(b'250 Accepted\r\n')
            elif verb == 'QUIT':
                self.wfile.write(b'221 Bye\r\n')
                break
            else:
                self.wfile.write(b'250 OK\r\n')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--port', type=int, required=True)
    args = parser.parse_args()
    verification.connect().close()
    cfg = verification.config()
    if args.port != cfg.get('smtpPort') or not 1024 <= args.port <= 65535:
        raise RuntimeError('仅允许隔离功能验收配置的本机 SMTP 端口')
    with socketserver.ThreadingTCPServer(('127.0.0.1', args.port), Handler) as server:
        server.serve_forever()


if __name__ == '__main__':
    main()

// 联调用的邮箱服务器：SMTP(2525) 收信 + POP3(1110) 发信，进程内存存储，纯 TCP（客户端关掉 TLS 即可对接）。
import net from 'node:net';
import fs from 'node:fs';

const SMTP_PORT = 2525, POP_PORT = 1110;
const LOG = 'D:/dshproject/sms-recv/_test/mock-mail.log';
let store = [];      // { uid, raw }
let seq = 1;

const log = (s) => { const line = new Date().toLocaleTimeString('sv-SE') + ' ' + s; console.log(line); fs.appendFileSync(LOG, line + '\n'); };

function serve(port, onConn) { net.createServer(onConn).listen(port, '127.0.0.1', () => log('listening ' + port)); }

// ---- SMTP ----
serve(SMTP_PORT, (sock) => {
  let inData = false, buf = '', data = '', authState = 0;   // 0=无 1=已 AUTH LOGIN 2=已收用户名
  sock.write('220 mock-smtp ready\r\n');
  sock.on('data', (chunk) => {
    buf += chunk.toString('utf8');
    let idx;
    while ((idx = buf.indexOf('\r\n')) >= 0) {
      const line = buf.slice(0, idx); buf = buf.slice(idx + 2);
      if (inData) {
        if (line === '.') { inData = false; sock.write('250 OK queued\r\n'); store.push({ uid: 'uid' + (seq++), raw: data }); log('SMTP: 收到一封信，共 ' + store.length + ' 封'); data = ''; }
        else data += line + '\n';
        continue;
      }
      const cmd = line.trim();
      if (/^(EHLO|HELO)/i.test(cmd)) sock.write('250-mock\r\n250 AUTH LOGIN\r\n');
      else if (/^AUTH LOGIN/i.test(cmd)) { authState = 1; sock.write('334 VXNlcm5hbWU6\r\n'); }
      else if (authState === 1) { authState = 2; sock.write('334 UGFzc3dvcmQ6\r\n'); }
      else if (authState === 2) { authState = 0; sock.write('235 Authentication successful\r\n'); }
      else if (/^(MAIL FROM|RCPT TO)/i.test(cmd)) sock.write('250 OK\r\n');
      else if (/^DATA/i.test(cmd)) { inData = true; sock.write('354 End data with <CR><LF>.<CR><LF>\r\n'); }
      else if (/^QUIT/i.test(cmd)) { sock.write('221 Bye\r\n'); sock.end(); }
      else sock.write('250 OK\r\n');
    }
  });
});

// ---- POP3 ----
serve(POP_PORT, (sock) => {
  let buf = '', user = '', pass = '', deleted = new Set();
  sock.write('+OK mock-pop3 ready\r\n');
  sock.on('data', (chunk) => {
    buf += chunk.toString('utf8');
    let idx;
    while ((idx = buf.indexOf('\r\n')) >= 0) {
      const line = buf.slice(0, idx); buf = buf.slice(idx + 2);
      const [cmd, arg] = line.trim().split(/\s+/);
      const c = (cmd || '').toUpperCase();
      if (c === 'USER') { user = arg || ''; sock.write('+OK\r\n'); }
      else if (c === 'PASS') { pass = arg || ''; sock.write('+OK logged in\r\n'); }
      else if (c === 'STAT') { const live = store.filter((m) => !deleted.has(m.uid)); sock.write('+OK ' + live.length + ' ' + live.reduce((a, m) => a + m.raw.length, 0) + '\r\n'); }
      else if (c === 'UIDL') {
        const live = store.filter((m) => !deleted.has(m.uid));
        if (!arg) { sock.write('+OK\r\n'); live.forEach((m, i) => sock.write((i + 1) + ' ' + m.uid + '\r\n')); sock.write('.\r\n'); }
        else { const i = Number(arg) - 1; const m = live[i]; sock.write(m ? '+OK ' + arg + ' ' + m.uid + '\r\n' : '-ERR no such message\r\n'); }
      }
      else if (c === 'LIST') {
        const live = store.filter((m) => !deleted.has(m.uid));
        sock.write('+OK\r\n'); live.forEach((m, i) => sock.write((i + 1) + ' ' + m.raw.length + '\r\n')); sock.write('.\r\n');
      }
      else if (c === 'RETR') {
        const live = store.filter((m) => !deleted.has(m.uid));
        const m = live[Number(arg) - 1];
        if (!m) sock.write('-ERR no such message\r\n');
        else {
          sock.write('+OK ' + m.raw.length + ' octets\r\n');
          m.raw.split('\n').forEach((l) => sock.write(l.replace(/\r$/, '') + '\r\n'));
          sock.write('.\r\n');
          log('POP3: 取走 ' + m.uid);
        }
      }
      else if (c === 'DELE') { const live = store.filter((m) => !deleted.has(m.uid)); const m = live[Number(arg) - 1]; if (m) deleted.add(m.uid); sock.write('+OK\r\n'); }
      else if (c === 'QUIT') { store = store.filter((m) => !deleted.has(m.uid)); sock.write('+OK bye\r\n'); sock.end(); log('POP3: 会话结束，剩 ' + store.length + ' 封'); }
      else sock.write('+OK\r\n');
    }
  });
});

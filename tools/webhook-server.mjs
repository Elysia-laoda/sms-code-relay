// 开发期用的本机接收端：把手机 POST 上来的转发负载原样落盘（一行一条 JSON）。
// 配合 adb reverse tcp:8787 tcp:8787，手机里的 http://127.0.0.1:8787 就是这台 PC。
import http from 'node:http';
import fs from 'node:fs';

const LOG = 'D:/dshproject/sms-relay/_test/webhook.log';
const PORT = Number(process.env.PORT || 8787);

const srv = http.createServer((req, res) => {
  let body = '';
  req.on('data', (c) => { body += c; });
  req.on('end', () => {
    const rec = {
      at: new Date().toLocaleString('sv-SE'),
      method: req.method,
      url: req.url,
      token: req.headers['x-token'] || null,
      body
    };
    fs.appendFileSync(LOG, JSON.stringify(rec) + '\n');
    console.log(new Date().toLocaleTimeString('sv-SE') + '  ' + rec.method + ' ' + rec.url + '  ' + body.slice(0, 300));
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end('{"ok":true,"got":1}');
  });
});

srv.listen(PORT, '127.0.0.1', () => console.log('webhook receiver listening on 127.0.0.1:' + PORT + ' -> ' + LOG));

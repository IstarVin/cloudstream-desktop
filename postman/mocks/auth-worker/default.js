// Contract stand-in for server HTTP error handling; the actual Worker is tested in auth-worker/test.
const http = require('node:http');
http.createServer(async (req, res) => {
  let body = req.body;
  if (typeof body === 'string') body = JSON.parse(body);
  if (!body) { res.writeHead(400).end(JSON.stringify({ error: 'Missing JSON body' })); return; }
  res.setHeader('Content-Type', 'application/json');
  if (!body.serverId) { res.writeHead(400).end(JSON.stringify({ error: 'Invalid serverId' })); return; }
  if (req.url.endsWith('/login')) {
    if (body.username === 'unavailable') { res.writeHead(503).end(JSON.stringify({ error: 'Unavailable' })); return; }
    if (body.username === 'html-error') { res.setHeader('Content-Type', 'text/html'); res.writeHead(502).end('<html>Bad gateway</html>'); return; }
    if (body.password !== 'test-password-123') { res.writeHead(401).end(JSON.stringify({ error: 'Incorrect username or password' })); return; }
    res.end(JSON.stringify({ epoch: 1 })); return;
  }
  if (req.url.endsWith('/epoch')) { res.end(JSON.stringify({ epoch: 1 })); return; }
  res.writeHead(501).end(JSON.stringify({ error: 'Endpoint not modeled' }));
}).listen(process.env.PORT);

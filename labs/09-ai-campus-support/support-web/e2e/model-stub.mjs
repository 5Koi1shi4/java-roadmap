import http from 'node:http';

let completionsUnavailable = false;

const server = http.createServer((request, response) => {
  if (request.method === 'GET' && request.url === '/health') {
    response.writeHead(200, { 'Content-Type': 'text/plain; charset=UTF-8' });
    response.end('ok');
    return;
  }
  if (request.method === 'POST' && request.url === '/__control/fail') {
    completionsUnavailable = true;
    response.writeHead(204);
    response.end();
    return;
  }
  if (request.method === 'POST' && request.url === '/__control/recover') {
    completionsUnavailable = false;
    response.writeHead(204);
    response.end();
    return;
  }
  if (request.method === 'POST' && request.url === '/v1/chat/completions') {
    console.log('[model-stub] received POST /v1/chat/completions');
    let body = '';
    request.setEncoding('utf8');
    request.on('data', (chunk) => { body += chunk; });
    request.on('end', () => {
      if (body.includes('orderId') || /[0-9a-f]{8}-[0-9a-f-]{27}/i.test(body)) {
        console.log('[model-stub] rejected private identifier');
        response.writeHead(400, { 'Content-Type': 'application/json; charset=UTF-8' });
        response.end(JSON.stringify({ error: { message: 'private data rejected' } }));
        return;
      }
      if (completionsUnavailable) {
        console.log('[model-stub] returned controlled 503');
        response.writeHead(503, { 'Content-Type': 'application/json; charset=UTF-8' });
        response.end(JSON.stringify({ error: { message: 'controlled failure' } }));
        return;
      }
      response.writeHead(200, { 'Content-Type': 'application/json; charset=UTF-8' });
      console.log('[model-stub] returned 200');
      response.end(JSON.stringify({
        id: 'demo-answer', object: 'chat.completion', created: 1, model: 'demo-support-model',
        choices: [{ index: 0, finish_reason: 'stop', message: { role: 'assistant', content: '退款条件以公开规则为准。' } }],
        usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 }
      }));
    });
    return;
  }
  response.writeHead(404, { 'Content-Type': 'application/json; charset=UTF-8' });
  response.end(JSON.stringify({ error: { message: 'not found' } }));
});

server.listen(18089, '0.0.0.0');

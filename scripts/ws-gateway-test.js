const WebSocket = require('ws');
const server = new WebSocket.Server({ port: 8090, path: '/client-ws' });

console.log('WS test server listening ws://localhost:8090/client-ws');

server.on('connection', (ws, req) => {
  console.log('Client connected');
  ws.on('message', (msg) => {
    try {
      const json = JSON.parse(msg.toString());
      console.log('received from client:', json);
      if (json.type === 'identify') {
        console.log('client identified, will send lcu_request in 2s');
        setTimeout(() => {
          const id = 'test-' + Date.now();
          const reqMsg = { type: 'lcu_request', id, method: 'GET', path: '/lol-summoner/v1/current-summoner' };
          console.log('sending lcu_request', reqMsg);
          ws.send(JSON.stringify(reqMsg));
        }, 2000);
      } else if (json.type === 'lcu_response') {
        console.log('got lcu_response', json);
      }
    } catch (e) {
      console.error('error parsing client msg', e);
    }
  });

  ws.on('close', () => console.log('client disconnected'));
});

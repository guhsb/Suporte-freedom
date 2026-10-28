/**
 * Servidor de sinalização para o protótipo "Acesso Remoto Freedom".
 *
 * Papéis:
 *  - "device"     -> o app Android do celular da frota, se registra com um deviceId
 *  - "controller" -> a página web de controle (quem está vendo/controlando)
 *
 * Fluxo:
 *  1. device se conecta e manda {type:"register", role:"device", deviceId:"xxx"}
 *  2. controller se conecta e manda {type:"register", role:"controller", deviceId:"xxx"}
 *  3. a partir daí, o servidor só repassa mensagens entre os dois lados daquele deviceId:
 *     sdp-offer, sdp-answer, ice-candidate, control-mode, mic-mute, disconnect
 *
 * Não guarda nada em disco. Cada deviceId só pode ter 1 device e 1 controller por vez.
 */

const WebSocket = require("ws");

const PORT = process.env.PORT || 8080;
const wss = new WebSocket.Server({ port: PORT });

// deviceId -> { device: ws|null, controller: ws|null }
const rooms = new Map();

function getRoom(deviceId) {
  if (!rooms.has(deviceId)) {
    rooms.set(deviceId, { device: null, controller: null });
  }
  return rooms.get(deviceId);
}

function send(ws, obj) {
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(obj));
  }
}

wss.on("connection", (ws) => {
  ws._role = null;
  ws._deviceId = null;

  ws.on("message", (raw) => {
    let msg;
    try {
      msg = JSON.parse(raw);
    } catch (e) {
      return;
    }

    if (msg.type === "register") {
      const { role, deviceId } = msg;
      if (role !== "device" && role !== "controller") return;
      if (!deviceId) return;

      const room = getRoom(deviceId);

      // Se já tinha alguém com o mesmo papel, derruba a conexão antiga
      if (room[role]) {
        send(room[role], { type: "replaced" });
        room[role].close();
      }

      room[role] = ws;
      ws._role = role;
      ws._deviceId = deviceId;

      console.log(`[registro] ${role} conectado em ${deviceId}`);

      // Avisa o outro lado que o par está pronto (se já existir)
      const otherRole = role === "device" ? "controller" : "device";
      if (room[otherRole]) {
        send(ws, { type: "peer-ready" });
        send(room[otherRole], { type: "peer-ready" });
      }
      return;
    }

    // Qualquer outra mensagem é só repassada para o outro lado da sala
    const room = rooms.get(ws._deviceId);
    if (!room) return;
    const otherRole = ws._role === "device" ? "controller" : "device";
    const other = room[otherRole];

    if (
      [
        "sdp-offer",
        "sdp-answer",
        "ice-candidate",
        "control-mode",
        "mic-mute",
        "disconnect",
      ].includes(msg.type)
    ) {
      send(other, msg);
    }
  });

  ws.on("close", () => {
    if (!ws._deviceId) return;
    const room = rooms.get(ws._deviceId);
    if (!room) return;
    if (room[ws._role] === ws) {
      room[ws._role] = null;
      const otherRole = ws._role === "device" ? "controller" : "device";
      send(room[otherRole], { type: "peer-left" });
      console.log(`[saida] ${ws._role} saiu de ${ws._deviceId}`);
    }
  });
});

console.log(`Servidor de sinalização rodando na porta ${PORT}`);

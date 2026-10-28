const { app, BrowserWindow, session } = require("electron");
const path = require("path");

/**
 * Janela desktop que carrega a mesma página de controle (index.html).
 * O conteúdo é o mesmo do web-control/ — só muda o "invólucro":
 * aqui vira um .exe de verdade em vez de precisar abrir um navegador.
 */
function createWindow() {
  const win = new BrowserWindow({
    width: 1280,
    height: 800,
    title: "Freedom - Suporte Remoto",
    webPreferences: {
      // getUserMedia (microfone) e WebRTC funcionam normalmente aqui dentro,
      // o Electron já roda sobre Chromium.
      contextIsolation: true,
      nodeIntegration: false,
    },
  });

  win.loadFile(path.join(__dirname, "index.html"));
}

app.whenReady().then(() => {
  // Libera direto o pedido de microfone (quem usa este .exe já é da equipe
  // de suporte, não faz sentido ficar perguntando toda vez).
  session.defaultSession.setPermissionRequestHandler((_wc, permission, callback) => {
    callback(permission === "media");
  });

  createWindow();

  app.on("activate", () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") app.quit();
});

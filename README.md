# Acesso Remoto Freedom — protótipo

App separado do Headwind MDM para ver + controlar + falar com os celulares da
frota, no estilo AnyDesk.

```
web-control/         página web onde você vê a tela, alterna Visualizar/
                     Controlar e muta seu microfone — hospedável no
                     GitHub Pages (é estática)
electron-app/        o mesmo controle, empacotado como app desktop (gera
                     um .exe para Windows, além de versão Mac e Linux)
android-app/         esqueleto do app Android (Kotlin) que roda no celular:
                     captura tela, executa os gestos recebidos, manda áudio
supabase/schema.sql  tabelas opcionais de registro de aparelhos e log de
                     sessões, para rodar no SQL Editor do seu projeto Supabase
signaling-server/    versão antiga (Node.js + WebSocket) do pareamento,
                     mantida só como referência — não é mais necessária,
                     o Supabase Realtime faz esse papel agora
```

## Arquitetura atual

- **Sinalização:** canal Realtime do Supabase (broadcast + presence) — o
  celular e a página de controle se encontram lá, sem servidor seu rodando.
- **Vídeo/áudio/controle:** direto entre o celular e o navegador via WebRTC
  (P2P), só passando pelo TURN quando a rede exige.
- **TURN:** ainda precisa de algo rodando perto da internet — Supabase e
  GitHub não cobrem isso. Use coturn num VPS pequeno (pode ser o mesmo do
  Headwind) ou um serviço de TURN gratuito (ex: metered.ca).

## Login do admin + pareamento por QR

A tela de controle (web e .exe) agora pede login antes de mostrar qualquer
coisa. Como é um usuário só:

1. No painel do Supabase, vá em **Authentication > Users > Add user** e
   crie o e-mail/senha do admin — não existe cadastro público, é só esse
   usuário que vai conseguir entrar.
2. Rode `supabase/schema.sql` (já vem com as políticas de RLS restringindo
   as tabelas a usuário autenticado).

Para parear um celular novo: logado na tela de controle, clique em
**"📱 Parear novo aparelho"** — aparece um QR code. Abra o app no celular
(ele mostra a câmera na primeira vez que abre) e aponte para o QR. O
aparelho salva a configuração sozinho e já fica pronto para conectar,
sem precisar digitar nada nem recompilar o app para cada aparelho.

## Janela de rastreamento: segunda a quinta, 40 dias de histórico

- **Dias:** o `LocationTrackingService` só grava ponto de segunda a quinta
  (sexta, sábado e domingo o serviço roda mas não manda nada).
- **Retenção:** 1 mês e 10 dias (40 dias) — depois disso, o
  `limpar_historico_antigo()` apaga sozinho (madrugada, via `pg_cron`).
- **Ver a rota:** no popup de cada aparelho no mapa da frota, dá pra
  escolher a data (dentro desses 40 dias, só seg-qui) e ver o trajeto,
  distância e velocidade daquele dia específico — não só "hoje".

## Quantos celulares aguenta

- **Sessão de suporte (tela/áudio/controle):** não escala com o tamanho da
  frota — é P2P, só conta quem está conectado naquele momento (normalmente
  1 sessão, já que é um admin só).
- **Localização:** leve (uma requisição HTTP a cada 5 min por celular), dá
  tranquilo pra dezenas de aparelhos até no plano gratuito do Supabase.
- **O que cresce com o tempo:** a tabela `localizacoes_historico`. Com isso
  rodando por meses numa frota grande, o banco cresce — por isso o
  `schema.sql` já inclui uma limpeza automática (mantém só os últimos 90
  dias, via `pg_cron`; habilite a extensão em **Database > Extensions** no
  painel do Supabase antes de rodar aquela parte do script).
- **Na prática:** plano gratuito aguenta bem até uns 30-50 celulares por
  alguns meses; se crescer além disso (ou quiser manter histórico mais
  longo), o plano Pro (uns US$25/mês) resolve sem precisar mudar nada na
  arquitetura.

## Localização da frota

Cada aparelho pareado roda um serviço (`LocationTrackingService`) com o GPS
ligado **durante o expediente configurado (7:30–22:00, ajustável no código)**
e manda um ponto a cada 5 minutos: localização + velocidade. Fora desse
horário o serviço fica parado (sem gastar bateria à toa) e não grava nada.

Na tela de controle, o botão **"🗺️ Mapa da frota"** mostra todos os
aparelhos com localização conhecida num mapa (OpenStreetMap via Leaflet,
sem precisar de chave de API do Google Maps). Clicando num marcador e em
**"Ver trajeto de hoje"**, aparece a rota percorrida no dia, distância
total, tempo em movimento e velocidade média/máxima — calculado a partir
dos pontos de `localizacoes_historico`.

**Permissão de localização:** em app comum, pede o diálogo padrão do
Android na primeira abertura depois do pareamento. Em aparelho gerenciado
pelo Headwind (Device Owner), dá para conceder isso sem diálogo via
`DevicePolicyManager.setPermissionGrantState` — diferente da câmera (tela)
e do microfone, localização é uma permissão "normal" de runtime, então o
Device Owner pode liberar de forma persistente sem o usuário precisar
aceitar nada.

**Nota:** mesmo sendo rastreamento de aparelho da empresa (prática comum e
geralmente aceita), vale incluir isso na mesma política escrita de uso que
mencionei acima — em várias situações rastreamento de localização de
funcionário também exige aviso, mesmo que a régua legal seja mais frouxa
que a de áudio.

## Permissão de microfone: pede uma vez só

O app pede a permissão de microfone (`RECORD_AUDIO`) apenas na primeira
abertura — depois de concedida, o Android nunca mais pergunta de novo,
diferente da tela (que o sistema força perguntar a cada sessão de
captura). Em aparelho gerenciado pelo Headwind, dá pra pular até esse
pedido único via `setPermissionGrantState` no Device Owner.

Enquanto o microfone está sendo usado, o próprio Android (a partir da
versão 12) mostra um **ponto verde na barra de status** — isso é do
sistema operacional, nenhum app controla ou consegue desligar esse
indicador. Então "permissão concedida uma vez + indicador automático
de uso" já é exatamente o comportamento padrão aqui, sem precisar de
nada extra no código.

## Sobre o aviso de sessão ativa

A notificação fixa de "suporte remoto ativo" continua aparecendo quando a
tela está sendo vista — deixei o visual mais discreto (sem som, sem
pop-up, só o ícone na barra de status), mas não removi, por dois motivos:

- **Monitorar o microfone sem nenhum indicativo é, na prática, escuta
  velada** — mesmo em aparelho da empresa, isso normalmente exige
  consentimento/aviso para ser legal, e sem isso fica tecnicamente igual
  ao que programas de espionagem (stalkerware) fazem. Prefiro não ajudar
  a esconder isso.
- A partir do Android 14, o próprio sistema **exige** uma notificação
  enquanto a captura de tela está ativa (foreground service tipo
  `mediaProjection`) — então não seria só uma escolha de design, em muitos
  aparelhos nem daria para remover de verdade.

Se o objetivo é reduzir o atrito com os funcionários, o caminho mais seguro
(inclusive para proteger a empresa legalmente) é ter uma política escrita
avisando que o suporte técnico pode acessar o aparelho remotamente — e não
tentar tornar isso invisível.

## Mapa da frota: por que Esri e não OpenStreetMap/CartoDB direto

Já troquei o provedor do fundo do mapa duas vezes:
- `tile.openstreetmap.org` direto **bloqueia** (erro 403) acesso vindo de
  um arquivo local ou app embutido assim, por política deles.
- O CartoDB (`basemaps.cartocdn.com`) passou a **exigir chave de API**
  mesmo no uso gratuito.

Por isso o mapa usa o Esri (`server.arcgisonline.com`), que não pede
chave e aceita esse tipo de uso. Se um dia ele também parar de funcionar
(provedores de tile gratuito mudam de política de vez em quando), a
solução mais estável a longo prazo é usar um provedor com chave de API
de verdade (MapTiler, Stadia Maps — ambos têm plano gratuito com
cadastro) em vez de ficar trocando entre serviços "sem chave".

## Configurar o Supabase

1. Crie um projeto em supabase.com (tem plano free).
2. Em **Project Settings > API**, copie a **URL** e a **anon key** — são
   os dois valores usados tanto na página web quanto no app Android.
3. Em **Database > Replication**, confirme que o Realtime está habilitado
   (já vem por padrão para canais Broadcast/Presence, não precisa de tabela).
4. (Opcional) Rode `supabase/schema.sql` no SQL Editor se quiser registrar
   os aparelhos da frota e um histórico de sessões.

## Gerar o APK sem Android Studio

Igual ao `.exe`, o APK também é compilado sozinho pelo GitHub Actions a
cada push que mexer em `android-app/` — não precisa instalar Android
Studio em lugar nenhum. O workflow
`.github/workflows/build-android.yml` compila num runner Linux do
próprio GitHub e deixa o APK pronto em **Actions > (o workflow que
rodou) > Artifacts**, com o nome `freedom-suporte-remoto-android`.

Esse APK é de **debug** (não assinado para loja/produção) — serve pra
testar nos aparelhos da frota via Headwind ou instalação direta, mas se
um dia quiser publicar numa loja própria, precisa de uma chave de
assinatura separada.

## Como testar

1. **Página de controle**: abra `web-control/index.html` no navegador (ou
   suba no GitHub Pages). Preencha a URL do Supabase, a anon key e o ID
   do celular.

2. **App Android**: abra `android-app/` no Android Studio, ajuste
   `DEVICE_ID`, `SUPABASE_URL` e `SUPABASE_ANON_KEY` em `MainActivity.kt`,
   instale num aparelho de teste. Hoje ele passa pelo diálogo normal do
   Android (aceitar compartilhamento de tela) — é o próximo ponto a resolver.

## Versão desktop (.exe)

A pasta `electron-app/` empacota a mesma página de controle como um app
desktop de verdade — abre com duplo clique, sem precisar de navegador.

**Testar localmente (em qualquer SO):**
```bash
cd electron-app
npm install
npm start
```

**Gerar o `.exe` sem precisar de uma máquina Windows:** suba este repositório
no GitHub — o workflow em `.github/workflows/build-electron.yml` já está
configurado para compilar automaticamente num runner Windows de verdade a
cada push na pasta `electron-app/`. Depois de rodar, baixe o instalador em
**Actions > (o workflow que rodou) > Artifacts**. Ele também compila para
Mac (`.dmg`) e Linux (`AppImage`) se você rodar `npm run dist` localmente
nesses sistemas.

Se mudar a URL/key do Supabase, troque direto em `electron-app/index.html`
(é cópia do `web-control/index.html`) antes de gerar o `.exe`, ou deixe os
campos em branco para o usuário preencher na hora.

## Hospedar no GitHub

- Suba a pasta inteira num repositório.
- Em **Settings > Pages** do repositório, aponte para a pasta `web-control/`
  (ou mova `index.html` para a raiz) — fica com uma URL pública tipo
  `https://seu-usuario.github.io/repo/`.
- A anon key do Supabase pode ficar visível no código-fonte da página (ela é
  pública por natureza); a segurança de quem consegue conectar deve vir de
  outro lugar — por exemplo, manter a URL do GitHub Pages fora de buscadores
  e usar IDs de celular difíceis de adivinhar, ou adicionar um login simples
  mais adiante.

## ⚠️ Ponto em aberto: conectar sem nenhum aceite na tela

Isso é o que falta amarrar com quem mantém o Headwind (Gabriel) antes do
protótipo virar algo usável sem toque manual no aparelho:

- **Ativar o serviço de acessibilidade sem o usuário ir em Configurações**:
  o Device Owner (Headwind) consegue fazer isso diretamente, escrevendo em
  `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` — mas quem precisa rodar
  esse código é o próprio Headwind (só o Device Owner tem esse privilégio),
  não este app separado. Vai ser preciso um pequeno ajuste no lado do
  Headwind para incluir o pacote `com.freedom.remoteaccess` nessa lista
  durante o provisionamento.
- **Conceder a permissão de microfone sem diálogo**: o Headwind já pode
  delegar isso via `DevicePolicyManager.setPermissionGrantState` ou via
  `setDelegatedScopes` com `DELEGATION_PERMISSION_GRANT` para este app.
  Também depende de um ajuste no lado do Headwind.
- **Pular o diálogo de "compartilhar tela" (MediaProjection)**: esse é o
  mais incerto dos três. Não existe uma API pública garantida, em todas as
  marcas (Xiaomi/Poco costuma ser a mais restritiva), para o Device Owner
  liberar a MediaProjection sem o diálogo do sistema aparecer. Alguns
  fabricantes/MDMs conseguem isso apenas quando o app é também assinado
  como app de sistema (pré-instalado na ROM) — o que é mais trabalho de
  configurar. **Vale testar isso cedo, num aparelho de cada marca da
  frota, antes de investir no resto.**

Enquanto isso não estiver resolvido, o app funciona normalmente pedindo o
aceite na primeira vez (como já está no `MainActivity.kt`) — o que já cobre
"ver, ouvir e controlar", só não é 100% silencioso ainda.

## Sobre o aviso fixo na tela do funcionário

O serviço (`ScreenCaptureService`) sobe como **foreground service com
notificação fixa** ("sessão de suporte remoto ativa") enquanto a tela está
sendo vista — isso fica assim mesmo depois de resolvido o ponto acima,
por transparência com quem usa o aparelho.

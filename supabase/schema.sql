-- Rode isso no SQL Editor do seu projeto Supabase.

create table if not exists devices (
  id text primary key,                 -- o deviceId gerado no pareamento
  nome text,                           -- apelido do aparelho (ex: "Poco X5 - Vendas 03")
  marca text,
  pareamento_token text,               -- token de uso único do QR de pareamento
  pareado boolean default false,       -- vira true quando o celular escaneia o QR
  criado_em timestamptz default now(),
  ultima_conexao timestamptz,
  latitude double precision,
  longitude double precision,
  localizacao_atualizada_em timestamptz
);

create table if not exists sessoes (
  id uuid primary key default gen_random_uuid(),
  device_id text references devices(id),
  iniciada_em timestamptz default now(),
  encerrada_em timestamptz,
  modo_controle_usado boolean default false
);

-- Histórico de pontos de localização (um registro por captura, a cada
-- ~5 minutos, só de segunda a quinta, durante o expediente) — é o que
-- permite calcular velocidade e distância andada, além de desenhar o
-- trajeto de qualquer dia dentro da janela de retenção abaixo.
create table if not exists localizacoes (
  id uuid primary key default gen_random_uuid(),
  device_id text references devices(id),
  latitude double precision not null,
  longitude double precision not null,
  velocidade_kmh double precision,
  capturado_em timestamptz default now()
);
create index if not exists idx_localizacoes_device_dia
  on localizacoes (device_id, capturado_em);

alter publication supabase_realtime add table devices;
alter publication supabase_realtime add table sessoes;
alter publication supabase_realtime add table localizacoes;

-- RLS: só usuário autenticado (o admin) pode ler/escrever nestas tabelas.
alter table devices enable row level security;
alter table sessoes enable row level security;
alter table localizacoes enable row level security;

create policy "admin le e escreve devices"
  on devices for all
  using (auth.role() = 'authenticated')
  with check (auth.role() = 'authenticated');

create policy "admin le e escreve sessoes"
  on sessoes for all
  using (auth.role() = 'authenticated')
  with check (auth.role() = 'authenticated');

-- O celular só INSERE pontos novos (nunca lê, edita ou apaga histórico).
grant insert on localizacoes to anon;
create policy "celular insere seu ponto de localizacao"
  on localizacoes for insert
  to anon
  with check (true);

-- Só o admin logado consegue ler o histórico (para montar o trajeto/velocidade).
create policy "admin le historico de localizacao"
  on localizacoes for select
  to authenticated
  using (true);

-- Crie o usuário admin em Authentication > Users > Add user, no painel do
-- Supabase (email + senha). Não existe um "cadastro público" nesta tabela —
-- é só esse usuário que vai conseguir logar na tela de controle.

-- O APP DO CELULAR escreve a própria localização usando a anon key (sem
-- login), então precisa de uma política própria, mais restrita: só pode
-- ATUALIZAR (nunca ler, nunca apagar) as colunas de localização.
grant update (latitude, longitude, localizacao_atualizada_em, ultima_conexao)
  on devices to anon;

create policy "celular atualiza a propria localizacao"
  on devices for update
  to anon
  using (true)
  with check (true);

-- CAVEAT do protótipo: como é a anon key (pública) quem escreve, qualquer
-- um que tenha a key consegue atualizar a localização de qualquer deviceId,
-- não só o seu. Para produção, troque isso por uma Edge Function do
-- Supabase que valide um token por aparelho antes de gravar.

-- Limpeza automática: mantém o histórico por 1 mês e 10 dias (40 dias) —
-- depois disso, apaga sozinho. Ajuste o "40" se decidir guardar mais ou
-- menos tempo.
create or replace function limpar_historico_antigo()
returns void as $$
begin
  delete from localizacoes
  where capturado_em < now() - interval '40 days';
end;
$$ language plpgsql;

-- Agenda a limpeza pra rodar toda madrugada (precisa da extensão pg_cron,
-- habilite em Database > Extensions no painel do Supabase antes de rodar
-- esta linha).
select cron.schedule(
  'limpar-historico-localizacao',
  '0 3 * * *', -- 3h da manhã, todo dia
  $$ select limpar_historico_antigo(); $$
);

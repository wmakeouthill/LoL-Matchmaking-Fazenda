# Implementação de Special Users - Votação Instantânea

## 📋 Resumo

Implementado sistema de **Special Users** onde jogadores privilegiados podem finalizar a votação de partida com apenas 1 voto, vinculando automaticamente a partida customizada à partida do LCU e completando com todos os dados.

---

## 🎯 Objetivo

**Problema Original:**

- Modal de votação exibia composições incompletas
- Partidas não finalizavam automaticamente após votação
- Necessário aguardar 5 votos mesmo quando um admin/special user votava

**Solução Implementada:**

1. ✅ Modal corrigido com composições completas (10 jogadores)
2. ✅ Layout compacto e otimizado
3. ✅ **Sistema de Special Users** para votação instantânea
4. ✅ Vinculação automática com dados do LCU

---

## 🏗️ Arquitetura

### Backend (Spring Boot)

#### 1. **SpecialUserService.java** (NOVO)

- Gerencia lista de special users armazenada na tabela `settings`
- Métodos principais:
  - `isSpecialUser(summonerName)`: Verifica se jogador é special user
  - `getSpecialUsers()`: Retorna lista de special users
  - `addSpecialUser(summonerName)`: Adiciona special user
  - `removeSpecialUser(summonerName)`: Remove special user

**Formato na tabela `settings`:**

```sql
settings_key: "special_users"
value: ["FZD Ratoso#fzd", "Player2#tag", "Player3#tag"]
```

#### 2. **MatchVoteService.java** (ATUALIZADO)

- Injetado `SpecialUserService`
- Modificado `processVote()`:
  - **Antes:** Sempre contava votos e verificava se atingiu 5
  - **Agora:**
    1. Verifica se jogador é special user
    2. Se SIM: retorna `shouldLink=true` imediatamente
    3. Se NÃO: segue fluxo normal (aguarda 5 votos)

**Resposta do voto:**

```json
{
  "success": true,
  "voteCount": 1,
  "lcuGameId": 123456789,
  "shouldLink": true,
  "specialUserVote": true,
  "voterName": "FZD Ratoso#fzd"
}
```

#### 3. **MatchVoteController.java** (ATUALIZADO)

- Detecta `specialUserVote` na resposta do service
- Logs diferenciados:
  - `🌟 SPECIAL USER finalizou a votação!`
  - `🎯 Limite de 5 votos atingido!`
- Vincula partida automaticamente em ambos os casos

#### 4. **WebSocket** (NOVO EVENTO)

- Evento: `special_user_vote`
- Payload:

```json
{
  "matchId": 123,
  "specialUser": "FZD Ratoso#fzd",
  "lcuGameId": 123456789,
  "message": "🌟 FZD Ratoso#fzd (SPECIAL USER) finalizou a votação!"
}
```

---

### Frontend (Angular)

#### 1. **winner-confirmation-modal.component.ts** (ATUALIZADO)

- Método `selectMatch()` modificado:
  - Recebe resposta do backend após voto
  - Verifica `voteResponse.specialUserVote`
  - Se `true`:
    - Exibe alert `🌟 Você é um SPECIAL USER!`
    - Emite `onConfirm` automaticamente (fecha modal)
    - Finaliza partida instantaneamente
  - Se `voteResponse.shouldLink === true` (5 votos):
    - Exibe alert `🎯 5 votos atingidos!`
    - Emite `onConfirm` automaticamente

**Fluxo:**

```typescript
async selectMatch(index) {
  const voteResponse = await api.voteForMatch(matchId, lcuGameId);
  
  if (voteResponse.specialUserVote === true) {
    alert('🌟 SPECIAL USER! Finalizando automaticamente...');
    this.onConfirm.emit({ match, winner });
  } else if (voteResponse.shouldLink === true) {
    alert('🎯 5 votos atingidos! Finalizando...');
    this.onConfirm.emit({ match, winner });
  }
}
```

---

## 🔄 Fluxo Completo

### Caso 1: Special User vota

```
1. Jogador abre modal de votação
2. Seleciona uma partida LCU
3. Frontend envia voto: POST /api/match/{id}/vote
4. Backend:
   - MatchVoteService.processVote()
   - SpecialUserService.isSpecialUser() → TRUE
   - Retorna shouldLink=true, specialUserVote=true
5. Controller detecta special user
   - Busca dados completos do LCU
   - Vincula partida automaticamente
   - Atualiza status para "completed"
6. Frontend recebe resposta
   - Detecta specialUserVote=true
   - Exibe alert de confirmação
   - Fecha modal automaticamente
   - Emite evento onConfirm
7. Partida finalizada! ✅
```

### Caso 2: 5 jogadores normais votam

```
1-4. (Igual ao caso 1)
5. Backend:
   - MatchVoteService.processVote()
   - SpecialUserService.isSpecialUser() → FALSE
   - Conta votos: voteCount++
   - Verifica se voteCount >= 5
   - Se SIM: retorna shouldLink=true, specialUserVote=false
6-7. (Igual ao caso 1, mas sem mensagem de special user)
```

---

## 📊 Banco de Dados

### Tabela `settings`

```sql
CREATE TABLE settings (
  settings_key VARCHAR(191) PRIMARY KEY,
  value TEXT NOT NULL,
  created_at TIMESTAMP,
  updated_at TIMESTAMP
);
```

### Inserir Special Users

```sql
INSERT INTO settings (settings_key, value, created_at, updated_at)
VALUES (
  'special_users',
  '["FZD Ratoso#fzd", "Admin#tag", "Moderador#br1"]',
  NOW(),
  NOW()
);
```

### Atualizar Special Users

```sql
UPDATE settings
SET value = '["FZD Ratoso#fzd", "NewUser#tag"]',
    updated_at = NOW()
WHERE settings_key = 'special_users';
```

---

## 🧪 Testes

### Testar Special User

1. **Configurar no banco:**

```sql
UPDATE settings
SET value = '["SEU_SUMMONER_NAME#TAG"]'
WHERE settings_key = 'special_users';
```

2. **Jogar partida customizada** com esse jogador

3. **Abrir modal de votação:**
   - Modal deve mostrar partidas LCU com composições completas
   - Selecionar uma partida
   - ✅ **Deve finalizar instantaneamente** sem aguardar outros votos
   - ✅ Alert: `🌟 Você é um SPECIAL USER!`

4. **Verificar no banco:**

```sql
SELECT status, winner_team, linked_lcu_game_id
FROM custom_matches
WHERE id = <MATCH_ID>;
```

- `status` = `completed`
- `linked_lcu_game_id` = ID da partida LCU votada

### Testar Votação Normal (5 votos)

1. **Usar jogadores não-special users**
2. Primeiro voto: partida continua `in_progress`
3. Segundo voto: partida continua `in_progress`
4. ...
5. **Quinto voto**: partida finaliza automaticamente
   - ✅ Alert: `🎯 5 votos atingidos!`
   - Status muda para `completed`

---

## 📝 Logs Importantes

### Backend

```
🗳️ Processando voto: matchId=123, playerId=1, lcuGameId=987654321
🌟 [MatchVote] FZD Ratoso#fzd é SPECIAL USER! Voto finaliza automaticamente a partida
🌟 SPECIAL USER finalizou a votação! Vinculando partida automaticamente...
🎯 Buscando dados da partida LCU: 987654321
🏆 Vencedor detectado: blue
✅ Partida atualizada no banco com sucesso
📢 Notificação de special user vote enviada via WebSocket
```

### Frontend

```
✅ [WinnerModal] Voto registrado para LCU game: 987654321
📊 [WinnerModal] Resposta do voto: { specialUserVote: true, shouldLink: true, ... }
🌟 [WinnerModal] SPECIAL USER detectado! Finalizando partida automaticamente...
```

---

## 🚀 Próximas Melhorias

1. **Interface de gerenciamento de special users**
   - Adicionar tela de admin para gerenciar lista
   - Não precisar editar diretamente no banco

2. **Níveis de privilégio**
   - Special User nível 1: finaliza com 1 voto
   - Special User nível 2: finaliza com 3 votos
   - Moderador: pode remover votos de outros

3. **Auditoria**
   - Registrar quem finalizou cada partida
   - Histórico de special user votes

4. **Notificação visual**
   - Badge especial no modal para special users
   - Indicação de quem pode finalizar votação

---

## ✅ Checklist de Implementação

- [x] Criar `SpecialUserService`
- [x] Injetar `SpecialUserService` no `MatchVoteService`
- [x] Modificar lógica de `processVote()` para detectar special users
- [x] Atualizar `MatchVoteController` para processar voto especial
- [x] Adicionar evento WebSocket `special_user_vote`
- [x] Atualizar frontend para detectar `specialUserVote` na resposta
- [x] Implementar fechamento automático do modal
- [x] Testar com special user configurado no banco
- [x] Documentar implementação

---

## 🎉 Resultado Final

✅ **Modal de votação funcional** com composições completas  
✅ **Layout otimizado** (lane badges dentro dos cards, stats em linha única)  
✅ **Special users** podem finalizar votação instantaneamente  
✅ **Vinculação automática** com dados completos do LCU  
✅ **Histórico de partidas** montado perfeitamente  

**Sem mais necessidade de esperar 10 jogadores votarem!** 🚀

# 🔍 Logs de Debug do Sistema de Draft

## Objetivo

Este documento descreve todos os logs detalhados adicionados para facilitar o debug do sistema de draft, incluindo informações sobre jogadores, ações, índices e dados salvos.

---

## 📱 Frontend - draft-pick-ban.ts

### 🚀 Método `onanySelected()` - Início da Ação

Quando o usuário confirma um campeão no modal, o método exibe:

```
========== [onanySelected] INÍCIO DA AÇÃO ==========
🎯 Campeão: Ahri (ID: Ahri)
🎯 MatchId: 12345
🎯 Current Action Index: 3
🎯 Fase Atual: ban (Team: 1)
🎯 Jogador da Vez: FZD Ratoso#fzd (ID: FZD Ratoso#fzd)
🎯 Current Player: FZD Ratoso#fzd
🎯 Is Editing Mode: false
🎯 Total Phases: 20
====================================================
```

**Informações mostradas:**

- Nome e ID do campeão selecionado
- ID da partida
- Índice da ação atual (0-19)
- Tipo de ação (ban/pick) e time (1 ou 2)
- Nome e ID do jogador que deve executar esta ação
- Nome do jogador atual (usuário logado)
- Se está em modo de edição
- Total de fases no draft

### 📤 Método `onanySelected()` - Resposta do Backend

Após enviar para o backend:

```
========== [onanySelected] RESPOSTA DO BACKEND ==========
✅ Status: SUCCESS
✅ Resposta completa: {
  "success": true
}
=========================================================
```

**Informações mostradas:**

- Status da requisição (SUCCESS/ERROR)
- JSON completo da resposta do backend

---

## 🖥️ Backend - DraftController.java

### 📥 Endpoint POST `/match/draft-action` - Requisição Recebida

Quando o backend recebe uma requisição:

```
╔════════════════════════════════════════════════════════════════╗
║  🎯 [DraftController] REQUISIÇÃO RECEBIDA                     ║
╚════════════════════════════════════════════════════════════════╝
📥 POST /match/draft-action
📋 Match ID: 12345
📋 Action Index: 3
📋 Champion ID: Ahri
📋 Player ID: FZD Ratoso#fzd
📋 Action: ban
```

**Informações mostradas:**

- ID da partida
- Índice da ação
- ID do campeão
- ID do jogador
- Tipo de ação (ban/pick)

### ✅ Endpoint POST `/match/draft-action` - Sucesso

Quando a ação é processada com sucesso:

```
╔════════════════════════════════════════════════════════════════╗
║  ✅ [DraftController] AÇÃO PROCESSADA COM SUCESSO             ║
╚════════════════════════════════════════════════════════════════╝
```

### ❌ Endpoint POST `/match/draft-action` - Erro

Quando a ação é rejeitada:

```
╔════════════════════════════════════════════════════════════════╗
║  ❌ [DraftController] AÇÃO REJEITADA                          ║
╚════════════════════════════════════════════════════════════════╝
⚠️ Motivos possíveis:
   - Campeão já utilizado
   - Jogador incorreto para esta ação
   - Ação fora de ordem
```

---

## 🔧 Backend - DraftFlowService.java

### 🔵 Método `processAction()` - Início

Quando o serviço começa a processar uma ação:

```
========================================
🔵 [processAction] === INICIANDO AÇÃO ===
========================================
📋 MatchId: 12345
📋 Action Index: 3
📋 Champion ID: Ahri
📋 By Player: FZD Ratoso#fzd
📊 Estado do Draft:
   - Current Index: 3
   - Total Actions: 20
   - Team1 Players: [Player1, Player2, Player3, Player4, Player5]
   - Team2 Players: [Player6, Player7, Player8, Player9, Player10]
```

**Informações mostradas:**

- Dados da requisição (matchId, actionIndex, championId, byPlayer)
- Estado atual do draft:
  - Índice atual da ação
  - Total de ações no draft
  - Lista de jogadores do Team 1
  - Lista de jogadores do Team 2

### ✅ Método `processAction()` - Ação Salva com Sucesso

Quando a ação é salva no banco de dados:

```
💾 Salvando ação no banco de dados...
✅ Ação salva com sucesso!

========================================
✅ [processAction] === AÇÃO SALVA COM SUCESSO ===
========================================
🎯 Ação #3: BAN Ahri por FZD Ratoso#fzd
🎯 Team: 1
🎯 Champion ID normalizado: Ahri
🎯 Champion Name: Ahri
🎯 Próxima ação: 4 / 20
📄 JSON da ação salva:
   {
     "index": 3,
     "type": "ban",
     "team": 1,
     "championId": "Ahri",
     "championName": "Ahri",
     "byPlayer": "FZD Ratoso#fzd"
   }
========================================
```

**Informações mostradas:**

- Número da ação (0-19)
- Tipo de ação (BAN/PICK)
- Nome do campeão
- Jogador que executou
- Time (1 ou 2)
- Champion ID normalizado
- Nome do campeão (traduzido)
- Próxima ação (índice/total)
- **JSON completo da ação salva** - CRUCIAL para verificar se os dados estão corretos

### 🏁 Método `processAction()` - Draft Completo

Quando o draft é finalizado:

```
🏁 [processAction] Draft completo! Broadcast de conclusão...
```

---

## 📊 Como Usar os Logs para Debug

### Cenário 1: Ação não está sendo salva

1. **Verificar se o frontend está chamando o backend:**
   - Procurar por `========== [onanySelected] INÍCIO DA AÇÃO ==========` no `electron.log`
   - Verificar se os dados estão corretos (campeão, índice, jogador)

2. **Verificar se o backend está recebendo:**
   - Procurar por `🎯 [DraftController] REQUISIÇÃO RECEBIDA` no `backend.log`
   - Se NÃO aparecer: problema na comunicação frontend→backend
   - Se aparecer: verificar os dados recebidos

3. **Verificar se a ação está sendo processada:**
   - Procurar por `🔵 [processAction] === INICIANDO AÇÃO ===` no `backend.log`
   - Verificar o estado do draft (current index, total actions, players)

4. **Verificar se foi salva com sucesso:**
   - Procurar por `✅ [processAction] === AÇÃO SALVA COM SUCESSO ===`
   - **Verificar o JSON da ação salva** - confirmar se os dados estão corretos

### Cenário 2: Jogador errado está executando a ação

1. **No frontend**, verificar:

   ```
   🎯 Jogador da Vez: FZD Ratoso#fzd (ID: FZD Ratoso#fzd)
   🎯 Current Player: FZD Ratoso#fzd
   ```

   - Esses dois devem ser iguais

2. **No backend**, verificar:

   ```
   🔍 [processAction] Jogador esperado: FZD Ratoso#fzd
   🔍 [processAction] Jogador recebido: FZD Ratoso#fzd
   ```

   - Se forem diferentes: problema na ordem do draft

### Cenário 3: Ordem do draft está errada

1. **Verificar o índice da ação:**

   ```
   📋 Action Index: 3
   🎯 Current Action Index: 3
   ```

   - Devem ser iguais

2. **Verificar se o jogador pertence ao time correto:**

   ```
   🎯 Fase Atual: ban (Team: 1)
   📊 Estado do Draft:
      - Team1 Players: [Player1, Player2, Player3, Player4, Player5]
      - Team2 Players: [Player6, Player7, Player8, Player9, Player10]
   ```

3. **Verificar a sequência de ações:**
   - Ações 0-5: Bans alternados (Team1, Team2, Team1, Team2, Team1, Team2)
   - Ações 6-11: Picks alternados (Team1, Team2, Team2, Team1, Team1, Team2)
   - Ações 12-15: Bans alternados (Team1, Team2, Team1, Team2)
   - Ações 16-19: Picks alternados (Team2, Team1, Team2, Team1)

### Cenário 4: Modal não está emitindo evento

1. **Verificar logs do modal:**

   ```
   🖱️ [onanyCardClick] Click detectado no campeão
   🎯 [DraftanyModal] === SELECIONANDO CAMPEÃO ===
   📤 [confirmModalSelection] EMITINDO EVENTO onanySelected...
   ```

   - Se o último log NÃO aparecer: botão "Confirmar" não foi clicado

2. **Verificar se o método pai foi chamado:**

   ```
   🚀🚀🚀 [onanySelected] MÉTODO CHAMADO!
   ```

   - Se NÃO aparecer: evento não foi emitido

---

## 🎯 Resumo dos Logs Importantes

### ✅ Logs que DEVEM aparecer em uma ação bem-sucedida

1. Frontend:
   - `========== [onanySelected] INÍCIO DA AÇÃO ==========`
   - `========== [onanySelected] RESPOSTA DO BACKEND ==========`

2. Backend Controller:
   - `🎯 [DraftController] REQUISIÇÃO RECEBIDA`
   - `✅ [DraftController] AÇÃO PROCESSADA COM SUCESSO`

3. Backend Service:
   - `🔵 [processAction] === INICIANDO AÇÃO ===`
   - `✅ [processAction] === AÇÃO SALVA COM SUCESSO ===`
   - `📄 JSON da ação salva:` (com o JSON completo)

### ❌ Logs que indicam problemas

- `❌ [onanySelected] Session não existe`
- `❌ [onanySelected] Fase atual não existe`
- `❌ [DraftController] AÇÃO REJEITADA`
- `❌ [processAction] DraftState não encontrado`
- `❌ [processAction] Draft já completo`
- `❌ [processAction] actionIndex diferente`
- `❌ [processAction] Jogador NÃO pertence ao time`

---

## 📁 Localização dos Logs

- **Frontend**: `electron.log` (raiz do projeto)
- **Backend**: `backend.log` (raiz do projeto) ou `logs/application.log`

---

## 🔄 Fluxo Completo com Logs

```
1. Usuário clica no campeão
   └─> 🖱️ [onanyCardClick] Click detectado

2. Campeão é selecionado
   └─> 🎯 [DraftanyModal] === SELECIONANDO CAMPEÃO ===

3. Usuário clica em "Confirmar"
   └─> 📤 [confirmModalSelection] EMITINDO EVENTO

4. Método pai é chamado
   └─> 🚀🚀🚀 [onanySelected] MÉTODO CHAMADO!
   └─> ========== [onanySelected] INÍCIO DA AÇÃO ==========

5. Requisição é enviada ao backend
   └─> 📡📡📡 [onanySelected] ENVIANDO PARA BACKEND!

6. Backend recebe a requisição
   └─> 🎯 [DraftController] REQUISIÇÃO RECEBIDA

7. Service processa a ação
   └─> 🔵 [processAction] === INICIANDO AÇÃO ===

8. Ação é salva no banco
   └─> 💾 Salvando ação no banco de dados...
   └─> ✅ [processAction] === AÇÃO SALVA COM SUCESSO ===
   └─> 📄 JSON da ação salva: {...}

9. Controller retorna sucesso
   └─> ✅ [DraftController] AÇÃO PROCESSADA COM SUCESSO

10. Frontend recebe resposta
    └─> ========== [onanySelected] RESPOSTA DO BACKEND ==========
```

---

## 💡 Dicas de Debug

1. **Use grep para filtrar logs:**

   ```bash
   # Ver todas as ações processadas
   grep "AÇÃO SALVA COM SUCESSO" backend.log
   
   # Ver todos os JSONs salvos
   grep -A 10 "JSON da ação salva" backend.log
   
   # Ver todos os erros
   grep "❌" backend.log
   ```

2. **Compare os índices:**
   - Frontend deve enviar `actionIndex` igual ao `currentAction` da sessão
   - Backend deve receber o mesmo `actionIndex`
   - O JSON salvo deve ter o mesmo `index`

3. **Verifique a ordem dos jogadores:**
   - Compare `Jogador da Vez` (frontend) com `Jogador esperado` (backend)
   - Se diferentes, problema na função `getPlayerForTeamAndIndex()`

4. **Use o JSON salvo para validar:**
   - O JSON mostra exatamente o que foi salvo no banco
   - Compare com o que foi enviado pelo frontend
   - Verifique `championId`, `championName`, `byPlayer`, `type`, `team`

---

**Data de criação**: 02/10/2025
**Versão**: 1.0

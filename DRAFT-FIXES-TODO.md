# 🔧 TO-DO LIST - Correções do Sistema de Draft

> **Data de análise:** 01/10/2025  
> **Baseado em:** electron.log, backend.log e análise completa do código

---

## 📋 RESUMO DOS PROBLEMAS

O draft funciona parcialmente:

- ✅ Fluxo é exibido corretamente
- ✅ Modal de seleção de campeão abre
- ❌ Picks/bans **NÃO são salvos** quando confirmados
- ❌ Fotos dos campeões **não aparecem** (apenas fallback)
- ❌ Pesquisa **não filtra em tempo real** (sem debounce)
- ❌ Campeões já pickados/banidos **não ficam bloqueados** visualmente
- ❌ Backend **não valida adequadamente** picks repetidos

---

## 🔴 PROBLEMA 1: Pick/Ban não são salvos quando jogador confirma

### **Causa Raiz**

O frontend envia a ação via **HTTP POST** para `/match/draft-action`, mas este endpoint **NÃO EXISTE** no backend Spring Boot atual.

### **Evidências**

```typescript
// frontend/src/app/components/draft/draft-pick-ban.ts (linha ~2066)
const url = `${this.baseUrl}/match/draft-action`;
const response = await firstValueFrom(this.http.post(url, requestData, { ... }));
```

```java
// src/main/java/br/com/lolmatchmaking/backend/websocket/CoreWebSocketHandler.java
// Linha 210: handleDraftAction() existe, mas só no WebSocket, não REST
private void handleDraftAction(WebSocketSession session, JsonNode root) throws IOException {
    // ...
    boolean ok = draftFlowService.processAction(matchId, actionIndex, championId, byPlayer);
    // ...
}
```

### **Arquivos Afetados**

- ❌ `frontend/src/app/components/draft/draft-pick-ban.ts` (linha ~2066)
- ❌ `src/main/java/br/com/lolmatchmaking/backend/controller/` (falta criar)
- ⚠️ `src/main/java/br/com/lolmatchmaking/backend/service/DraftFlowService.java` (linha 237)

### **Solução**

**OPÇÃO A: Criar endpoint REST** (Recomendado para consistência com arquitetura atual)

```java
// src/main/java/br/com/lolmatchmaking/backend/controller/DraftController.java
@RestController
@RequestMapping("/api/match")
public class DraftController {
    
    @Autowired
    private DraftFlowService draftFlowService;

    @PostMapping("/draft-action")
    public ResponseEntity<Map<String, Object>> processDraftAction(@RequestBody DraftActionRequest request) {
        boolean success = draftFlowService.processAction(
            request.getMatchId(),
            request.getActionIndex(),
            request.getChampionId(), // ⚠️ Converter para String se necessário
            request.getPlayerId()
        );

        if (success) {
            return ResponseEntity.ok(Map.of("success", true));
        } else {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "error", "Ação inválida ou campeão já utilizado"
            ));
        }
    }
}

// DTO
public class DraftActionRequest {
    private Long matchId;
    private Integer actionIndex;
    private String championId; // ou Integer, dependendo do formato
    private String playerId;
    // getters/setters
}
```

**OPÇÃO B: Usar WebSocket no frontend** (Alternativa)

```typescript
// Enviar via WebSocket ao invés de HTTP POST
this.apiService.sendWebSocketMessage({
    type: 'draft_action',
    data: {
        matchId: effectiveMatchId,
        actionIndex: this.session.currentAction,
        championId: champion.key, // ⚠️ Usar key numérico
        playerId: playerIdentifier
    }
});
```

---

## 🔴 PROBLEMA 2: Fotos dos campeões não aparecem

### **Causa Raiz**

A URL da imagem não está sendo construída corretamente ou o `champion.id` não é o valor esperado pela Data Dragon.

### **Evidências**

```typescript
// ChampionService está correto:
getChampionImageUrl(championId: number): string {
    const champion = this.championsCache.get(championId.toString());
    if (champion) {
        return `${this.DD_BASE_URL}/img/champion/${champion.image.full}`;
        // Deveria ser: https://ddragon.leagueoflegends.com/cdn/15.19.1/img/champion/Aatrox.png
    }
    return `${this.DD_BASE_URL}/img/champion/champion_placeholder.png`;
}
```

**Problema**: O modal pode não estar usando `champion.image.full` corretamente.

### **Arquivos Afetados**

- ❌ `frontend/src/app/components/draft/draft-champion-modal.html` (template)
- ⚠️ `frontend/src/app/components/draft/draft-champion-modal.ts` (métodos de exibição)
- ✅ `frontend/src/app/services/champion.service.ts` (parece correto)

### **Solução**

**1. Verificar template HTML:**

```html
<!-- draft-champion-modal.html -->
<!-- ❌ ERRADO: -->
<img [src]="champion.id + '.png'" />

<!-- ✅ CORRETO: -->
<img [src]="getChampionImageUrl(champion)" 
     [alt]="champion.name"
     (error)="onImageError($event)" />
```

**2. Adicionar método no componente:**

```typescript
// draft-champion-modal.ts
getChampionImageUrl(champion: any): string {
    // Opção 1: Usar o serviço
    return this.championService.getChampionImageUrl(Number(champion.key));
    
    // Opção 2: Construir diretamente
    // return `https://ddragon.leagueoflegends.com/cdn/15.19.1/img/champion/${champion.id}.png`;
}

onImageError(event: any): void {
    event.target.src = 'assets/images/champion-placeholder.svg';
}
```

**3. Verificar estrutura de dados:**

```typescript
// Adicionar log para debug
console.log('🔍 Champion data:', {
    id: champion.id,           // "Aatrox"
    key: champion.key,         // "266"
    name: champion.name,       // "Aatrox"
    imageFull: champion.image.full  // "Aatrox.png"
});
```

---

## 🔴 PROBLEMA 3: Pesquisa sem debounce (não filtra em tempo real)

### **Causa Raiz**

O campo de pesquisa não tem debounce nem filtro reativo implementado.

### **Arquivos Afetados**

- ❌ `frontend/src/app/components/draft/draft-champion-modal.ts`
- ❌ `frontend/src/app/components/draft/draft-champion-modal.html`

### **Solução**

```typescript
// draft-champion-modal.ts
import { Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged } from 'rxjs/operators';

export class DraftChampionModalComponent implements OnInit, OnDestroy {
    searchFilter: string = '';
    private searchSubject = new Subject<string>();
    filteredChampions: any[] = [];

    ngOnInit() {
        // Configurar debounce na pesquisa
        this.searchSubject.pipe(
            debounceTime(500),              // ⏰ Aguardar 500ms após última digitação
            distinctUntilChanged()          // 🔄 Só processar se o valor mudou
        ).subscribe(searchTerm => {
            this.filterChampions(searchTerm);
        });

        this.loadChampions();
    }

    // Método chamado pelo ngModel
    onSearchChange(searchTerm: string): void {
        this.searchSubject.next(searchTerm);
    }

    private filterChampions(searchTerm: string): void {
        if (!searchTerm || searchTerm.trim() === '') {
            this.filteredChampions = this.champions;
            return;
        }

        const term = searchTerm.toLowerCase().trim();
        this.filteredChampions = this.champions.filter(champion =>
            champion.name.toLowerCase().includes(term) ||
            champion.title?.toLowerCase().includes(term)
        );

        console.log(`🔍 Filtrados ${this.filteredChampions.length} campeões para "${searchTerm}"`);
        this.changeDetectorRef.detectChanges();
    }

    ngOnDestroy() {
        this.searchSubject.complete();
    }
}
```

```html
<!-- draft-champion-modal.html -->
<input type="text" 
       class="search-input"
       [(ngModel)]="searchFilter"
       (ngModelChange)="onSearchChange($event)"
       placeholder="Pesquisar campeão..." />

<!-- Usar filteredChampions ao invés de champions -->
<div class="champions-grid">
    <div *ngFor="let champion of filteredChampions" 
         class="champion-card"
         [class.disabled]="isChampionBanned(champion) || isChampionPicked(champion)"
         (click)="selectChampion(champion)">
        <!-- ... -->
    </div>
</div>
```

---

## 🔴 PROBLEMA 4: Campeões pickados/banidos não ficam bloqueados

### **Causa Raiz**

Os métodos `getBannedChampions()` e `getTeamPicks()` buscam pelos campos errados na estrutura de dados.

**Backend envia:**

```json
{
    "type": "ban",      // ❌ Frontend procura por "action"
    "team": 1,          // ❌ Frontend procura por "teamIndex"
    "championId": "266", // ✅ Correto
    "byPlayer": "FZD Ratoso#fzd" // ✅ Correto
}
```

### **Evidências**

```typescript
// draft-champion-modal.ts (linha ~200)
// ❌ ERRADO:
const banActions = this.session.actions.filter((action: any) =>
    action.action === 'ban' && action.champion && action.locked
);

// ✅ CORRETO:
const banActions = this.session.actions.filter((action: any) =>
    action.type === 'ban' && action.championId && action.byPlayer
);
```

### **Arquivos Afetados**

- ❌ `frontend/src/app/components/draft/draft-champion-modal.ts` (linhas ~200-350)
- ❌ `frontend/src/app/components/draft/draft-champion-modal.html` (aplicar CSS)
- ❌ `frontend/src/app/components/draft/draft-champion-modal.scss` (criar estilos)

### **Solução**

**1. Corrigir métodos de detecção:**

```typescript
// draft-champion-modal.ts
getBannedChampions(): any[] {
    if (!this.session?.actions || this.session.actions.length === 0) {
        return [];
    }

    const bannedActions = this.session.actions.filter((action: any) => {
        // ✅ Usar estrutura correta do backend
        const isBan = action.type === 'ban';
        const hasChampion = action.championId && action.byPlayer;
        return isBan && hasChampion;
    });

    // ✅ Converter championId para objeto champion
    const bannedChampions = bannedActions.map((action: any) => {
        const championId = parseInt(action.championId, 10);
        return this.getChampionFromCache(championId);
    }).filter(champion => champion !== null);

    console.log('🚫 Campeões banidos:', bannedChampions.length);
    return bannedChampions;
}

getTeamPicks(team: 'blue' | 'red'): any[] {
    if (!this.session?.actions || this.session.actions.length === 0) {
        return [];
    }

    const teamNumber = team === 'blue' ? 1 : 2;

    const pickActions = this.session.actions.filter((action: any) => {
        // ✅ Usar estrutura correta do backend
        const isCorrectTeam = action.team === teamNumber;
        const isPick = action.type === 'pick';
        const hasChampion = action.championId && action.byPlayer;
        return isCorrectTeam && isPick && hasChampion;
    });

    // ✅ Converter championId para objeto champion
    const picks = pickActions.map((action: any) => {
        const championId = parseInt(action.championId, 10);
        return this.getChampionFromCache(championId);
    }).filter(champion => champion !== null);

    console.log(`🎯 Picks do time ${team}:`, picks.length);
    return picks;
}

// ✅ Método auxiliar para buscar campeão
private getChampionFromCache(championId: number): any {
    const cache = (this.championService as any).championsCache as Map<string, any>;
    if (!cache) return null;

    for (const [key, champ] of cache.entries()) {
        if (champ.key === championId.toString() || parseInt(champ.key, 10) === championId) {
            return champ;
        }
    }

    console.warn(`⚠️ Campeão ${championId} não encontrado no cache`);
    return null;
}
```

**2. Aplicar CSS no template:**

```html
<!-- draft-champion-modal.html -->
<div *ngFor="let champion of filteredChampions" 
     class="champion-card"
     [class.disabled]="isChampionBanned(champion) || isChampionPicked(champion)"
     [class.banned]="isChampionBanned(champion)"
     [class.picked]="isChampionPicked(champion)"
     (click)="selectChampion(champion)">
    
    <img [src]="getChampionImageUrl(champion)" 
         [alt]="champion.name" />
    
    <span class="champion-name">{{ champion.name }}</span>
    
    <!-- Overlay para banidos -->
    <div *ngIf="isChampionBanned(champion)" class="banned-overlay">
        <span class="overlay-text">🚫 BANIDO</span>
    </div>
    
    <!-- Overlay para pickados -->
    <div *ngIf="isChampionPicked(champion)" class="picked-overlay">
        <span class="overlay-text">✅ PICKADO</span>
    </div>
</div>
```

**3. Criar estilos CSS:**

```scss
// draft-champion-modal.scss
.champion-card {
    position: relative;
    cursor: pointer;
    transition: all 0.2s ease;

    &:hover:not(.disabled) {
        transform: scale(1.05);
        box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
    }

    &.disabled {
        cursor: not-allowed;
        opacity: 0.4;
        filter: grayscale(100%);

        &:hover {
            transform: none;
        }
    }

    .banned-overlay,
    .picked-overlay {
        position: absolute;
        top: 0;
        left: 0;
        right: 0;
        bottom: 0;
        display: flex;
        align-items: center;
        justify-content: center;
        background: rgba(0, 0, 0, 0.7);
        backdrop-filter: blur(2px);
        z-index: 2;

        .overlay-text {
            font-weight: bold;
            font-size: 0.9rem;
            color: white;
            text-align: center;
            padding: 4px 8px;
            border-radius: 4px;
        }
    }

    .banned-overlay {
        background: rgba(255, 0, 0, 0.6);
    }

    .picked-overlay {
        background: rgba(0, 128, 0, 0.6);
    }
}
```

**4. Bloquear clique em campeões desabilitados:**

```typescript
// draft-champion-modal.ts
selectChampion(champion: any): void {
    // ✅ Bloquear se banido ou pickado
    if (this.isChampionBanned(champion) || this.isChampionPicked(champion)) {
        console.warn(`⚠️ Campeão ${champion.name} não disponível`);
        // TODO: Mostrar toast de erro
        return;
    }

    // Processar seleção normal
    this.onChampionSelected.emit(champion);
}
```

---

## 🔴 PROBLEMA 5: Backend não valida picks repetidos adequadamente

### **Causa Raiz**

O método `processAction()` compara strings de championId, mas pode haver inconsistência de formato (ex: "1" vs "Aatrox" vs 1).

### **Evidências**

```java
// DraftFlowService.java (linha ~257)
boolean alreadyUsed = st.actions.stream()
    .filter(a -> a.championId() != null && !SKIPPED.equalsIgnoreCase(a.championId()))
    .anyMatch(a -> championId.equalsIgnoreCase(a.championId()));
```

**Problema**: Se o frontend enviar `championId` em formato diferente dos já salvos, a validação falha.

### **Arquivos Afetados**

- ⚠️ `src/main/java/br/com/lolmatchmaking/backend/service/DraftFlowService.java` (linha ~257)

### **Solução**

```java
// DraftFlowService.java
@Transactional
public synchronized boolean processAction(long matchId, int actionIndex, String championId, String byPlayer) {
    DraftState st = states.get(matchId);
    if (st == null) {
        log.warn("❌ [DraftFlow] Estado do draft não encontrado para matchId={}", matchId);
        return false;
    }

    if (st.getCurrentIndex() >= st.getActions().size()) {
        log.warn("❌ [DraftFlow] Draft já completo para matchId={}", matchId);
        return false;
    }

    if (actionIndex != st.currentIndex) {
        log.warn("❌ [DraftFlow] Ação fora de ordem: expected={}, received={}", st.currentIndex, actionIndex);
        return false;
    }

    DraftAction prev = st.actions.get(actionIndex);

    // ✅ CORREÇÃO: Normalizar championId para sempre ser numérico
    String normalizedChampionId = normalizeChampionId(championId);
    if (normalizedChampionId == null) {
        log.error("❌ [DraftFlow] championId inválido: {}", championId);
        return false;
    }

    // ✅ Validar se jogador pertence ao time da ação
    if (!st.isPlayerInTeam(byPlayer, prev.team())) {
        log.warn("❌ [DraftFlow] Jogador {} não pertence ao time {}", byPlayer, prev.team());
        return false;
    }

    // ✅ CORREÇÃO: Validar duplicatas comparando IDs normalizados
    boolean alreadyUsed = st.actions.stream()
        .filter(a -> a.championId() != null && !SKIPPED.equalsIgnoreCase(a.championId()))
        .anyMatch(a -> {
            String existingId = normalizeChampionId(a.championId());
            boolean isDuplicate = existingId != null && existingId.equals(normalizedChampionId);
            if (isDuplicate) {
                log.warn("⚠️ [DraftFlow] Campeão {} já foi usado (existente: {}, novo: {})", 
                    existingId, a.championId(), championId);
            }
            return isDuplicate;
        });

    if (alreadyUsed) {
        log.warn("❌ [DraftFlow] Campeão {} já foi pickado ou banido", championId);
        return false;
    }

    // ✅ Criar ação atualizada com ID normalizado
    DraftAction updated = new DraftAction(prev.index(), prev.type(), prev.team(), 
        normalizedChampionId, byPlayer);
    st.getActions().set(actionIndex, updated);
    st.advance();
    st.markActionStart();

    log.info("✅ [DraftFlow] Ação processada: matchId={}, action={}, team={}, champion={}, player={}", 
        matchId, prev.type(), prev.team(), normalizedChampionId, byPlayer);

    persist(matchId, st);
    broadcastUpdate(st, false);

    if (st.getCurrentIndex() >= st.getActions().size()) {
        broadcastDraftCompleted(st);
    }

    return true;
}

/**
 * Normaliza o championId para sempre ser numérico (key)
 * Aceita: "266", 266, "Aatrox"
 * Retorna: "266"
 */
private String normalizeChampionId(String championId) {
    if (championId == null || championId.isBlank()) {
        return null;
    }

    // Se já é numérico, retornar
    if (championId.matches("\\d+")) {
        return championId;
    }

    // Se é nome de campeão, tentar buscar key no DataDragonService
    try {
        Integer key = dataDragonService.getChampionKeyByName(championId);
        if (key != null) {
            return key.toString();
        }
    } catch (Exception e) {
        log.warn("⚠️ [DraftFlow] Erro ao buscar key para campeão: {}", championId, e);
    }

    log.warn("⚠️ [DraftFlow] Não foi possível normalizar championId: {}", championId);
    return null;
}
```

**Adicionar método no DataDragonService:**

```java
// DataDragonService.java
public Integer getChampionKeyByName(String championName) {
    return championsMap.values().stream()
        .filter(c -> c.getName().equalsIgnoreCase(championName) || 
                     c.getId().equalsIgnoreCase(championName))
        .map(ChampionData::getKey)
        .map(Integer::parseInt)
        .findFirst()
        .orElse(null);
}
```

---

## 🟢 MELHORIAS DE UX (Prioridade Baixa)

### 1. Feedback Visual Durante Ações

```typescript
// draft-pick-ban.ts
async onChampionSelected(champion: any): Promise<void> {
    // ✅ Mostrar loading
    this.isWaitingBackend = true;
    this.cdr.detectChanges();

    try {
        const response = await firstValueFrom(this.http.post(url, requestData));

        // ✅ Sucesso
        this.showToast('✅ ' + (isPick ? 'Pick' : 'Ban') + ' confirmado!', 'success');
        this.showChampionModal = false;

    } catch (error) {
        // ✅ Erro
        this.showToast('❌ Erro ao confirmar ação. Tente novamente.', 'error');
        console.error('Erro:', error);
    } finally {
        this.isWaitingBackend = false;
        this.cdr.detectChanges();
    }
}

// Método auxiliar para toasts
private showToast(message: string, type: 'success' | 'error' | 'info'): void {
    // TODO: Implementar sistema de toasts
    console.log(`[Toast ${type}]`, message);
}
```

### 2. Animações de Transição

```scss
// draft-pick-ban.scss
.draft-phase {
    animation: fadeIn 0.3s ease-in-out;
}

@keyframes fadeIn {
    from { opacity: 0; transform: translateY(10px); }
    to { opacity: 1; transform: translateY(0); }
}

.champion-card {
    transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1);
}
```

---

## ✅ CHECKLIST DE IMPLEMENTAÇÃO

### Backend

- [ ] **Criar `DraftController.java`** com endpoint `/api/match/draft-action`
- [ ] **Adicionar validação robusta** em `processAction()` com IDs normalizados
- [ ] **Criar método** `normalizeChampionId()` em `DraftFlowService`
- [ ] **Adicionar método** `getChampionKeyByName()` em `DataDragonService`
- [ ] **Melhorar logs** de erro com mais contexto

### Frontend - Componente Modal

- [ ] **Corrigir** `getBannedChampions()` para usar `action.type` e `action.team`
- [ ] **Corrigir** `getTeamPicks()` para usar estrutura correta
- [ ] **Implementar** `getChampionFromCache()` para conversão de IDs
- [ ] **Corrigir** URL das imagens dos campeões
- [ ] **Implementar** debounce na pesquisa (RxJS `debounceTime(500)`)
- [ ] **Adicionar** CSS para campeões bloqueados (`.disabled`, `.banned-overlay`, `.picked-overlay`)
- [ ] **Bloquear** clique em campeões banidos/pickados

### Frontend - Componente Draft

- [ ] **Usar endpoint REST** correto (`/api/match/draft-action`)
- [ ] **Enviar** `championId` como **key numérico** (ex: "266")
- [ ] **Adicionar** loading spinner durante envio de ação
- [ ] **Implementar** sistema de toasts para feedback
- [ ] **Desabilitar** botão de confirmação enquanto processa

### Testes

- [ ] **Testar** pick/ban com jogador humano
- [ ] **Testar** pick/ban com bot
- [ ] **Testar** tentativa de pick duplicado
- [ ] **Testar** pesquisa de campeões com debounce
- [ ] **Testar** bloqueio visual de campeões
- [ ] **Testar** imagens dos campeões carregando corretamente

---

## 📝 NOTAS ADICIONAIS

### Comparação com Backend Antigo

O backend antigo (`old backend`) tinha uma estrutura diferente:

- Usava **WebSocket** para todas as ações
- Tinha validação de duplicatas mais simples
- Armazenava campeões por **nome** ao invés de **key**

O novo backend centralizado precisa:

- ✅ Suportar **REST** para compatibilidade com frontend Angular
- ✅ Normalizar **championId** para evitar inconsistências
- ✅ Melhorar **logs** para facilitar debug

### Logs Úteis para Debug

```bash
# Backend
tail -f backend.log | grep "DraftFlow"

# Frontend (Electron)
tail -f electron.log | grep "DraftPickBan"
tail -f draft-debug.log
```

---

## 🎯 PRIORIZAÇÃO FINAL

### 🔥 **FAZER PRIMEIRO (Bloqueadores críticos)**

1. Criar endpoint REST `/api/match/draft-action`
2. Corrigir métodos de detecção de bans/picks no modal
3. Corrigir URL das imagens dos campeões

### 🟠 **FAZER EM SEGUIDA (Funcionalidades importantes)**

4. Implementar debounce na pesquisa
5. Adicionar CSS para campeões bloqueados
6. Melhorar validação de picks duplicados no backend

### 🟢 **FAZER POR ÚLTIMO (Melhorias de UX)**

7. Adicionar feedback visual (loading, toasts)
8. Adicionar animações de transição
9. Melhorar logs para debug

---

**Estimativa de tempo total:** 4-6 horas de desenvolvimento + 2 horas de testes

**Última atualização:** 01/10/2025

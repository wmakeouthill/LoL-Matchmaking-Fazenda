# 🔧 CORREÇÃO DA ORDEM DO DRAFT

## ❌ PROBLEMA: Ordem atual está COMPLETAMENTE ERRADA

### 📋 ORDEM CORRETA (do documento do usuário)

**Fase 1 - Bans (6 ações: 0-5):**

- Top Azul → Top Vermelho → Jungle Azul → Jungle Vermelho → Mid Azul → Mid Vermelho

**Fase 2 - Picks (6 ações: 6-11):**

- Azul pick 1 → Vermelho pick 2 → Azul pick 2 → Vermelho pick 1 → Azul pick 1 → (depois mais bans)

**TRADUZINDO FASE 2:**

- Ação 6: Azul pick 1 (quem?)
- Ação 7: Vermelho pick 1 (quem?)
- Ação 8: Vermelho pick 2 (quem?)
- Ação 9: Azul pick 2 (quem?)
- Ação 10: Azul pick 3 (quem?)
- Ação 11: Vermelho pick 3 (quem?)

**Fase 3 - Bans (4 ações: 12-15):**

- ADC Vermelho → ADC Azul → Suporte Vermelho → Suporte Azul

**Fase 4 - Picks (4 ações: 16-19):**

- Vermelho pick 4 → Azul pick 4 → Azul pick 5 → Vermelho pick 5

---

## 🔍 ANÁLISE DO JSON ATUAL

```json
"blue": {
  "players": [
    {"teamIndex": 0, "assignedLane": "top"},      // Bot4
    {"teamIndex": 1, "assignedLane": "jungle"},   // FZD Ratoso#fzd
    {"teamIndex": 2, "assignedLane": "mid"},      // Bot5
    {"teamIndex": 3, "assignedLane": "bot"},      // Bot7
    {"teamIndex": 4, "assignedLane": "support"}   // Bot3
  ]
}
```

**ORDEM DOS PICKS NO DRAFT PROFISSIONAL:**

- Pick 1: Top
- Pick 2: Jungle  
- Pick 3: Mid
- Pick 4: ADC
- Pick 5: Support

---

## ✅ MAPEAMENTO CORRETO

### Fase 1 - Bans (0-5)

```java
case 0: team=1, playerIndex=0; // Top Azul
case 1: team=2, playerIndex=0; // Top Vermelho
case 2: team=1, playerIndex=1; // Jungle Azul
case 3: team=2, playerIndex=1; // Jungle Vermelho
case 4: team=1, playerIndex=2; // Mid Azul
case 5: team=2, playerIndex=2; // Mid Vermelho
```

### Fase 2 - Picks (6-11)

```java
case 6:  team=1, playerIndex=0; // Azul Pick 1 (Top)
case 7:  team=2, playerIndex=0; // Vermelho Pick 1 (Top)
case 8:  team=2, playerIndex=1; // Vermelho Pick 2 (Jungle)
case 9:  team=1, playerIndex=1; // Azul Pick 2 (Jungle)
case 10: team=1, playerIndex=2; // Azul Pick 3 (Mid)
case 11: team=2, playerIndex=2; // Vermelho Pick 3 (Mid)
```

### Fase 3 - Bans (12-15)

```java
case 12: team=2, playerIndex=3; // ADC Vermelho
case 13: team=1, playerIndex=3; // ADC Azul
case 14: team=2, playerIndex=4; // Suporte Vermelho
case 15: team=1, playerIndex=4; // Suporte Azul
```

### Fase 4 - Picks (16-19)

```java
case 16: team=2, playerIndex=3; // Vermelho Pick 4 (ADC)
case 17: team=1, playerIndex=3; // Azul Pick 4 (ADC)
case 18: team=1, playerIndex=4; // Azul Pick 5 (Support) - LAST PICK
case 19: team=2, playerIndex=4; // Vermelho Pick 5 (Support)
```

---

## 🔧 CÓDIGO CORRETO

```java
switch (actionIndex) {
    // FASE 1 - BANS (0-5)
    case 0: playerIndex = 0; break; // Top Azul (team 1)
    case 1: playerIndex = 0; break; // Top Vermelho (team 2)
    case 2: playerIndex = 1; break; // Jungle Azul (team 1)
    case 3: playerIndex = 1; break; // Jungle Vermelho (team 2)
    case 4: playerIndex = 2; break; // Mid Azul (team 1)
    case 5: playerIndex = 2; break; // Mid Vermelho (team 2)

    // FASE 2 - PICKS (6-11)
    case 6: playerIndex = 0; break;  // Azul Pick 1: Top (team 1)
    case 7: playerIndex = 0; break;  // Vermelho Pick 1: Top (team 2)
    case 8: playerIndex = 1; break;  // Vermelho Pick 2: Jungle (team 2)
    case 9: playerIndex = 1; break;  // Azul Pick 2: Jungle (team 1)
    case 10: playerIndex = 2; break; // Azul Pick 3: Mid (team 1)
    case 11: playerIndex = 2; break; // Vermelho Pick 3: Mid (team 2)

    // FASE 3 - BANS (12-15)
    case 12: playerIndex = 3; break; // ADC Vermelho (team 2) ⚠️ ERRADO NO CÓDIGO!
    case 13: playerIndex = 3; break; // ADC Azul (team 1) ⚠️ ERRADO NO CÓDIGO!
    case 14: playerIndex = 4; break; // Suporte Vermelho (team 2) ⚠️ ERRADO NO CÓDIGO!
    case 15: playerIndex = 4; break; // Suporte Azul (team 1) ⚠️ ERRADO NO CÓDIGO!

    // FASE 4 - PICKS (16-19)
    case 16: playerIndex = 3; break; // Vermelho Pick 4: ADC (team 2)
    case 17: playerIndex = 3; break; // Azul Pick 4: ADC (team 1) ⚠️ ERRADO NO CÓDIGO!
    case 18: playerIndex = 4; break; // Azul Pick 5: Support (team 1)
    case 19: playerIndex = 4; break; // Vermelho Pick 5: Support (team 2) ⚠️ ERRADO NO CÓDIGO!
}
```

**PROBLEMAS NO CÓDIGO ATUAL:**

1. Fase 3 (bans 12-15): Está usando team1 quando deveria ser team2 e vice-versa
2. Fase 4 (picks 17 e 19): Está invertido

---

## 🎯 TESTE

Quando `currentIndex = 13` (ban2, ADC Azul):

- **Esperado:** `currentPlayer = "Bot7"` (ADC Azul, teamIndex=3)
- **Atual:** Provavelmente errado porque está pegando team2[3] ao invés de team1[3]

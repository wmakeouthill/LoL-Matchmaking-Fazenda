# 🔍 ANÁLISE COMPLETA DA ORDEM DO DRAFT

## 📋 DO DOCUMENTO "pergunas draft.md"

```
Fase 1 - Bans (6): Top Azul → Top Vermelho → Jungle Azul → Jungle Vermelho → Mid Azul → Mid Vermelho
Fase 2 - Picks (6): Azul pick 1 → Vermelho pick 2 → Azul pick 2 → Vermelho pick 1 → Azul pick 1
Fase 3 - Bans (4): ADC Azul → ADC Vermelho → Suporte Azul → Suporte Vermelho  
Fase 4 - Picks (4): Vermelho pick 2 → Azul pick 2 → Vermelho Last Pick
```

## 🧮 INTERPRETAÇÃO

### Fase 1 - Bans (ações 0-5): ✅ CLARO

```
0: Top Azul (team1[0])
1: Top Vermelho (team2[0])
2: Jungle Azul (team1[1])
3: Jungle Vermelho (team2[1])
4: Mid Azul (team1[2])
5: Mid Vermelho (team2[2])
```

### Fase 2 - Picks (ações 6-11): ⚠️ INTERPRETAÇÃO

"Azul pick 1 → Vermelho pick 2 → Azul pick 2 → Vermelho pick 1 → Azul pick 1"

Contagem: 1 + 2 + 2 + 1 = 6 picks ✅

```
6:  Azul pick 1 (Top) - team1[0]
7:  Vermelho pick 1 (Top) - team2[0]
8:  Vermelho pick 2 (Jungle) - team2[1]
9:  Azul pick 2 (Jungle) - team1[1]
10: Azul pick 3 (Mid) - team1[2]
11: Vermelho pick 3 (Mid) - team2[2]
```

### Fase 3 - Bans (ações 12-15): ✅ CLARO

```
12: ADC Azul (team1[3])
13: ADC Vermelho (team2[3])
14: Suporte Azul (team1[4])
15: Suporte Vermelho (team2[4])
```

### Fase 4 - Picks (ações 16-19): ⚠️ CONFUSO

"Vermelho pick 2 → Azul pick 2 → Vermelho Last Pick"

Contagem: 2 + 2 + 1 = 5 picks ❌ (mas devem ser 4!)

**POSSÍVEL INTERPRETAÇÃO 1:**

```
16: Vermelho pick 4 (ADC) - team2[3]
17: Azul pick 4 (ADC) - team1[3]
18: Azul pick 5 (Support - LAST PICK) - team1[4]
19: Vermelho pick 5 (Support) - team2[4]
```

**POSSÍVEL INTERPRETAÇÃO 2 (Vermelho last pick):**

```
16: Vermelho pick 4 (ADC) - team2[3]
17: Azul pick 4 (ADC) - team1[3]
18: Azul pick 5 (Support) - team1[4]
19: Vermelho pick 5 (Support - LAST PICK) - team2[4]
```

## 🎮 COMPARAÇÃO COM DRAFT PROFISSIONAL REAL DO LOL

**DRAFT RANQUEADO REAL:**

```
Bans 1-3:   B B B R R R (alternado)
Picks 1-3:  B R R B B R (1-2-2-1)
Bans 4-5:   R R B B     (Vermelho primeiro)
Picks 4-5:  R B B R     (Vermelho primeiro, Azul last pick)
```

**TRADUZINDO:**

```
0-5:   B R B R B R (bans fase 1)
6-11:  B R R B B R (picks fase 1)
12-15: R R B B     (bans fase 2) ⚠️ DOCUMENTO DIZ: B R B R
16-19: R B B R     (picks fase 2)
```

## ❌ DISCREPÂNCIA ENCONTRADA

**Documento diz Fase 3:**
"ADC Azul → ADC Vermelho → Suporte Azul → Suporte Vermelho"
= B R B R

**Draft profissional real:**
Red Ban 4 → Red Ban 5 → Blue Ban 4 → Blue Ban 5
= R R B B

## ✅ ORDEM CORRETA FINAL (seguindo draft profissional)

```java
// FASE 1 - BANS (0-5): Alternado Top/Jungle/Mid
case 0: team=1, lane=top;     // Top Azul
case 1: team=2, lane=top;     // Top Vermelho  
case 2: team=1, lane=jungle;  // Jungle Azul
case 3: team=2, lane=jungle;  // Jungle Vermelho
case 4: team=1, lane=mid;     // Mid Azul
case 5: team=2, lane=mid;     // Mid Vermelho

// FASE 2 - PICKS (6-11): 1-2-2-1 pattern
case 6:  team=1, lane=top;    // Azul Pick 1 (Top)
case 7:  team=2, lane=top;    // Vermelho Pick 1 (Top)
case 8:  team=2, lane=jungle; // Vermelho Pick 2 (Jungle)
case 9:  team=1, lane=jungle; // Azul Pick 2 (Jungle)
case 10: team=1, lane=mid;    // Azul Pick 3 (Mid)
case 11: team=2, lane=mid;    // Vermelho Pick 3 (Mid)

// FASE 3 - BANS (12-15): Vermelho primeiro (ADC/Suporte)
case 12: team=2, lane=adc;     // ADC Vermelho ⚠️ DOCUMENTO DIZ AZUL!
case 13: team=2, lane=support; // Suporte Vermelho ⚠️ DOCUMENTO DIZ ADC VERMELHO!
case 14: team=1, lane=adc;     // ADC Azul ⚠️ DOCUMENTO DIZ SUPORTE AZUL!
case 15: team=1, lane=support; // Suporte Azul ⚠️ DOCUMENTO DIZ SUPORTE VERMELHO!

// FASE 4 - PICKS (16-19): Vermelho primeiro, Azul last pick
case 16: team=2, lane=adc;     // Vermelho Pick 4 (ADC)
case 17: team=1, lane=adc;     // Azul Pick 4 (ADC)
case 18: team=1, lane=support; // Azul Pick 5 (Support - LAST PICK)
case 19: team=2, lane=support; // Vermelho Pick 5 (Support)
```

## 🤔 QUAL USAR?

**Opção A:** Seguir o DOCUMENTO literalmente (mas tem inconsistência na Fase 3)
**Opção B:** Seguir o DRAFT PROFISSIONAL REAL do LoL (R R B B na Fase 3)

**RECOMENDAÇÃO:** Perguntar ao usuário qual é a ordem EXATA da Fase 3!

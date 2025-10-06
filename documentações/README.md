# LoL Matchmaking - Spring Backend (Refactor)

Esqueleto inicial para portar o backend Node.js para Spring Boot.

## Objetivos

- Manter endpoints e payloads compatíveis.
- Reimplementar fila, matchmaking, draft, sync multi-backend.
- Melhorar transações, caching e observabilidade.

## Módulos já criados

- `Player` e `CustomMatch` entidades JPA.
- Repositórios básicos.
- WebSocket `/ws` handler inicial (identify_player + ping).
- Health endpoint `/api/health`.
- Flyway baseline (players, custom_matches, event_inbox).

## Próximos Passos

1. Adicionar entidades restantes (queue_players, settings, discord_lol_links, matches).
2. Implementar serviços: Champion/DataDragon cache, QueueService, MatchmakingService.
3. Replicar mensagens WebSocket adicionais (join_queue, leave_queue, accept/decline_match, cancel_* etc.).
4. Endpoints de campeões (usar ChampionService) e players (registro / current-details placeholder).
5. MultiBackendSync (EventInbox + REST controller /api/sync/event + ownership heartbeat + claim ownership).
6. Draft flow (confirm / change pick / confirmation-status) com persistência em pick_ban_data.
7. Discord integration (JDA) + endpoints/status.
8. Socket.IO signaling (avaliar netty-socketio vs WS puro) mantendo compatibilidade.
9. Tests (fila, ownership, idempotência de eventos, draft, champion cache).
10. Observabilidade extra (métricas custom via Actuator) e resiliente Riot API.

## Rodar

```bash
mvn spring-boot:run
```

Configurar variáveis ambiente ou use `application.yml` para apontar MySQL.

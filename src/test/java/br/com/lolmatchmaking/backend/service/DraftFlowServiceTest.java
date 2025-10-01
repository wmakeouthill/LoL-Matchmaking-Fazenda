package br.com.lolmatchmaking.backend.service;

import br.com.lolmatchmaking.backend.domain.entity.CustomMatch;
import br.com.lolmatchmaking.backend.domain.repository.CustomMatchRepository;
import br.com.lolmatchmaking.backend.websocket.SessionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DraftFlowServiceTest {

    private CustomMatchRepository customMatchRepository;
    private SessionRegistry sessionRegistry;
    private DataDragonService dataDragonService;
    private DraftFlowService draftFlowService;

    @BeforeEach
    void setup() {
        customMatchRepository = mock(CustomMatchRepository.class);
        sessionRegistry = mock(SessionRegistry.class);
        dataDragonService = mock(DataDragonService.class);

        // Mock para retornar lista vazia de campeões (evita NPE nos testes)
        when(dataDragonService.getAllChampions()).thenReturn(List.of());

        draftFlowService = new DraftFlowService(customMatchRepository, sessionRegistry, dataDragonService);
    }

    @Test
    void testProcessActionAndCompletion() {
        // given
        when(customMatchRepository.findById(1L))
                .thenReturn(Optional.of(CustomMatch.builder().id(1L).status("draft").build()));
        WebSocketSession ws = mock(WebSocketSession.class);
        when(sessionRegistry.all()).thenReturn(List.of(ws));
        var state = draftFlowService.startDraft(1L, List.of("A1", "A2", "A3", "A4", "A5"),
                List.of("B1", "B2", "B3", "B4", "B5"));

        // act: perform first action (ban)
        boolean ok = draftFlowService.processAction(1L, 0, "ChampX", "A1");

        // assert
        assertThat(ok).isTrue();
        assertThat(state.getActions().get(0).championId()).isEqualTo("ChampX");
        assertThat(state.getCurrentIndex()).isEqualTo(1);
    }

    @Test
    void testRejectWrongTurnPlayer() {
        when(customMatchRepository.findById(1L))
                .thenReturn(Optional.of(CustomMatch.builder().id(1L).status("draft").build()));
        WebSocketSession ws = mock(WebSocketSession.class);
        when(sessionRegistry.all()).thenReturn(List.of(ws));
        draftFlowService.startDraft(1L, List.of("A1", "A2", "A3", "A4", "A5"), List.of("B1", "B2", "B3", "B4", "B5"));

        // wrong team tries
        boolean ok = draftFlowService.processAction(1L, 0, "ChampX", "B1");
        assertThat(ok).isFalse();
    }

    @Test
    void testSkipOnTimeout() throws Exception {
        when(customMatchRepository.findById(2L))
                .thenReturn(Optional.of(CustomMatch.builder().id(2L).status("draft").build()));
        WebSocketSession ws = mock(WebSocketSession.class);
        when(sessionRegistry.all()).thenReturn(List.of(ws));
        var st = draftFlowService.startDraft(2L, List.of("A1", "A2", "A3", "A4", "A5"),
                List.of("B1", "B2", "B3", "B4", "B5"));

        // Force internal timer (simulate elapsed)
        // Reflection quick hack: set lastActionStartMs far in past
        var field = st.getClass().getDeclaredField("lastActionStartMs");
        field.setAccessible(true);
        field.set(st, System.currentTimeMillis() - 60000); // 60s ago

        draftFlowService.monitorActionTimeouts();

        assertThat(st.getActions().get(0).championId()).isEqualTo("SKIPPED");
        assertThat(st.getCurrentIndex()).isEqualTo(1);
    }

    @Test
    void testConfirmDraftFlowToGameReady() {
        // prepare repository returns for saving status transitions
        CustomMatch cm = CustomMatch.builder().id(3L).status("draft").build();
        when(customMatchRepository.findById(3L)).thenReturn(Optional.of(cm));
        WebSocketSession ws = mock(WebSocketSession.class);
        when(sessionRegistry.all()).thenReturn(List.of(ws));
        draftFlowService.startDraft(3L, List.of("A1", "A2", "A3", "A4", "A5"), List.of("B1", "B2", "B3", "B4", "B5"));

        // preencher todas as ações rapidamente (ignorar validação de time simplificando
        // usando nomes corretos sequencialmente)
        for (int i = 0; i < 20; i++) {
            var state = draftFlowService.getState(3L).orElseThrow();
            var action = state.getActions().get(i);
            String player = action.team() == 1 ? "A1" : "B1"; // usa primeiro jogador do time
            draftFlowService.processAction(3L, i, "Champ" + i, player);
        }

        // confirmar por 10 jogadores (simulate)
        for (String p : List.of("A1", "A2", "A3", "A4", "A5", "B1", "B2", "B3", "B4", "B5")) {
            draftFlowService.confirmDraft(3L, p);
        }

        // status do match deve ter sido atualizado para game_ready
        assertThat(cm.getStatus()).isEqualTo("game_ready");
    }
}

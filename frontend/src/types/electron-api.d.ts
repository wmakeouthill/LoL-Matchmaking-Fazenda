/**
 * ✅ DEFINIÇÕES DE TIPO: ElectronAPI para TypeScript
 *
 * Este arquivo define os tipos TypeScript para o electronAPI
 * exposto pelo preload.js do Electron.
 */
declare global {
  interface Window {
    electronAPI: {
      // === CORE ELECTRON API ===
      openExternal: (url: string) => Promise<void>;
      getVersion: () => string;
      getBackendApiUrl: () => string;
      isElectron: () => boolean;

      // === EVENT LISTENERS ===
      onMatchFound: (callback: (event: any, data: any) => void) => void;
      onDraftStarting: (callback: (event: any, data: any) => void) => void;
      onDraftStarted: (callback: (event: any, data: any) => void) => void;
      onGameInProgress: (callback: (event: any, data: any) => void) => void;
      onMatchCancelled: (callback: (event: any, data: any) => void) => void;
      onAcceptanceTimer: (callback: (event: any, data: any) => void) => void;
      onAcceptanceProgress: (callback: (event: any, data: any) => void) => void;

      // === DRAFT EVENTS ===
      onDraftTimer: (callback: (event: any, data: any) => void) => void;
      onDraftUpdate: (callback: (event: any, data: any) => void) => void;
      onDraftUpdated: (callback: (event: any, data: any) => void) => void;
      onPickChampion: (callback: (event: any, data: any) => void) => void;
      onBanChampion: (callback: (event: any, data: any) => void) => void;
      onDraftConfirmed: (callback: (event: any, data: any) => void) => void;
      onDraftConfirmationUpdate: (callback: (event: any, data: any) => void) => void;

      // === GAME EVENTS ===
      onGameStarted: (callback: (event: any, data: any) => void) => void;
      onWinnerModal: (callback: (event: any, data: any) => void) => void;
      onVoteWinner: (callback: (event: any, data: any) => void) => void;
      onMatchVoteProgress: (callback: (event: any, data: any) => void) => void;
      onMatchVoteUpdate: (callback: (event: any, data: any) => void) => void;

      // === SPECTATOR EVENTS ===
      onSpectatorMuted: (callback: (event: any, data: any) => void) => void;
      onSpectatorUnmuted: (callback: (event: any, data: any) => void) => void;

      // === CANCELLATION EVENTS ===
      onMatchCancelled: (callback: (event: any, data: any) => void) => void;
      onDraftCancelled: (callback: (event: any, data: any) => void) => void;
      onGameCancelled: (callback: (event: any, data: any) => void) => void;

      // === QUEUE EVENTS ===
      onQueueStatus: (callback: (event: any, data: any) => void) => void;
      onQueueUpdate: (callback: (event: any, data: any) => void) => void;

      // === CONNECTION EVENTS ===
      onBackendConnection: (callback: (event: any, data: any) => void) => void;
      onPlayerSessionUpdate: (callback: (event: any, data: any) => void) => void;

      // === LCU API ===
      lcu: {
        request: (pathname: string, method?: string, body?: any) => Promise<any>;
        getCurrentSummoner: () => Promise<any>;
        getCurrentRanked: () => Promise<any>;
        getGameflowPhase: () => Promise<any>;
        getSession: () => Promise<any>;
        getMatchHistory: () => Promise<any>;
      };

      // === PLAYER IDENTIFICATION ===
      identifyPlayer: (payload: any) => Promise<{ success: boolean; error?: string }>;
      getLCULockfileInfo: () => any;

      // === STORAGE API ===
      storage: {
        savePlayerData: (summonerName: string, data: any) => Promise<{ success: boolean; error?: string }>;
        loadPlayerData: (summonerName: string) => Promise<any>;
        clearPlayerData: (summonerName: string) => Promise<{ success: boolean; error?: string }>;
        listPlayers: () => Promise<string[]>;
      };

      // === GAME ACTIONS (TODAS VALIDADAS VIA ELECTRON) ===
      gameActionAcceptMatch: (matchData: any) => Promise<boolean>;
      gameActionDeclineMatch: (matchData: any) => Promise<boolean>;
      gameActionCancelMatch: (matchData: any) => Promise<boolean>;
      gameActionJoinQueue: (queueData: any) => Promise<boolean>;
      gameActionLeaveQueue: (queueData: any) => Promise<boolean>;
      gameActionPickChampion: (pickData: any) => Promise<boolean>;
      gameActionBanChampion: (banData: any) => Promise<boolean>;
      gameActionSelectLane: (laneData: any) => Promise<boolean>;
      gameActionConfirmDraft: (draftData: any) => Promise<boolean>;
      gameActionVoteWinner: (voteData: any) => Promise<boolean>;
      gameActionReportResult: (resultData: any) => Promise<boolean>;
      gameActionSurrender: (surrenderData: any) => Promise<boolean>;
      gameActionMuteSpectator: (muteData: any) => Promise<boolean>;
      gameActionUnmuteSpectator: (unmuteData: any) => Promise<boolean>;
      gameActionPing: (pingData: any) => Promise<boolean>;
      gameActionHeartbeat: (heartbeatData: any) => Promise<boolean>;
    };
  }
}

export { };

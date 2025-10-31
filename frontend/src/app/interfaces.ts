// Shared interfaces for League of Legends Matchmaking App

export interface Player {
  id: number;
  summonerName: string;
  displayName?: string; // Nome completo formatado (gameName#tagLine)
  gameName?: string;  // Nome do Riot ID (sem tag)
  summonerId?: string;
  puuid?: string;
  tagLine?: string;
  profileIconId?: string | number;
  summonerLevel?: number;
  currentMMR: number;
  mmr?: number; // Alias para currentMMR
  customLp?: number; // LP customizado
  region: string;
  rank?: {
    tier: string;
    rank: string;
    display: string;
    lp?: number;
  };
  wins?: number;
  losses?: number;
  lastMatchDate?: Date;
  registeredAt?: string; // Data de registro do jogador
  updatedAt?: string; // Data da última atualização
  rankedData?: {
    soloQueue?: any;
    flexQueue?: any;
  };
}

export interface QueueStatus {
  playersInQueue: number;
  averageWaitTime: number;
  estimatedMatchTime?: number;
  isActive?: boolean;
  yourPosition?: number;
  playersInQueueList?: QueuedPlayerInfo[]; // Lista dos jogadores na fila
  recentActivities?: QueueActivity[]; // Atividades recentes
  isCurrentPlayerInQueue?: boolean; // Indica se o usuário atual está na fila (calculado no backend)
}

export interface QueuedPlayerInfo {
  summonerName: string;
  tagLine?: string;
  primaryLane: string;
  secondaryLane: string;
  primaryLaneDisplay: string;
  secondaryLaneDisplay: string;
  mmr: number;
  queuePosition: number;
  joinTime: string; // ISO string
  isCurrentPlayer?: boolean; // ✅ NOVO: Indica se este é o jogador atual
}

export interface QueueActivity {
  id: string;
  timestamp: Date;
  type: 'player_joined' | 'player_left' | 'match_created' | 'system_update' | 'queue_cleared';
  message: string;
  playerName?: string;
  playerTag?: string;
  lane?: string;
}

export interface Lane {
  id: string;
  name: string;
  icon: string;
  description: string;
}

export interface QueuePreferences {
  primaryLane: string;
  secondaryLane: string;
}

export interface LCUStatus {
  isConnected: boolean;
  summoner?: any;
  gameflowPhase?: string;
  lobby?: any;
}

// ✅ ESTRUTURA UNIFICADA: Backend envia estrutura hierárquica moderna
export interface UnifiedTeamPlayer {
  playerId: number;
  summonerName: string;
  gameName?: string;
  tagLine?: string;
  mmr: number;
  customLp?: number;
  profileIconId?: number;
  teamIndex: number;
  assignedLane: string;
  primaryLane?: string;
  secondaryLane?: string;
  isAutofill?: boolean;
  laneBadge?: 'primary' | 'secondary' | 'autofill';
  acceptanceStatus?: number | 'pending' | 'accepted' | 'declined' | 'timeout'; // Backend: number, Frontend UI: string
  hasAccepted?: boolean;
  championId?: number;
  championName?: string;

  // ✅ Campos UI adicionados dinamicamente no frontend
  id?: number; // Alias para playerId
  riotIdGameName?: string; // Alias para gameName
  riotIdTagline?: string; // Alias para tagLine
  acceptedAt?: string; // ISO timestamp quando aceitou
  isCurrentUser?: boolean; // Se é o usuário logado via LCU
}

export interface UnifiedTeam {
  name: string;
  side: 'blue' | 'red';
  players: UnifiedTeamPlayer[];
}

export interface UnifiedTeams {
  blue: UnifiedTeam;
  red: UnifiedTeam;
}

export interface MatchFound {
  matchId: number;
  teams: UnifiedTeams; // ✅ NOVA estrutura hierárquica
  team1?: any[]; // ⚠️ Mantido para compatibilidade temporária
  team2?: any[]; // ⚠️ Mantido para compatibilidade temporária
  phase: 'match_found' | 'draft' | 'in_game' | 'post_game';
  averageMmrTeam1?: number;
  averageMmrTeam2?: number;
  timeoutSeconds?: number;
  acceptedCount?: number;
  totalPlayers?: number;

  // ✅ Campos UI adicionados dinamicamente no frontend
  playerSide?: 'blue' | 'red'; // Qual time o jogador está
  acceptanceTimer?: number; // Timer de aceitação (segundos)
  acceptTimeout?: number; // Timeout de aceitação (compatibilidade)
  averageMMR?: { // Estrutura legada para compatibilidade
    yourTeam: number;
    enemyTeam: number;
  };
  teammates?: any[]; // Legado (usar teams.blue/red.players)
  enemies?: any[]; // Legado (usar teams.blue/red.players)
  estimatedGameDuration?: number;
}

export interface Notification {
  id: string;
  type: 'success' | 'error' | 'warning' | 'info';
  title: string;
  message: string;
  timestamp: Date;
  isRead?: boolean;
  isVisible?: boolean;
  isHiding?: boolean;
  autoHideTimeout?: number;
}

export interface CurrentGame {
  session: any;
  phase: string;
  isInGame: boolean;
}

export interface Match {
  id: number;
  createdAt?: Date;
  timestamp?: number;
  duration: number;
  teams?: UnifiedTeams; // ✅ NOVA estrutura unificada
  team1?: any[]; // ⚠️ Mantido para compatibilidade com histórico
  team2?: any[]; // ⚠️ Mantido para compatibilidade com histórico
  winner?: number;
  averageMMR1?: number;
  averageMMR2?: number;
  isVictory?: boolean;
  mmrChange?: number;
  gameMode?: string;

  // Propriedades adicionais para exibição no dashboard
  champion?: string;
  playerName?: string;
  kda?: string;

  // Dados expandidos da Riot API
  participants?: any[]; // Todos os 10 jogadores
  riotTeams?: any[]; // Dados dos times da Riot API (team100/team200)
  gameVersion?: string;
  mapId?: number;
  // Campos específicos para partidas customizadas
  player_lp_change?: number; // LP ganho/perdido pelo jogador
  player_mmr_change?: number; // MMR ganho/perdido pelo jogador
  player_team?: number; // Em qual time o jogador estava (1 ou 2)
  player_won?: boolean; // Se o jogador ganhou a partida
  lp_changes?: any; // Objeto com LP changes de todos os jogadores
  participants_data?: any[]; // Dados reais dos participantes (KDA, itens, etc.)

  playerStats?: {
    champion: string;
    kills: number;
    deaths: number;
    assists: number;
    mmrChange: number;
    isWin: boolean;
    championLevel?: number;
    lane?: string; // Adicionando propriedade para a lane
    firstBloodKill?: boolean; // Indica se o jogador fez o primeiro sangue
    doubleKills?: number;
    tripleKills?: number;
    quadraKills?: number;
    pentaKills?: number;
    items?: number[];
    lpChange?: number;
    // Dados expandidos do jogador
    goldEarned?: number;
    totalDamageDealt?: number;
    totalDamageDealtToChampions?: number;
    totalDamageTaken?: number;
    totalMinionsKilled?: number;
    neutralMinionsKilled?: number;
    wardsPlaced?: number;
    wardsKilled?: number;
    visionScore?: number;
    summoner1Id?: number;
    summoner2Id?: number;
    perks?: any; // Runas
  };
}

export interface RefreshPlayerResponse {
  success: boolean;
  player: Player | null;
  error?: string;
}

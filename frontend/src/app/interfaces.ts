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
  autoAccept?: boolean;
}

export interface LCUStatus {
  isConnected: boolean;
  summoner?: any;
  gameflowPhase?: string;
  lobby?: any;
}

export interface MatchFound {
  matchId: number;
  team1: any[];
  team2: any[];
  yourTeam: number;
  averageMMR1: number;
  averageMMR2: number;
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
  team1?: any[];
  team2?: any[];
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
  teams?: any[]; // Dados dos times
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

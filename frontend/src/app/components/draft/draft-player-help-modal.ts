import { Component, Input, Output, EventEmitter, OnInit, OnChanges, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../../services/api';
import { ChampionService } from '../../services/champion.service';

interface ChampionDraftStats {
  championId: number;
  championName: string;
  gamesPlayed: number;
  wins: number;
  losses: number;
  winRate: number;
}

interface ChampionMastery {
  championId: number;
  championName: string;
  championLevel: number;
  championPoints: number;
  championPointsSinceLastLevel: number;
  championPointsUntilNextLevel: number;
}

interface ChampionRanked {
  championId: number;
  championName: string;
  gamesPlayed: number;
  wins: number;
  losses: number;
  winRate: number;
  tier: string;
}

interface PlayerChampionData {
  draftStats: ChampionDraftStats[];
  masteryChampions: ChampionMastery[];
  rankedChampions: ChampionRanked[];
}

@Component({
  selector: 'app-draft-player-help-modal',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './draft-player-help-modal.html',
  styleUrls: ['./draft-player-help-modal.css']
})
export class DraftPlayerHelpModalComponent implements OnInit, OnChanges {
  @Input() isVisible: boolean = false;
  @Input() playerName: string = '';
  @Input() playerSummonerName: string = '';
  @Output() closed = new EventEmitter<void>();

  playerData: PlayerChampionData | null = null;
  loading: boolean = false;
  errorMessage: string = '';
  activeTab: 'draft' | 'mastery' | 'ranked' = 'draft';

  constructor(
    private readonly http: HttpClient,
    private readonly apiService: ApiService,
    private readonly championService: ChampionService
  ) { }

  ngOnInit(): void {
    console.log('🔵 [PlayerHelpModal] Component initialized');
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['isVisible'] && this.isVisible && this.playerSummonerName) {
      console.log('🔵 [PlayerHelpModal] Modal opened for:', this.playerSummonerName);
      this.loadPlayerData();
    }

    if (changes['playerSummonerName'] && this.playerSummonerName && this.isVisible) {
      console.log('🔵 [PlayerHelpModal] Player changed:', this.playerSummonerName);
      this.loadPlayerData();
    }
  }

  async loadPlayerData(): Promise<void> {
    this.loading = true;
    this.errorMessage = '';
    this.playerData = null;

    try {
      console.log('📡 [PlayerHelpModal] Loading stats from database for:', this.playerSummonerName);

      // ✅ NOVO: Endpoint que busca direto do banco
      const baseUrl = this.apiService.getBaseUrl();
      const response: any = await firstValueFrom(
        this.http.get(`${baseUrl}/player/stats/${encodeURIComponent(this.playerSummonerName)}`, {
          headers: this.apiService.getAuthenticatedHeaders()
        })
      );

      console.log('✅ [PlayerHelpModal] Stats loaded from database:', response);

      if (!response.success) {
        throw new Error(response.error || 'Erro ao buscar dados');
      }

      this.playerData = {
        draftStats: this.parseJsonField(response.playerStatsDraft) || [],
        masteryChampions: this.parseJsonField(response.masteryChampions) || [],
        rankedChampions: this.parseJsonField(response.rankedChampions) || []
      };

      // ✅ Normalizar nomes de campeões usando ChampionService
      this.normalizeChampionNames();

      // Ordenar dados
      this.sortData();

    } catch (error: any) {
      console.error('❌ [PlayerHelpModal] Error loading data:', error);
      this.errorMessage = error.error?.error || error.message || 'Erro ao carregar dados do jogador';
    } finally {
      this.loading = false;
    }
  }

  private parseJsonField(field: any): any[] {
    if (!field) return [];
    if (typeof field === 'string') {
      try {
        return JSON.parse(field);
      } catch {
        return [];
      }
    }
    if (Array.isArray(field)) return field;
    return [];
  }

  private sortData(): void {
    if (!this.playerData) return;

    // Draft: ordenar por win rate (desc), depois por games played (desc)
    this.playerData.draftStats.sort((a, b) => {
      if (b.winRate !== a.winRate) return b.winRate - a.winRate;
      return b.gamesPlayed - a.gamesPlayed;
    });

    // Mastery: já vem ordenado por championPoints (desc)
    this.playerData.masteryChampions.sort((a, b) => b.championPoints - a.championPoints);

    // Ranked: ordenar por games played (desc)
    this.playerData.rankedChampions.sort((a, b) => b.gamesPlayed - a.gamesPlayed);
  }

  /**
   * ✅ NOVO: Normaliza nomes de campeões usando ChampionService
   * Substitui "Champion 20" pelo nome real do campeão
   */
  private normalizeChampionNames(): void {
    if (!this.playerData) return;

    // Normalizar Draft Stats
    this.playerData.draftStats.forEach(champ => {
      if (champ.championName.startsWith('Champion ')) {
        champ.championName = this.championService.getChampionName(champ.championId);
      }
    });

    // Normalizar Mastery Champions
    this.playerData.masteryChampions.forEach(champ => {
      if (champ.championName.startsWith('Champion ')) {
        champ.championName = this.championService.getChampionName(champ.championId);
      }
    });

    // Normalizar Ranked Champions
    this.playerData.rankedChampions.forEach(champ => {
      if (champ.championName.startsWith('Champion ')) {
        champ.championName = this.championService.getChampionName(champ.championId);
      }
    });
  }

  close(): void {
    console.log('🔵 [PlayerHelpModal] Closing modal');
    this.closed.emit();
  }

  setTab(tab: 'draft' | 'mastery' | 'ranked'): void {
    this.activeTab = tab;
  }

  getChampionImageUrl(championId: number): string {
    return this.championService.getChampionImageUrl(championId);
  }

  formatNumber(num: number): string {
    return num.toLocaleString('pt-BR');
  }

  formatWinRate(winRate: number): string {
    return `${winRate.toFixed(1)}%`;
  }

  getMasteryColor(level: number): string {
    if (level >= 7) return '#f59e0b'; // Laranja/Dourado
    if (level >= 5) return '#a855f7'; // Roxo
    if (level >= 4) return '#3b82f6'; // Azul
    return '#6b7280'; // Cinza
  }

  getTierColor(tier: string): string {
    if (tier.includes('RANKED_SOLO')) return '#3b82f6';
    if (tier.includes('RANKED_FLEX')) return '#8b5cf6';
    return '#6b7280';
  }
}

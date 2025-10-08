import { Component, Input, Output, EventEmitter, OnInit, OnChanges, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../../services/api';

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

    private readonly baseUrl = 'http://localhost:8080/api';

    playerData: PlayerChampionData | null = null;
    loading: boolean = false;
    errorMessage: string = '';
    activeTab: 'draft' | 'mastery' | 'ranked' = 'draft';

    constructor(
        private readonly http: HttpClient,
        private readonly apiService: ApiService
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
            console.log('📡 [PlayerHelpModal] Loading data for:', this.playerSummonerName);

            const response: any = await firstValueFrom(
                this.http.get(`${this.baseUrl}/player/summoner/${encodeURIComponent(this.playerSummonerName)}`, {
                    headers: this.apiService.getAuthenticatedHeaders()
                })
            );

            console.log('✅ [PlayerHelpModal] Data loaded:', response);

            this.playerData = {
                draftStats: this.parseJsonField(response.playerStatsDraft) || [],
                masteryChampions: this.parseJsonField(response.masteryChampions) || [],
                rankedChampions: this.parseJsonField(response.rankedChampions) || []
            };

            // Ordenar dados
            this.sortData();

        } catch (error: any) {
            console.error('❌ [PlayerHelpModal] Error loading data:', error);
            this.errorMessage = 'Erro ao carregar dados do jogador';
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

    close(): void {
        console.log('🔵 [PlayerHelpModal] Closing modal');
        this.closed.emit();
    }

    setTab(tab: 'draft' | 'mastery' | 'ranked'): void {
        this.activeTab = tab;
    }

    getChampionImageUrl(championId: number): string {
        // Placeholder - ajustar para o serviço real de imagens
        return `https://ddragon.leagueoflegends.com/cdn/13.24.1/img/champion/champion_${championId}.png`;
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

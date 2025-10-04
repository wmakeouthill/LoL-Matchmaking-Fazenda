import { Component, EventEmitter, Input, OnInit, OnDestroy, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Subscription } from 'rxjs';
import { ApiService } from '../../services/api';
import { getChampionKeyById, getChampionIconUrl } from '../../utils/champion-data';

interface CustomMatch {
    gameId: number;
    gameCreation: number;
    gameDuration: number;
    participants: any[];
    teams: any[];
    gameMode: string;
    queueId: number;
}

interface MatchOption {
    match: CustomMatch;
    matchIndex: number;
    winningTeam: 'blue' | 'red' | null;
    formattedDate: string;
    formattedDuration: string;
    ourPlayers: {
        blue: any[];
        red: any[];
    };
    voteCount?: number; // Contador de votos para esta partida
}

@Component({
    selector: 'app-winner-confirmation-modal',
    standalone: true,
    imports: [CommonModule],
    templateUrl: './winner-confirmation-modal.component.html',
    styleUrls: ['./winner-confirmation-modal.component.scss']
})
export class WinnerConfirmationModalComponent implements OnInit, OnDestroy {
    @Input() customMatches: CustomMatch[] = [];
    @Input() currentPlayers: any[] = []; // Jogadores da partida atual
    @Input() matchId: number | null = null; // ID da partida customizada para votação
    @Output() onConfirm = new EventEmitter<{ match: CustomMatch, winner: 'blue' | 'red' }>();
    @Output() onCancel = new EventEmitter<void>();

    matchOptions: MatchOption[] = [];
    selectedMatchIndex: number | null = null;
    voteCounts: Map<number, number> = new Map(); // lcuGameId -> voteCount
    myVotedGameId: number | null = null; // LCU game que o usuário votou
    private wsSubscription?: Subscription;

    constructor(private apiService: ApiService) { }

    ngOnInit() {
        this.processMatches();
        this.setupWebSocketListeners();
        this.loadInitialVotes();
    }

    ngOnDestroy() {
        if (this.wsSubscription) {
            this.wsSubscription.unsubscribe();
        }
    }

    private setupWebSocketListeners() {
        this.wsSubscription = this.apiService.onWebSocketMessage().subscribe({
            next: (message) => {
                const { type, data } = message;

                if (type === 'match_vote_update') {
                    this.handleVoteUpdate(data);
                } else if (type === 'match_linked') {
                    this.handleMatchLinked(data);
                }
            },
            error: (error) => {
                console.error('❌ [WinnerModal] Erro no WebSocket:', error);
            }
        });
    }

    private async loadInitialVotes() {
        if (!this.matchId) return;

        try {
            // Carregar votos atuais do backend
            const votes = await this.apiService.getMatchVotes(this.matchId).toPromise();
            if (votes) {
                this.voteCounts = new Map(Object.entries(votes).map(([k, v]) => [Number(k), Number(v)]));
                this.updateVoteCountsInOptions();
            }
        } catch (error) {
            console.error('❌ [WinnerModal] Erro ao carregar votos:', error);
        }
    }

    private handleVoteUpdate(data: any) {
        console.log('🗳️ [WinnerModal] Atualização de votos recebida:', data);

        if (data.matchId !== this.matchId) return;

        // Atualizar contadores de votos
        if (data.voteCounts) {
            this.voteCounts = new Map(Object.entries(data.voteCounts).map(([k, v]) => [Number(k), Number(v)]));
            this.updateVoteCountsInOptions();
        }
    }

    private handleMatchLinked(data: any) {
        console.log('🔗 [WinnerModal] Partida vinculada:', data);

        if (data.matchId !== this.matchId) return;

        // Fechar modal automaticamente após vinculação
        setTimeout(() => {
            alert(`✅ Partida vinculada automaticamente! Vencedor: Time ${data.winner === 'blue' ? 'Azul' : 'Vermelho'}`);
            this.cancel();
        }, 1000);
    }

    private updateVoteCountsInOptions() {
        this.matchOptions.forEach(option => {
            const voteCount = this.voteCounts.get(option.match.gameId) || 0;
            option.voteCount = voteCount;
        });
    }

    async selectMatch(index: number) {
        const previousIndex = this.selectedMatchIndex;
        this.selectedMatchIndex = index;

        if (!this.matchId) {
            console.warn('⚠️ [WinnerModal] Match ID não fornecido, votação desabilitada');
            return;
        }

        const selectedOption = this.matchOptions[index];
        const lcuGameId = selectedOption.match.gameId;

        try {
            // Enviar voto ao backend
            await this.apiService.voteForMatch(this.matchId, lcuGameId).toPromise();
            this.myVotedGameId = lcuGameId;
            console.log('✅ [WinnerModal] Voto registrado para LCU game:', lcuGameId);
        } catch (error) {
            console.error('❌ [WinnerModal] Erro ao votar:', error);
            this.selectedMatchIndex = previousIndex; // Reverter seleção
            alert('Erro ao registrar voto. Tente novamente.');
        }
    }

    private processMatches() {
        this.matchOptions = this.customMatches.map((match, index) => {
            const winningTeam = this.getWinningTeam(match);
            const ourPlayers = this.identifyOurPlayers(match);

            return {
                match,
                matchIndex: index,
                winningTeam,
                formattedDate: this.formatDate(match.gameCreation),
                formattedDuration: this.formatDuration(match.gameDuration),
                ourPlayers
            };
        });
    }

    private getWinningTeam(match: CustomMatch): 'blue' | 'red' | null {
        if (!match.teams || match.teams.length < 2) return null;

        const blueTeam = match.teams.find(t => t.teamId === 100);
        const redTeam = match.teams.find(t => t.teamId === 200);

        if (blueTeam?.win === 'Win' || blueTeam?.win === true) return 'blue';
        if (redTeam?.win === 'Win' || redTeam?.win === true) return 'red';

        return null;
    }

    private identifyOurPlayers(match: CustomMatch) {
        const blue: any[] = [];
        const red: any[] = [];

        if (!match.participants) return { blue, red };

        const currentPlayerNames = this.currentPlayers.map(p =>
            (p.summonerName || p.name || '').toLowerCase()
        );

        match.participants.forEach(participant => {
            const participantName = (participant.summonerName || participant.riotIdGameName || '').toLowerCase();
            const isOurPlayer = currentPlayerNames.includes(participantName);

            if (isOurPlayer) {
                if (participant.teamId === 100) {
                    blue.push(participant);
                } else if (participant.teamId === 200) {
                    red.push(participant);
                }
            }
        });

        return { blue, red };
    }

    private formatDate(timestamp: number): string {
        const date = new Date(timestamp);
        const now = new Date();
        const diffMs = now.getTime() - date.getTime();
        const diffMins = Math.floor(diffMs / 60000);

        if (diffMins < 60) {
            return `${diffMins} minutos atrás`;
        } else if (diffMins < 1440) { // 24 hours
            const hours = Math.floor(diffMins / 60);
            return `${hours} hora${hours > 1 ? 's' : ''} atrás`;
        } else {
            return date.toLocaleDateString('pt-BR', {
                day: '2-digit',
                month: '2-digit',
                hour: '2-digit',
                minute: '2-digit'
            });
        }
    }

    private formatDuration(seconds: number): string {
        const minutes = Math.floor(seconds / 60);
        const secs = seconds % 60;
        return `${minutes}:${secs.toString().padStart(2, '0')}`;
    }

    confirmSelection() {
        if (this.selectedMatchIndex === null) {
            alert('Por favor, selecione uma partida primeiro.');
            return;
        }

        const selectedOption = this.matchOptions[this.selectedMatchIndex];
        if (!selectedOption.winningTeam) {
            alert('A partida selecionada não possui um vencedor identificado.');
            return;
        }

        this.onConfirm.emit({
            match: selectedOption.match,
            winner: selectedOption.winningTeam
        });
    }

    cancel() {
        this.onCancel.emit();
    }

    getChampionName(championId: number): string {
        const key = getChampionKeyById(championId);

        // Convert key back to readable name (e.g., "MissFortune" -> "Miss Fortune")
        return key
            .replace(/([A-Z])/g, ' $1') // Add space before capitals
            .trim() // Remove leading space
            .replace(/\s+/g, ' '); // Normalize spaces
    }

    getPlayersByTeam(match: CustomMatch, teamId: number) {
        if (!match.participants) return [];
        return match.participants.filter(p => p.teamId === teamId);
    }

    isPlayerInOurMatch(participant: any, teamColor: 'blue' | 'red'): boolean {
        const option = this.matchOptions[this.selectedMatchIndex || 0];
        if (!option) return false;

        const ourPlayersInTeam = option.ourPlayers[teamColor];
        const participantName = (participant.summonerName || participant.riotIdGameName || '').toLowerCase();

        return ourPlayersInTeam.some(p =>
            (p.summonerName || p.riotIdGameName || '').toLowerCase() === participantName
        );
    }

    getChampionIconUrl(championId: number): string {
        const key = getChampionKeyById(championId);
        return `https://ddragon.leagueoflegends.com/cdn/14.1.1/img/champion/${key}.png`;
    }

    getChampionKeyById(championId: number): string {
        return getChampionKeyById(championId);
    }

    getItemIconUrl(itemId: number): string {
        return `https://ddragon.leagueoflegends.com/cdn/14.1.1/img/item/${itemId}.png`;
    }

    getPlayerItems(player: any): number[] {
        return [
            player.item0 || 0,
            player.item1 || 0,
            player.item2 || 0,
            player.item3 || 0,
            player.item4 || 0,
            player.item5 || 0,
            player.item6 || 0 // Trinket
        ];
    }

    getKDAFormattedRatio(player: any): string {
        if (!player.deaths || player.deaths === 0) {
            return 'Perfect';
        }
        const kda = (player.kills + player.assists) / player.deaths;
        return kda.toFixed(2);
    }

    formatNumber(num: number): string {
        if (!num) return '0';
        if (num >= 1000) {
            return (num / 1000).toFixed(1) + 'k';
        }
        return num.toString();
    }
}

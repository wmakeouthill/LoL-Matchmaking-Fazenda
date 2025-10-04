import { Component, EventEmitter, Input, OnInit, Output } from '@angular/core';
import { CommonModule } from '@angular/common';

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
}

@Component({
    selector: 'app-winner-confirmation-modal',
    standalone: true,
    imports: [CommonModule],
    templateUrl: './winner-confirmation-modal.component.html',
    styleUrls: ['./winner-confirmation-modal.component.scss']
})
export class WinnerConfirmationModalComponent implements OnInit {
    @Input() customMatches: CustomMatch[] = [];
    @Input() currentPlayers: any[] = []; // Jogadores da partida atual
    @Output() onConfirm = new EventEmitter<{ match: CustomMatch, winner: 'blue' | 'red' }>();
    @Output() onCancel = new EventEmitter<void>();

    matchOptions: MatchOption[] = [];
    selectedMatchIndex: number | null = null;

    ngOnInit() {
        this.processMatches();
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

    selectMatch(index: number) {
        this.selectedMatchIndex = index;
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
        // Placeholder - será preenchido com dados reais
        return `Champion ${championId}`;
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
}

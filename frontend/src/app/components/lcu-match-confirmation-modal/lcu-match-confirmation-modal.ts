import { Component, Input, Output, EventEmitter, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';

interface Champion {
  id: string;
  name: string;
  image?: string;
}

interface PickBanData {
  team1Picks: Array<{
    playerName: string;
    champion: Champion;
    playerLane: string;
  }>;
  team2Picks: Array<{
    playerName: string;
    champion: Champion;
    playerLane: string;
  }>;
  team1Bans: Array<{
    playerName: string;
    champion: Champion;
  }>;
  team2Bans: Array<{
    playerName: string;
    champion: Champion;
  }>;
}

interface ComparisonData {
  customMatch: {
    id: number;
    players: string[];
    picks: Champion[];
    bans: Champion[];
    pickBanData: PickBanData;
  };
  lcuMatch: {
    gameId: number;
    participants: any[];
    teams: any[];
    gameCreation: number;
    gameDuration: number;
  };
  similarity: {
    playerMatch: number;
    championMatch: number;
    overall: number;
    details: string[];
  };
}

@Component({
  selector: 'app-lcu-match-confirmation-modal',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './lcu-match-confirmation-modal.html',
  styleUrl: './lcu-match-confirmation-modal.scss'
})
export class LcuMatchConfirmationModalComponent implements OnInit {
  @Input() isVisible: boolean = false;
  @Input() comparisonData: ComparisonData | null = null;
  @Input() isLeader: boolean = false;

  @Output() confirmed = new EventEmitter<ComparisonData>();
  @Output() rejected = new EventEmitter<void>();
  @Output() cancelled = new EventEmitter<void>();

  ngOnInit() {
    console.log('🔍 [LCU Confirmation Modal] Inicializado:', {
      isVisible: this.isVisible,
      hasComparisonData: !!this.comparisonData,
      isLeader: this.isLeader
    });
  }

  onConfirm() {
    if (this.isLeader && this.comparisonData) {
      console.log('✅ [LCU Confirmation Modal] Partida confirmada pelo líder');
      this.confirmed.emit(this.comparisonData);
    }
  }

  onReject() {
    if (this.isLeader) {
      console.log('❌ [LCU Confirmation Modal] Partida rejeitada pelo líder');
      this.rejected.emit();
    }
  }

  onCancel() {
    console.log('↩️ [LCU Confirmation Modal] Modal cancelado');
    this.cancelled.emit();
  }

  onOverlayClick(event: Event) {
    // Fechar modal apenas se clicou no overlay, não no conteúdo
    if (event.target === event.currentTarget) {
      this.onCancel();
    }
  }

  // Helper methods
  getChampionImageUrl(championId: string): string {
    return `https://ddragon.leagueoflegends.com/cdn/15.14.1/img/champion/${championId}.png`;
  }

  getChampionImageById(championId: number): string {
    // Você pode implementar um mapeamento ID -> nome do campeão aqui
    return `https://ddragon.leagueoflegends.com/cdn/15.14.1/img/champion/champion_${championId}.png`;
  }

  getChampionNameById(championId: number): string {
    // Implementar mapeamento ID -> nome
    return `Champion ${championId}`;
  }

  getParticipantName(participant: any): string {
    return participant.summonerName || participant.gameName || `Summoner ${participant.participantId}`;
  }

  getLCUWinner(): 'blue' | 'red' | null {
    if (!this.comparisonData?.lcuMatch.teams) return null;

    const winningTeam = this.comparisonData.lcuMatch.teams.find(team =>
      team.win === "Win" || team.win === true
    );

    if (!winningTeam) return null;
    return winningTeam.teamId === 100 ? 'blue' : 'red';
  }

  getLCUTeamParticipants(teamId: number): any[] {
    if (!this.comparisonData?.lcuMatch.participants) return [];

    return this.comparisonData.lcuMatch.participants.filter(participant =>
      participant.teamId === teamId
    );
  }

  formatDate(timestamp: number): string {
    return new Date(timestamp).toLocaleString('pt-BR');
  }

  formatDuration(duration: number): string {
    const minutes = Math.floor(duration / 60);
    const seconds = duration % 60;
    return `${minutes}:${seconds.toString().padStart(2, '0')}`;
  }
}

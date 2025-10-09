import { Component, Input, Output, EventEmitter, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { ApiService } from '../../services/api';

interface SpectatorDTO {
  discordId: string;
  discordUsername: string;
  channelName: string; // "Blue Team" ou "Red Team"
  isMuted: boolean;
}

interface SpectatorResponse {
  success: boolean;
  spectators: SpectatorDTO[];
  count: number;
  timestamp: number;
}

interface MuteResponse {
  success: boolean;
  message: string;
  timestamp: number;
}

@Component({
  selector: 'app-spectators-modal',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './spectators-modal.component.html',
  styleUrls: ['./spectators-modal.component.css']
})
export class SpectatorsModalComponent implements OnInit {
  @Input() matchId!: number;
  @Input() summonerName!: string;
  @Output() closed = new EventEmitter<void>();

  spectators: SpectatorDTO[] = [];
  loading = false;
  error: string | null = null;
  private readonly baseUrl: string;

  constructor(
    private http: HttpClient,
    private apiService: ApiService
  ) {
    this.baseUrl = this.apiService.getBaseUrl();
  }

  ngOnInit(): void {
    this.loadSpectators();
    // Auto-refresh a cada 5 segundos
    setInterval(() => this.loadSpectators(), 5000);
  }

  /**
   * Carrega a lista de espectadores do backend
   */
  loadSpectators(): void {
    if (this.loading) return;

    this.loading = true;
    this.error = null;

    const headers = new HttpHeaders({
      'X-Summoner-Name': this.summonerName
    });

    const url = `${this.baseUrl}/discord/match/${this.matchId}/spectators`;

    this.http.get<SpectatorResponse>(url, { headers }).subscribe({
      next: (response: SpectatorResponse) => {
        if (response.success) {
          this.spectators = response.spectators;
          console.log(`👀 [SpectatorsModal] ${response.count} espectadores carregados`);
        } else {
          this.error = 'Erro ao carregar espectadores';
        }
        this.loading = false;
      },
      error: (err: any) => {
        console.error('❌ [SpectatorsModal] Erro ao carregar espectadores:', err);
        this.error = 'Erro ao comunicar com o servidor';
        this.loading = false;
      }
    });
  }

  /**
   * Muta ou desmuta um espectador
   */
  toggleMute(spectator: SpectatorDTO): void {
    const headers = new HttpHeaders({
      'X-Summoner-Name': this.summonerName
    });

    const action = spectator.isMuted ? 'unmute' : 'mute';
    const url = `${this.baseUrl}/discord/match/${this.matchId}/spectator/${spectator.discordId}/${action}`;

    console.log(`🔇 [SpectatorsModal] ${action} espectador ${spectator.discordUsername}`);

    this.http.post<MuteResponse>(url, {}, { headers }).subscribe({
      next: (response: MuteResponse) => {
        if (response.success) {
          // Atualizar estado local imediatamente
          spectator.isMuted = !spectator.isMuted;
          console.log(`✅ [SpectatorsModal] ${spectator.discordUsername} ${spectator.isMuted ? 'mutado' : 'desmutado'}`);
        } else {
          console.error(`❌ [SpectatorsModal] Erro: ${response.message}`);
          this.error = response.message;
        }
      },
      error: (err: any) => {
        console.error(`❌ [SpectatorsModal] Erro ao ${action}:`, err);
        this.error = `Erro ao ${action === 'mute' ? 'mutar' : 'desmutar'} espectador`;
      }
    });
  }

  /**
   * Fecha o modal
   */
  closeModal(): void {
    this.closed.emit();
  }

  /**
   * Retorna a classe CSS do badge baseado no canal
   */
  getChannelBadgeClass(channelName: string): string {
    return channelName === 'Blue Team' ? 'badge-blue' : 'badge-red';
  }

  /**
   * Retorna o ícone do botão de mute
   */
  getMuteIcon(isMuted: boolean): string {
    return isMuted ? '🔊' : '🔇';
  }

  /**
   * Retorna o texto do botão de mute
   */
  getMuteText(isMuted: boolean): string {
    return isMuted ? 'Desmutar' : 'Mutar';
  }
}

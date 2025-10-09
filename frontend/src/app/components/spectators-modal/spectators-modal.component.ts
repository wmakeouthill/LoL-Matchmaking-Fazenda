import { Component, Input, Output, EventEmitter, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { ApiService } from '../../services/api';
import { CurrentSummonerService } from '../../services/current-summoner.service';

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
    private readonly http: HttpClient,
    private readonly apiService: ApiService,
    private readonly currentSummonerService: CurrentSummonerService
  ) {
    this.baseUrl = this.apiService.getBaseUrl();
    console.log('🎯 [SpectatorsModal] CONSTRUCTOR - Componente criado!', {
      baseUrl: this.baseUrl,
      timestamp: new Date().toISOString()
    });
  }

  ngOnInit(): void {
    console.log('🔵 [SpectatorsModal] ngOnInit chamado', {
      matchId: this.matchId,
      summonerName: this.summonerName,
      hasMatchId: !!this.matchId,
      hasSummonerName: !!this.summonerName
    });

    // ✅ VALIDAÇÃO: Verificar se matchId existe
    if (!this.matchId) {
      console.error('❌ [SpectatorsModal] matchId é undefined! Não é possível carregar espectadores');
      this.error = 'ID da partida não disponível';
      this.loading = false;
      return;
    }

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

    // ✅ CORREÇÃO: Obter summoner name com fallback
    let summonerName = this.summonerName;
    if (!summonerName) {
      summonerName = this.currentSummonerService.getSummonerNameForHeader() || '';
      console.warn('⚠️ [SpectatorsModal] summonerName não passado via @Input, usando CurrentSummonerService:', summonerName);
    }

    const headers = new HttpHeaders({
      'X-Summoner-Name': summonerName
    });

    const url = `${this.baseUrl}/discord/match/${this.matchId}/spectators`;

    console.log('📡 [SpectatorsModal] ========== INICIANDO REQUISIÇÃO ==========');
    console.log('📡 [SpectatorsModal] URL completa:', url);
    console.log('📡 [SpectatorsModal] matchId:', this.matchId);
    console.log('📡 [SpectatorsModal] summonerName:', summonerName);
    console.log('📡 [SpectatorsModal] Headers:', {
      'X-Summoner-Name': summonerName
    });
    console.log('📡 [SpectatorsModal] baseUrl:', this.baseUrl);

    this.http.get<SpectatorResponse>(url, { headers }).subscribe({
      next: (response: SpectatorResponse) => {
        console.log('✅ [SpectatorsModal] ========== RESPOSTA RECEBIDA ==========');
        console.log('✅ [SpectatorsModal] Response completo:', response);
        console.log('✅ [SpectatorsModal] Success:', response.success);
        console.log('✅ [SpectatorsModal] Count:', response.count);
        console.log('✅ [SpectatorsModal] Spectators:', response.spectators);

        if (response.success) {
          this.spectators = response.spectators;
          console.log(`✅ [SpectatorsModal] ${response.count} espectadores carregados com sucesso`);
        } else {
          this.error = 'Erro ao carregar espectadores';
          console.error('❌ [SpectatorsModal] Resposta indicou falha:', response);
        }
        this.loading = false;
      },
      error: (err: any) => {
        console.error('❌ [SpectatorsModal] ========== ERRO NA REQUISIÇÃO ==========');
        console.error('❌ [SpectatorsModal] Erro completo:', err);
        console.error('❌ [SpectatorsModal] Status:', err.status);
        console.error('❌ [SpectatorsModal] StatusText:', err.statusText);
        console.error('❌ [SpectatorsModal] Error body:', err.error);
        console.error('❌ [SpectatorsModal] Message:', err.message);
        console.error('❌ [SpectatorsModal] URL chamada:', err.url);
        console.error('❌ [SpectatorsModal] Headers enviados:', {
          'X-Summoner-Name': summonerName
        });

        this.error = `Erro ao comunicar com o servidor: ${err.status || 'Unknown'} ${err.statusText || ''}`;
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

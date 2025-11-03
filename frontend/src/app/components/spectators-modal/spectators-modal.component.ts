import { Component, Input, Output, EventEmitter, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Subscription } from 'rxjs';
import { ApiService } from '../../services/api';
import { CurrentSummonerService } from '../../services/current-summoner.service';
import { ElectronEventsService } from '../../services/electron-events.service';

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
  styleUrls: ['./spectators-modal.component.scss']
})
export class SpectatorsModalComponent implements OnInit, OnDestroy {
  @Input() matchId!: number;
  @Input() summonerName!: string;
  @Output() closed = new EventEmitter<void>();

  spectators: SpectatorDTO[] = [];
  loading = false;
  error: string | null = null;
  private readonly baseUrl: string;
  private readonly subscriptions: Subscription[] = [];

  // ✅ CORREÇÃO FALLBACK: Manter matchId fixo durante toda a vida do modal
  private cachedMatchId: number | null = null;

  constructor(
    private readonly http: HttpClient,
    private readonly apiService: ApiService,
    private readonly currentSummonerService: CurrentSummonerService,
    private readonly electronEvents: ElectronEventsService
  ) {
    this.baseUrl = this.apiService.getBaseUrl();
    console.log('🎯 [SpectatorsModal] CONSTRUCTOR - Componente criado!', {
      baseUrl: this.baseUrl,
      timestamp: new Date().toISOString()
    });

    // ✅ INTEGRAÇÃO COM SIGNALS: Ouvir eventos de mute/unmute do Electron
    // Quando alguém muta/desmuta um espectador, o evento vem via Electron
    // e precisa atualizar a lista local
    this.subscriptions.push(
      this.electronEvents.spectatorMuted$.subscribe(muteData => {
        if (muteData) {
          console.log('🔇 [SpectatorsModal] Evento spectatorMuted recebido:', muteData);
          this.handleSpectatorMuteEvent(muteData, true);
        }
      })
    );

    this.subscriptions.push(
      this.electronEvents.spectatorUnmuted$.subscribe(unmuteData => {
        if (unmuteData) {
          console.log('🔊 [SpectatorsModal] Evento spectatorUnmuted recebido:', unmuteData);
          this.handleSpectatorMuteEvent(unmuteData, false);
        }
      })
    );
  }

  ngOnInit(): void {
    console.log('🔵 [SpectatorsModal] ngOnInit chamado');
    console.log('🔑 [SpectatorsModal] matchId recebido:', this.matchId);
    console.log('🔑 [SpectatorsModal] typeof matchId:', typeof this.matchId);
    console.log('🔑 [SpectatorsModal] summonerName:', this.summonerName);
    console.log('✅ [SpectatorsModal] hasMatchId:', !!this.matchId);
    console.log('✅ [SpectatorsModal] hasSummonerName:', !!this.summonerName);

    // ✅ VALIDAÇÃO: Verificar se matchId existe
    if (!this.matchId) {
      console.error('❌ [SpectatorsModal] matchId é undefined! Não é possível carregar espectadores');
      this.error = 'ID da partida não disponível';
      this.loading = false;
      return;
    }

    // ✅ CORREÇÃO FALLBACK: Cachear o matchId no momento da abertura do modal
    // Isso garante que o matchId não será perdido durante operações (mute/unmute)
    this.cachedMatchId = this.matchId;
    console.log('💾 [SpectatorsModal] matchId cacheado:', this.cachedMatchId);

    this.loadSpectators();
  }

  /**
   * ✅ INTEGRAÇÃO COM SIGNALS: Manipula eventos de mute/unmute vindos do Electron
   * Quando outro usuário muta/desmuta um espectador, o evento chega via WebSocket/Electron
   * e precisa atualizar a lista local sem precisar fazer uma nova requisição HTTP
   */
  private handleSpectatorMuteEvent(eventData: any, isMuted: boolean): void {
    console.log(`🎯 [SpectatorsModal] Processando evento de ${isMuted ? 'mute' : 'unmute'}:`, eventData);

    // Verificar se o evento é da partida atual
    if (eventData.matchId && this.cachedMatchId && eventData.matchId !== this.cachedMatchId) {
      console.log(`⏭️ [SpectatorsModal] Evento ignorado - matchId diferente (evento: ${eventData.matchId}, modal: ${this.cachedMatchId})`);
      return;
    }

    const discordId = eventData.spectator?.discordId || eventData.discordId;
    if (!discordId) {
      console.warn('⚠️ [SpectatorsModal] Evento não contém discordId, ignorando');
      return;
    }

    // Encontrar e atualizar o espectador na lista
    const spectatorIndex = this.spectators.findIndex(s => s.discordId === discordId);
    if (spectatorIndex >= 0) {
      // ✅ CRÍTICO: Criar NOVA referência do array para signals detectarem
      const updatedSpectators = [...this.spectators];
      // ✅ CRÍTICO: Criar NOVA referência do objeto para signals detectarem
      updatedSpectators[spectatorIndex] = {
        ...updatedSpectators[spectatorIndex],
        isMuted: isMuted
      };
      // Atribuir nova referência
      this.spectators = updatedSpectators;

      console.log(`✅ [SpectatorsModal] Espectador ${updatedSpectators[spectatorIndex].discordUsername} atualizado para ${isMuted ? 'MUTADO' : 'DESMUTADO'} via evento`);
    } else {
      console.log(`ℹ️ [SpectatorsModal] Espectador ${discordId} não encontrado na lista local, recarregando lista completa...`);
      // Se o espectador não está na lista, recarregar tudo
      this.loadSpectators();
    }
  }

  ngOnDestroy(): void {
    console.log('🔴 [SpectatorsModal] ngOnDestroy - Componente destruído');
    // ✅ Limpar todas as subscriptions para evitar memory leaks
    for (const sub of this.subscriptions) {
      sub.unsubscribe();
    }
  }

  /**
   * Carrega a lista de espectadores do backend
   */
  loadSpectators(): void {
    if (this.loading) return;

    // ✅ CORREÇÃO FALLBACK: Usar o matchId cacheado, ou o @Input se o cache não existir
    const effectiveMatchId = this.cachedMatchId || this.matchId;

    if (!effectiveMatchId) {
      console.error('❌ [SpectatorsModal] matchId não disponível (nem cached nem input)');
      this.error = 'ID da partida não disponível';
      this.loading = false;
      return;
    }

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

    const url = `${this.baseUrl}/discord/match/${effectiveMatchId}/spectators`;

    console.log('📡 [SpectatorsModal] ========== INICIANDO REQUISIÇÃO ==========');
    console.log('📡 [SpectatorsModal] URL completa:', url);
    console.log('📡 [SpectatorsModal] matchId (@Input):', this.matchId);
    console.log('📡 [SpectatorsModal] cachedMatchId:', this.cachedMatchId);
    console.log('📡 [SpectatorsModal] effectiveMatchId:', effectiveMatchId);
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
    // ✅ CORREÇÃO FALLBACK: Usar o matchId cacheado, ou o @Input se o cache não existir
    const effectiveMatchId = this.cachedMatchId || this.matchId;

    if (!effectiveMatchId) {
      console.error('❌ [SpectatorsModal] matchId não disponível para toggleMute');
      this.error = 'ID da partida não disponível';
      return;
    }

    // ✅ CORREÇÃO: Obter summoner name com fallback
    let summonerName = this.summonerName;
    if (!summonerName) {
      summonerName = this.currentSummonerService.getSummonerNameForHeader() || '';
      console.warn('⚠️ [SpectatorsModal] summonerName não passado via @Input para toggleMute, usando CurrentSummonerService:', summonerName);
    }

    const headers = new HttpHeaders({
      'X-Summoner-Name': summonerName
    });

    const action = spectator.isMuted ? 'unmute' : 'mute';
    const url = `${this.baseUrl}/discord/match/${effectiveMatchId}/spectator/${spectator.discordId}/${action}`;

    console.log(`🔇 [SpectatorsModal] === TOGGLE MUTE ===`);
    console.log(`🔇 [SpectatorsModal] Action: ${action}`);
    console.log(`🔇 [SpectatorsModal] Espectador: ${spectator.discordUsername}`);
    console.log(`🔇 [SpectatorsModal] URL: ${url}`);
    console.log(`🔇 [SpectatorsModal] matchId (@Input): ${this.matchId}`);
    console.log(`🔇 [SpectatorsModal] cachedMatchId: ${this.cachedMatchId}`);
    console.log(`🔇 [SpectatorsModal] effectiveMatchId: ${effectiveMatchId}`);
    console.log(`🔇 [SpectatorsModal] summonerName: ${summonerName}`);
    console.log(`🔇 [SpectatorsModal] discordId: ${spectator.discordId}`);

    this.http.post<MuteResponse>(url, {}, { headers }).subscribe({
      next: (response: MuteResponse) => {
        if (response.success) {
          // ✅ CRÍTICO: Criar NOVA referência do array ao invés de mutar diretamente
          // Isso garante que Signals detectem a mudança se o componente pai estiver observando
          const spectatorIndex = this.spectators.findIndex(s => s.discordId === spectator.discordId);
          if (spectatorIndex >= 0) {
            // Criar nova referência do array
            const updatedSpectators = [...this.spectators];
            // Criar nova referência do spectator
            updatedSpectators[spectatorIndex] = {
              ...updatedSpectators[spectatorIndex],
              isMuted: !updatedSpectators[spectatorIndex].isMuted
            };
            // Atualizar com nova referência
            this.spectators = updatedSpectators;

            console.log(`✅ [SpectatorsModal] ${spectator.discordUsername} ${updatedSpectators[spectatorIndex].isMuted ? 'mutado' : 'desmutado'} (nova referência)`);
          }
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

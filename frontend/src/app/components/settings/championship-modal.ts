import { Component, Input, Output, EventEmitter, OnInit, OnChanges, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { ApiService } from '../../services/api';
import { firstValueFrom } from 'rxjs';

@Component({
  selector: 'app-championship-modal',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './championship-modal.html',
  styleUrl: './championship-modal.css'
})
export class ChampionshipModalComponent implements OnInit, OnChanges {
  @Input() isVisible: boolean = false;
  @Output() closed = new EventEmitter<void>();
  @Output() championshipAwarded = new EventEmitter<any>();

  players: any[] = [];
  championships: any[] = [];
  selectedPlayer: string = '';
  selectedChampionship: any = null;
  customLpBonus: number = 0;
  loading: boolean = false;
  errorMessage: string = '';
  successMessage: string = '';

  private readonly baseUrl: string;

  constructor(
    private readonly http: HttpClient,
    private readonly apiService: ApiService
  ) {
    this.baseUrl = this.apiService.getBaseUrl();
  }

  ngOnInit(): void {
    // ✅ FIX: Não carregar dados automaticamente no init
    // Apenas carregar quando modal for aberto (isVisible = true)
  }

  ngOnChanges(changes: SimpleChanges): void {
    // ✅ FIX: Carregar dados apenas quando modal se tornar visível
    if (changes['isVisible'] && changes['isVisible'].currentValue === true) {
      this.loadPlayers();
      this.loadChampionships();
    }
  }

  async loadPlayers(): Promise<void> {
    try {
      const response: any = await firstValueFrom(
        this.http.get(`${this.baseUrl}/player/all`, {
          headers: this.apiService.getAuthenticatedHeaders()
        })
      );

      if (response.success && response.players) {
        this.players = response.players;
        this.players.sort((a, b) => a.summonerName.localeCompare(b.summonerName));
      } else {
        this.players = [];
        this.errorMessage = 'Nenhum jogador encontrado';
      }
    } catch (error: any) {
      console.error('Erro ao carregar jogadores:', error);
      this.errorMessage = error.error?.error || 'Erro ao carregar lista de jogadores';
    }
  }

  async loadChampionships(): Promise<void> {
    try {
      const response: any = await firstValueFrom(
        this.http.get(`${this.baseUrl}/admin/championship-titles`, {
          headers: this.apiService.getAuthenticatedHeaders()
        })
      );
      this.championships = response.titles || [];
    } catch (error) {
      console.error('Erro ao carregar campeonatos:', error);
      this.errorMessage = 'Erro ao carregar títulos de campeonato';
    }
  }

  onChampionshipSelect(championship: any): void {
    this.selectedChampionship = championship;
    this.customLpBonus = championship.defaultLp;
  }

  async awardChampionship(): Promise<void> {
    if (!this.selectedPlayer) {
      this.errorMessage = 'Selecione um jogador';
      return;
    }

    if (!this.selectedChampionship) {
      this.errorMessage = 'Selecione um campeonato';
      return;
    }

    if (this.customLpBonus <= 0) {
      this.errorMessage = 'O bônus de LP deve ser maior que zero';
      return;
    }

    try {
      this.loading = true;
      this.errorMessage = '';
      this.successMessage = '';

      // ✅ CORREÇÃO: Validar que temos headers autenticados antes de fazer a requisição
      const headers = this.apiService.getAuthenticatedHeaders();
      console.log('🔐 [ChampionshipModal] Headers para requisição:', headers);

      if (!headers['X-Summoner-Name']) {
        throw new Error('Você precisa estar logado para premiar jogadores. Por favor, reconecte ao League of Legends.');
      }

      console.log('🏆 [ChampionshipModal] Premiando jogador:', {
        summonerName: this.selectedPlayer,
        championshipTitle: this.selectedChampionship.name,
        lpBonus: this.customLpBonus,
        requestingPlayer: headers['X-Summoner-Name']
      });

      const response: any = await firstValueFrom(
        this.http.post(`${this.baseUrl}/admin/award-championship`, {
          summonerName: this.selectedPlayer,
          championshipTitle: this.selectedChampionship.name,
          lpBonus: this.customLpBonus
        }, {
          headers: headers
        })
      );

      if (response.success) {
        this.successMessage = `🏆 Título concedido! ${this.selectedChampionship.name} (+${this.customLpBonus} LP)`;
        this.championshipAwarded.emit(response);

        // Resetar formulário após 3 segundos
        setTimeout(() => {
          this.resetForm();
        }, 3000);
      } else {
        this.errorMessage = response.message || 'Erro ao premiar jogador';
      }
    } catch (error: any) {
      console.error('❌ [ChampionshipModal] Erro ao premiar jogador:', error);
      console.error('❌ [ChampionshipModal] Erro completo:', {
        status: error.status,
        statusText: error.statusText,
        message: error.message,
        error: error.error
      });

      // ✅ CORREÇÃO: Exibir mensagem de erro mais detalhada
      if (error?.message?.includes('logado')) {
        this.errorMessage = error.message;
      } else if (error.error?.message) {
        this.errorMessage = error.error.message;
      } else if (error.status === 400 && error.error) {
        this.errorMessage = error.error.message || 'Erro de validação. Verifique os dados e tente novamente.';
      } else if (error.status === 403) {
        this.errorMessage = 'Você não tem permissão para premiar jogadores.';
      } else {
        this.errorMessage = error.message || 'Erro ao premiar jogador com título';
      }
    } finally {
      this.loading = false;
    }
  }

  resetForm(): void {
    this.selectedPlayer = '';
    this.selectedChampionship = null;
    this.customLpBonus = 0;
    this.successMessage = '';
    this.errorMessage = '';
  }

  close(): void {
    this.resetForm();
    this.closed.emit();
  }
}

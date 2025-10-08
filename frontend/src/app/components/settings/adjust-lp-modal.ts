import { Component, Input, Output, EventEmitter, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { ApiService } from '../../services/api';
import { firstValueFrom } from 'rxjs';

@Component({
    selector: 'app-adjust-lp-modal',
    standalone: true,
    imports: [CommonModule, FormsModule],
    templateUrl: './adjust-lp-modal.html',
    styleUrl: './adjust-lp-modal.css'
})
export class AdjustLpModalComponent implements OnInit {
    @Input() isVisible: boolean = false;
    @Output() closed = new EventEmitter<void>();
    @Output() lpAdjusted = new EventEmitter<any>();

    players: any[] = [];
    selectedPlayer: string = '';
    lpAdjustment: number = 0;
    reason: string = '';
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

    async ngOnInit() {
        await this.loadPlayers();
    }

    async loadPlayers(): Promise<void> {
        try {
            this.loading = true;
            const response: any = await firstValueFrom(
                this.http.get(`${this.baseUrl}/player/all`, {
                    headers: this.apiService.getAuthenticatedHeaders()
                })
            );
            this.players = response || [];
            this.players.sort((a, b) => a.summonerName.localeCompare(b.summonerName));
        } catch (error) {
            console.error('Erro ao carregar jogadores:', error);
            this.errorMessage = 'Erro ao carregar lista de jogadores';
        } finally {
            this.loading = false;
        }
    }

    async adjustLp(): Promise<void> {
        if (!this.selectedPlayer) {
            this.errorMessage = 'Selecione um jogador';
            return;
        }

        if (this.lpAdjustment === 0) {
            this.errorMessage = 'Informe um valor diferente de zero';
            return;
        }

        try {
            this.loading = true;
            this.errorMessage = '';
            this.successMessage = '';

            const response: any = await firstValueFrom(
                this.http.post(`${this.baseUrl}/admin/adjust-player-lp`, {
                    summonerName: this.selectedPlayer,
                    lpAdjustment: this.lpAdjustment,
                    reason: this.reason
                }, {
                    headers: this.apiService.getAuthenticatedHeaders()
                })
            );

            if (response.success) {
                this.successMessage = `LP ajustado! ${response.previousLp} → ${response.newLp} (${this.lpAdjustment > 0 ? '+' : ''}${this.lpAdjustment})`;
                this.lpAdjusted.emit(response);

                // Resetar formulário após 2 segundos
                setTimeout(() => {
                    this.resetForm();
                }, 2000);
            } else {
                this.errorMessage = response.message || 'Erro ao ajustar LP';
            }
        } catch (error: any) {
            console.error('Erro ao ajustar LP:', error);
            this.errorMessage = error.error?.message || 'Erro ao ajustar LP do jogador';
        } finally {
            this.loading = false;
        }
    }

    resetForm(): void {
        this.selectedPlayer = '';
        this.lpAdjustment = 0;
        this.reason = '';
        this.successMessage = '';
        this.errorMessage = '';
    }

    close(): void {
        this.resetForm();
        this.closed.emit();
    }
}

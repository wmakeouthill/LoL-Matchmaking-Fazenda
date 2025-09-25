import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import { ApiService } from './api';

export interface Champion {
  id: string | number; // ✅ CORREÇÃO: Aceitar tanto string quanto number
  key: string;
  name: string;
  title: string;
  image: string;
  tags: string[];
  info: {
    attack: number;
    defense: number;
    magic: number;
    difficulty: number;
  };
}

export interface ChampionsByRole {
  top: Champion[];
  jungle: Champion[];
  mid: Champion[];
  adc: Champion[];
  support: Champion[];
  all: Champion[];
}

@Injectable({
  providedIn: 'root'
})
export class ChampionService {
  private readonly baseImageUrl = 'https://ddragon.leagueoflegends.com/cdn/15.14.1/img/champion/';
  private readonly baseUrl: string;

  private readonly roleMapping = {
    top: ['Fighter', 'Tank'],
    jungle: ['Assassin', 'Fighter', 'Tank'],
    mid: ['Mage', 'Assassin'],
    adc: ['Marksman'],
    support: ['Support', 'Tank', 'Mage']
  };

  // Lista mínima de campeões mais comuns para funcionalidades básicas (fallback)
  private readonly fallbackChampions: Champion[] = [
    { id: '266', key: 'Aatrox', name: 'Aatrox', title: 'a Espada Darkin', image: this.baseImageUrl + 'Aatrox.png', tags: ['Fighter'], info: { attack: 8, defense: 4, magic: 3, difficulty: 4 } },
    { id: '103', key: 'Ahri', name: 'Ahri', title: 'a Raposa de Nove Caudas', image: this.baseImageUrl + 'Ahri.png', tags: ['Mage', 'Assassin'], info: { attack: 3, defense: 4, magic: 8, difficulty: 5 } },
    { id: '84', key: 'Akali', name: 'Akali', title: 'a Assassina Renegada', image: this.baseImageUrl + 'Akali.png', tags: ['Assassin'], info: { attack: 5, defense: 3, magic: 8, difficulty: 7 } },
    { id: '166', key: 'Akshan', name: 'Akshan', title: 'o Sentinela Rebelde', image: this.baseImageUrl + 'Akshan.png', tags: ['Marksman', 'Assassin'], info: { attack: 0, defense: 0, magic: 0, difficulty: 0 } },
    { id: '12', key: 'Alistar', name: 'Alistar', title: 'o Minotauro', image: this.baseImageUrl + 'Alistar.png', tags: ['Tank', 'Support'], info: { attack: 6, defense: 9, magic: 5, difficulty: 7 } }
  ];

  // Cache para os campeões carregados do backend
  private cachedChampions: Champion[] | null = null;
  private cachedChampionsByRole: ChampionsByRole | null = null;

  constructor(private readonly http: HttpClient, private readonly apiService: ApiService) {
    this.baseUrl = this.apiService.getBaseUrl();
  }

  /**
   * Obtém o nome do campeão pelo seu ID
   *
   * NOTA: Este método agora é usado apenas como fallback.
   * O backend já processa os dados dos campeões automaticamente.
   *
   * @param championId - ID numérico do campeão
   * @returns Nome do campeão ou 'Minion' como fallback
   */
  static getChampionNameById(championId: number | undefined): string {
    if (!championId) return 'Minion';

    // Fallback simples - retorna 'Minion' para qualquer ID desconhecido
    // O backend já processa os dados automaticamente, então este método
    // é usado apenas em casos extremos onde o backend não está disponível
    return 'Minion';
  }

  /**
   * Obtém todos os campeões do backend
   */
  getAllChampions(): Observable<Champion[]> {
    console.log('🏆 [ChampionService] getAllChampions() chamado');
    console.log('🏆 [ChampionService] Cache atual:', this.cachedChampions ? `${this.cachedChampions.length} campeões` : 'null');

    // Se já temos cache, retornar imediatamente
    if (this.cachedChampions) {
      console.log('🏆 [ChampionService] Retornando cache:', this.cachedChampions.length, 'campeões');
      return of(this.cachedChampions);
    }

    console.log('🏆 [ChampionService] Cache vazio, carregando do backend...');
    console.log('🏆 [ChampionService] URL do backend:', `${this.baseUrl}/champions`);

    return this.http.get<any>(`${this.baseUrl}/champions`).pipe(
      map(response => {
        console.log('🏆 [ChampionService] Resposta do backend recebida:', response);

        if (response.success && response.champions) {
          console.log(`✅ [ChampionService] ${response.champions.length} campeões carregados do backend`);
          console.log('🏆 [ChampionService] Primeiros 5 campeões:', response.champions.slice(0, 5).map((c: Champion) => c.name));
          this.cachedChampions = response.champions;
          return response.champions;
        } else {
          console.error('❌ [ChampionService] Resposta inválida do backend:', response);
          throw new Error('Resposta inválida do backend');
        }
      }),
      catchError(error => {
        console.warn('⚠️ [ChampionService] Erro ao carregar do backend, usando fallback:', error);
        console.log('🏆 [ChampionService] Retornando fallback:', this.fallbackChampions.length, 'campeões');
        return of(this.fallbackChampions);
      })
    );
  }

  /**
   * Obtém campeões organizados por role do backend
   */
  getChampionsByRole(): Observable<ChampionsByRole> {
    // Se já temos cache, retornar imediatamente
    if (this.cachedChampionsByRole) {
      return of(this.cachedChampionsByRole);
    }

    console.log('🏆 [ChampionService] Carregando campeões por role do backend...');

    return this.http.get<any>(`${this.baseUrl}/champions`).pipe(
      map(response => {
        if (response.success && response.championsByRole) {
          console.log(`✅ [ChampionService] Campeões por role carregados do backend`);
          this.cachedChampionsByRole = response.championsByRole;
          return response.championsByRole;
        } else {
          throw new Error('Resposta inválida do backend');
        }
      }),
      catchError(error => {
        console.warn('⚠️ [ChampionService] Erro ao carregar do backend, usando fallback:', error);
        // Criar fallback organizado por role
        const fallbackByRole = this.createFallbackChampionsByRole();
        return of(fallbackByRole);
      })
    );
  }

  /**
   * Cria fallback organizado por role
   */
  private createFallbackChampionsByRole(): ChampionsByRole {
    const result: ChampionsByRole = {
      top: [],
      jungle: [],
      mid: [],
      adc: [],
      support: [],
      all: this.fallbackChampions
    };

    this.fallbackChampions.forEach(champion => {
      // Top lane
      if (champion.tags.some(tag => this.roleMapping.top.includes(tag))) {
        result.top.push(champion);
      }

      // Jungle
      if (champion.tags.some(tag => this.roleMapping.jungle.includes(tag))) {
        result.jungle.push(champion);
      }

      // Mid lane
      if (champion.tags.some(tag => this.roleMapping.mid.includes(tag))) {
        result.mid.push(champion);
      }

      // ADC
      if (champion.tags.some(tag => this.roleMapping.adc.includes(tag))) {
        result.adc.push(champion);
      }

      // Support
      if (champion.tags.some(tag => this.roleMapping.support.includes(tag))) {
        result.support.push(champion);
      }
    });

    return result;
  }

  /**
   * Busca campeões por query
   */
  searchChampions(query: string, role?: string): Observable<Champion[]> {
    return this.getAllChampions().pipe(
      map(champions => {
        let filtered = champions;

        // Filtrar por role
        if (role && role !== 'all') {
          filtered = filtered.filter(champion => {
            const tags = champion.tags || [];
            switch (role) {
              case 'top':
                return tags.includes('Fighter') || tags.includes('Tank');
              case 'jungle':
                return tags.includes('Fighter') || tags.includes('Assassin');
              case 'mid':
                return tags.includes('Mage') || tags.includes('Assassin');
              case 'adc':
                return tags.includes('Marksman');
              case 'support':
                return tags.includes('Support');
              default:
                return true;
            }
          });
        }

        // Filtrar por busca
        if (query.trim()) {
          const searchTerm = query.toLowerCase().trim();
          filtered = filtered.filter(champion =>
            champion.name.toLowerCase().includes(searchTerm) ||
            champion.title.toLowerCase().includes(searchTerm) ||
            champion.tags.some(tag => tag.toLowerCase().includes(searchTerm))
          );
        }

        return filtered;
      })
    );
  }

  /**
   * Obtém um campeão aleatório
   */
  getRandomChampion(excludeIds: string[] = []): Observable<Champion> {
    return this.getAllChampions().pipe(
      map(champions => {
        const availableChampions = champions.filter(c => !excludeIds.includes(c.id.toString()));
        const randomIndex = Math.floor(Math.random() * availableChampions.length);
        return availableChampions[randomIndex];
      })
    );
  }

  /**
   * Verifica se um campeão está banido
   */
  isChampionBanned(championId: string, bannedChampions: Champion[]): boolean {
    return bannedChampions.some(banned => banned.id.toString() === championId);
  }

  /**
   * Verifica se um campeão foi escolhido
   */
  isChampionPicked(championId: string, pickedChampions: Champion[]): boolean {
    return pickedChampions.some(picked => picked.id.toString() === championId);
  }

  /**
   * Limpa o cache dos campeões
   */
  clearCache(): void {
    this.cachedChampions = null;
    this.cachedChampionsByRole = null;
    console.log('🧹 [ChampionService] Cache limpo');
  }
}

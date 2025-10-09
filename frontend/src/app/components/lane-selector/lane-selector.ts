import { Component, Input, Output, EventEmitter, OnInit, OnChanges, SimpleChanges, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Lane, QueuePreferences } from '../../interfaces';
import { DiscordIntegrationService } from '../../services/discord-integration.service';
import { CurrentSummonerService } from '../../services/current-summoner.service';

@Component({
  selector: 'app-lane-selector',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './lane-selector.html',
  styleUrl: './lane-selector.scss'
})
export class LaneSelectorComponent implements OnInit, OnChanges, OnDestroy {
  @Input() isVisible = false;
  @Input() currentPreferences: QueuePreferences = {
    primaryLane: '',
    secondaryLane: ''
  };
  @Input() currentSummonerName = '';

  @Output() close = new EventEmitter<void>();
  @Output() confirm = new EventEmitter<QueuePreferences>();

  lanes: Lane[] = [
    {
      id: 'top',
      name: 'Topo',
      icon: '🛡️',
      description: 'Tanques e Lutadores'
    },
    {
      id: 'jungle',
      name: 'Selva',
      icon: '🌲',
      description: 'Controle de Objetivos'
    },
    {
      id: 'mid',
      name: 'Meio',
      icon: '⚡',
      description: 'Magos e Assassinos'
    },
    {
      id: 'bot',
      name: 'Atirador',
      icon: '🏹',
      description: 'Dano Sustentado'
    },
    {
      id: 'support',
      name: 'Suporte',
      icon: '🛡️',
      description: 'Proteção e Utilidade'
    }
  ];

  selectedPrimary = '';
  selectedSecondary = '';
  isPlayerOnDiscord = false;
  private discordSubscription: any;

  constructor(
    private readonly discordService: DiscordIntegrationService,
    private readonly currentSummonerService: CurrentSummonerService
  ) { }

  ngOnInit() {
    console.log('🎯 [LaneSelector] Componente inicializado');
    console.log('🎯 [LaneSelector] Estado inicial:', {
      isVisible: this.isVisible,
      currentPreferences: this.currentPreferences,
      currentSummonerName: this.currentSummonerName
    });
    this.selectedPrimary = this.currentPreferences.primaryLane;
    this.selectedSecondary = this.currentPreferences.secondaryLane;

    // Verificar se jogador está no Discord
    this.checkPlayerOnDiscord();

    // Subscribir para mudanças nos usuários Discord
    this.discordSubscription = this.discordService.onUsersUpdate().subscribe(() => {
      this.checkPlayerOnDiscord();
    });
  }

  ngOnChanges(changes: SimpleChanges) {
    if (changes['isVisible']) {
      console.log('🎯 [LaneSelector] isVisible mudou:', {
        previousValue: changes['isVisible'].previousValue,
        currentValue: changes['isVisible'].currentValue,
        modalAberto: changes['isVisible'].currentValue
      });

      if (changes['isVisible'].currentValue === true) {
        console.log('🎯 [LaneSelector] MODAL DEVE ESTAR VISÍVEL AGORA!');
        // Verificar Discord quando modal abrir
        this.checkPlayerOnDiscord();
      }
    }
  }

  selectPrimaryLane(laneId: string) {
    this.selectedPrimary = laneId;
    // Se a lane secundária for igual à primária, limpar
    if (this.selectedSecondary === laneId) {
      this.selectedSecondary = '';
    }
  }

  selectSecondaryLane(laneId: string) {
    // Não permitir selecionar a mesma lane como secundária
    if (laneId !== this.selectedPrimary) {
      this.selectedSecondary = laneId;
    }
  }

  isValidSelection(): boolean {
    return this.selectedPrimary !== '' &&
      this.selectedSecondary !== '' &&
      this.isPlayerOnDiscord;
  }

  checkPlayerOnDiscord(): void {
    const discordUsers = this.discordService.getDiscordUsersOnline();
    const currentName = this.currentSummonerName || this.currentSummonerService.getSummonerNameForHeader();

    if (!currentName) {
      this.isPlayerOnDiscord = false;
      console.log('🎯 [LaneSelector] Sem summoner name disponível');
      return;
    }

    // Normalizar o nome atual (remover espaços e lowercase)
    const normalizedCurrent = currentName.toLowerCase().trim();

    this.isPlayerOnDiscord = discordUsers.some(user => {
      // Verificar vários campos possíveis onde o summoner name pode estar
      const linkedNickname = this.getLinkedNickname(user);
      const summonerName = user.summonerName || '';
      const linkedDisplayName = user.linkedDisplayName || '';

      // Comparar com todos os campos possíveis
      return (
        linkedNickname?.toLowerCase().trim() === normalizedCurrent ||
        summonerName?.toLowerCase().trim() === normalizedCurrent ||
        linkedDisplayName?.toLowerCase().trim() === normalizedCurrent
      );
    });

    console.log('🎯 [LaneSelector] Verificação Discord:', {
      currentName,
      normalizedCurrent,
      discordUsers: discordUsers.length,
      discordUsersSample: discordUsers.slice(0, 2).map(u => ({
        summonerName: u.summonerName,
        linkedNickname: this.getLinkedNickname(u),
        linkedDisplayName: u.linkedDisplayName
      })),
      isPlayerOnDiscord: this.isPlayerOnDiscord
    });
  }

  private getLinkedNickname(user: any): string {
    if (!user?.linkedNickname) {
      return '';
    }

    // Se for string (displayName direto), retornar
    if (typeof user.linkedNickname === 'string') {
      return user.linkedNickname;
    }

    // Se for objeto com {gameName, tagLine}, montar displayName
    if (typeof user.linkedNickname === 'object' &&
      user.linkedNickname.gameName &&
      user.linkedNickname.tagLine) {
      const tagLine = user.linkedNickname.tagLine.startsWith('#')
        ? user.linkedNickname.tagLine
        : `#${user.linkedNickname.tagLine}`;
      return `${user.linkedNickname.gameName}${tagLine}`;
    }

    // Verificar se tem linkedDisplayName (novo formato)
    if (user.linkedDisplayName && typeof user.linkedDisplayName === 'string') {
      return user.linkedDisplayName;
    }

    return '';
  }

  onConfirm() {
    if (this.isValidSelection()) {
      this.confirm.emit({
        primaryLane: this.selectedPrimary,
        secondaryLane: this.selectedSecondary
      });
    }
  }

  onClose() {
    console.log('🎯 [LaneSelector] Fechando modal...');
    this.close.emit();
  }

  ngOnDestroy() {
    console.log('🎯 [LaneSelector] Componente destruído');
    if (this.discordSubscription) {
      this.discordSubscription.unsubscribe();
    }
  }

  getLaneName(laneId: string): string {
    const lane = this.lanes.find(l => l.id === laneId);
    return lane ? lane.name : laneId;
  }

  getLaneIcon(laneId: string): string {
    const lane = this.lanes.find(l => l.id === laneId);
    return lane ? lane.icon : '❓';
  }
}

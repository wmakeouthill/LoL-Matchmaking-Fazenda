import { Injectable } from '@angular/core';
// import { any } from './champion.service'; // TODO: Implementar se necessário

export interface PickBanPhase {
    team: 'blue' | 'red';
    action: 'ban' | 'pick';
    champion?: any; // TODO: Definir interface any se necessário
    playerId?: string;
    playerName?: string;
    playerIndex?: number;
    locked: boolean;
    timeRemaining: number;
}

export interface CustomPickBanSession {
    id: string;
    phase: 'bans' | 'picks' | 'completed';
    currentAction: number;
    extendedTime: number;
    phases: PickBanPhase[];
    blueTeam: any[];
    redTeam: any[];
    currentPlayerIndex: number;
    team1?: any[];
    team2?: any[];
}

@Injectable({
    providedIn: 'root'
})
export class BotService {

    constructor() { }

    /**
     * ✅ NOVO: Verifica se deve aceitar partida automaticamente
     */
    shouldAutoAcceptMatch(currentPlayer: any): boolean {
        if (!currentPlayer) {
            return false;
        }

        const isBotPlayer = this.isBot(currentPlayer);
        console.log(`🤖 [BotService] shouldAutoAcceptMatch:`, {
            playerName: currentPlayer.summonerName || currentPlayer.gameName,
            isBot: isBotPlayer
        });

        return isBotPlayer;
    }

    /**
     * Verifica se um jogador é um bot
     */
    isBot(player: any): boolean {
        if (!player) {
            console.log('🤖 [BotService] isBot: player é null/undefined');
            return false;
        }

        // Verificar se é um bot baseado no nome
        const playerName = player.name || player.summonerName || player.displayName || player.gameName || '';

        // ✅ SIMPLIFICADO: Apenas padrão Bot1, Bot2, Bot3, etc.
        const botPattern = /^Bot\d+$/i;
        const isBotPlayer = botPattern.test(playerName);

        console.log(`🤖 [BotService] === isBot check ===`, {
            playerName: playerName,
            isBotPlayer,
            id: player.id,
            summonerName: player.summonerName,
            name: player.name,
            displayName: player.displayName,
            gameName: player.gameName,
            tagLine: player.tagLine
        });

        return isBotPlayer;
    }

    /**
     * Método auxiliar para comparar jogadores de forma consistente
     */
    comparePlayers(player1: any, player2: any): boolean {
        if (!player1 || !player2) return false;

        const id1 = player1.id?.toString();
        const name1 = player1.summonerName || player1.name || '';
        const id2 = player2.id?.toString();
        const name2 = player2.summonerName || player2.name || '';

        if (id1 && id2 && id1 === id2) {
            return true;
        }

        if (name1 && name2 && name1 === name2) {
            return true;
        }

        if (name1 && name2 && name1.includes('#')) {
            const gameName1 = name1.split('#')[0];
            if (name2.includes('#')) {
                const gameName2 = name2.split('#')[0];
                if (gameName1 === gameName2) {
                    return true;
                }
            } else if (gameName1 === name2) {
                return true;
            }
        }

        if (name1 && name2 && name1.startsWith(name2 + '#')) {
            return true;
        }

        return false;
    }

    /**
     * Compara um jogador com um ID específico usando identificadores padronizados
     */
    comparePlayerWithId(player: any, targetId: string): boolean {
        if (!player || !targetId) {
            console.log(`🤖 [comparePlayerWithId] Dados inválidos - player: ${!!player}, targetId: ${targetId}`);
            return false;
        }

        // ✅ NOVO: Usar identificadores padronizados
        const playerNormalized = this.normalizePlayerIdentifier(player);
        const targetNormalized = targetId.toLowerCase().trim();

        console.log('🔍 [BotService] Comparando jogadores:', {
            player: playerNormalized,
            target: targetNormalized,
            match: playerNormalized === targetNormalized
        });

        return playerNormalized === targetNormalized;
    }

    /**
     * ✅ NOVO: Verifica se o usuário atual é o special user autorizado
     */
    private isSpecialUser(currentPlayer: any): boolean {
        if (!currentPlayer) {
            console.log('🔐 [BotService] currentPlayer é null/undefined');
            return false;
        }

        console.log('🔐 [BotService] === VERIFICAÇÃO DE SPECIAL USER ===');
        console.log('🔐 [BotService] currentPlayer completo:', currentPlayer);

        // ✅ NOVO: Padronizar identificador do jogador
        const normalizedId = this.normalizePlayerIdentifier(currentPlayer);

        console.log('🔐 [BotService] Identificador normalizado:', normalizedId);

        // ✅ CORREÇÃO: Verificar se é o popcorn seller#coup (case insensitive)
        const isSpecial = normalizedId === 'popcorn seller#coup' ||
            normalizedId === 'popcorn seller' ||
            normalizedId.includes('popcorn seller');

        console.log(`🔐 [BotService] Verificação de special user:`, {
            normalizedId,
            isSpecial,
            expected: 'popcorn seller#coup'
        });

        return isSpecial;
    }

    /**
     * ✅ NOVO: Padronizar identificador do jogador (igual ao backend)
     */
    private normalizePlayerIdentifier(playerInfo: any): string {
        if (!playerInfo) return '';

        // Prioridade 1: gameName#tagLine (padrão)
        if (playerInfo.gameName && playerInfo.tagLine) {
            return `${playerInfo.gameName}#${playerInfo.tagLine}`.toLowerCase().trim();
        }

        // Prioridade 2: displayName (se já está no formato correto)
        if (playerInfo.displayName && playerInfo.displayName.includes('#')) {
            return playerInfo.displayName.toLowerCase().trim();
        }

        // Prioridade 3: summonerName (fallback)
        if (playerInfo.summonerName) {
            return playerInfo.summonerName.toLowerCase().trim();
        }

        // Prioridade 4: name (fallback)
        if (playerInfo.name) {
            return playerInfo.name.toLowerCase().trim();
        }

        return '';
    }

    /**
     * Verifica se uma fase deve ter ação automática de bot
     * ✅ MELHORADO: Agora verifica se é realmente o turno do bot baseado no fluxo do draft
     */
    shouldPerformBotAction(phase: PickBanPhase, session: CustomPickBanSession, currentPlayer?: any): boolean {
        console.log('🤖 [BotService] === VERIFICANDO AÇÃO AUTOMÁTICA ===');
        console.log('🤖 [BotService] Phase:', phase);
        console.log('🤖 [BotService] Tipo de ação:', phase.action);
        console.log('🤖 [BotService] Session currentAction:', session.currentAction);

        // ✅ NOVO: Verificar se o usuário atual é o special user autorizado
        const isSpecialUser = this.isSpecialUser(currentPlayer);
        console.log('🤖 [BotService] É special user?', isSpecialUser);

        if (!isSpecialUser) {
            console.log('🚫 [BotService] Ação de bot BLOQUEADA - não é special user');
            console.log('🚫 [BotService] Apenas popcorn seller#coup pode executar ações de bot');
            return false;
        }

        const currentTeam = phase.team;
        const teamPlayers = currentTeam === 'blue' ? session.blueTeam : session.redTeam;

        console.log(`🤖 [BotService] Time atual: ${currentTeam}`);
        console.log(`🤖 [BotService] Jogadores do time:`, teamPlayers.map(p => ({
            id: p.id,
            name: p.summonerName,
            lane: p.lane,
            teamIndex: p.teamIndex,
            isBot: this.isBot(p)
        })));

        let phasePlayer = null;

        // ✅ CORREÇÃO: Primeiro tentar pelo playerId se existir
        if (phase.playerId) {
            console.log(`🤖 [BotService] Procurando por playerId: ${phase.playerId}`);
            phasePlayer = teamPlayers.find(p => this.comparePlayerWithId(p, phase.playerId!));
        }

        // ✅ CORREÇÃO: Se não encontrou por playerId, tentar pelo teamIndex
        if (!phasePlayer && phase.playerIndex !== undefined) {
            console.log(`🤖 [BotService] Tentando encontrar por teamIndex: ${phase.playerIndex}`);
            phasePlayer = teamPlayers.find(p => p.teamIndex === phase.playerIndex);
        }

        // ✅ CORREÇÃO: Se ainda não encontrou, tentar pelo índice do array
        if (!phasePlayer && phase.playerIndex !== undefined) {
            console.log(`🤖 [BotService] Tentando encontrar por índice do array: ${phase.playerIndex}`);
            phasePlayer = teamPlayers[phase.playerIndex];
        }

        console.log('🤖 [BotService] Phase player encontrado:', phasePlayer);

        if (phasePlayer) {
            const isBotPlayer = this.isBot(phasePlayer);
            console.log('🤖 [BotService] É bot?', isBotPlayer);
            console.log('🤖 [BotService] Detalhes do jogador:', {
                id: phasePlayer.id,
                name: phasePlayer.summonerName,
                teamIndex: phasePlayer.teamIndex,
                isBot: isBotPlayer,
                action: phase.action
            });

            // ✅ MELHORADO: Verificar se é realmente o turno do bot
            const currentActionIndex = session.currentAction;
            const expectedPlayer = this.getExpectedPlayerForAction(session, currentActionIndex);

            if (!expectedPlayer) {
                console.log('⚠️ [BotService] Jogador esperado não encontrado para ação', currentActionIndex);
                return false;
            }

            const isBotTurn = this.comparePlayerWithId(
                { summonerName: expectedPlayer },
                phasePlayer.summonerName || phasePlayer.name
            );

            console.log('🤖 [BotService] Verificações:', {
                isBotPlayer,
                isSpecialUser,
                isBotTurn,
                expectedPlayer,
                currentPlayer: phasePlayer.summonerName || phasePlayer.name,
                currentActionIndex
            });

            // ✅ MELHORADO: Só executar se for bot E special user E for realmente o turno do bot
            const shouldExecute = isBotPlayer && isSpecialUser && isBotTurn;
            console.log('🤖 [BotService] Deve executar ação de bot?', shouldExecute);
            return shouldExecute;
        } else {
            console.log('⚠️ [BotService] Jogador não encontrado!');
            console.log('🤖 [BotService] Phase.playerId:', phase.playerId);
            console.log('🤖 [BotService] Phase.playerIndex:', phase.playerIndex);
            console.log('🤖 [BotService] Todos os jogadores do time:', teamPlayers);
            return false;
        }
    }

    /**
     * Executa ação automática do bot
     */
    performBotAction(phase: PickBanPhase, session: CustomPickBanSession, champions: any[]): void {
        console.log('🤖 [BotService] === EXECUTANDO AÇÃO DO BOT ===');
        console.log('🤖 [BotService] Executando ação do bot para fase:', phase);
        console.log('🤖 [BotService] Tipo de ação:', phase.action);
        console.log(`🤖 [BotService] currentAction antes: ${session.currentAction}`);
        console.log(`🤖 [BotService] total de fases: ${session.phases.length}`);

        if (!session) {
            console.log('⚠️ [BotService] Session não existe');
            return;
        }

        if (!phase) {
            console.log('⚠️ [BotService] Phase não existe');
            return;
        }

        const availableanys = champions.filter(c =>
            !this.isanyBanned(c, session) && !this.isanyPicked(c, session)
        );

        console.log('🤖 [BotService] Campeões disponíveis:', availableanys.length);
        console.log('🤖 [BotService] Primeiros 5 campeões disponíveis:', availableanys.slice(0, 5).map(c => c.name));

        if (availableanys.length === 0) {
            console.log('⚠️ [BotService] Nenhum campeão disponível');
            return;
        }

        const randomany = availableanys[Math.floor(Math.random() * availableanys.length)];
        console.log(`🤖 [BotService] Campeão selecionado para ${phase.action}:`, randomany.name);

        // ✅ CORREÇÃO: Garantir que a fase seja atualizada corretamente
        phase.champion = randomany;
        phase.locked = true;
        phase.timeRemaining = 0;

        // ✅ NOVO: Clonar o array de fases para garantir detecção de mudança
        session.phases = [...session.phases];

        console.log('🤖 [BotService] Fase atualizada:', {
            team: phase.team,
            action: phase.action,
            champion: phase.champion?.name,
            locked: phase.locked,
            timeRemaining: phase.timeRemaining
        });

        // ✅ CORREÇÃO: Incrementar currentAction
        session.currentAction++;

        console.log(`🤖 [BotService] currentAction após incremento: ${session.currentAction}`);
        console.log(`🤖 [BotService] total de fases: ${session.phases.length}`);

        if (session.currentAction >= session.phases.length) {
            console.log('🤖 [BotService] Sessão completada');
            session.phase = 'completed';
        } else {
            console.log(`🤖 [BotService] Próxima ação será: ${session.currentAction + 1}`);
            console.log(`🤖 [BotService] Próxima fase:`, session.phases[session.currentAction]);
        }

        console.log('🤖 [BotService] === FIM DA AÇÃO DO BOT ===');
    }

    /**
     * Verifica se um campeão está banido
     */
    private isanyBanned(champion: any, session: CustomPickBanSession): boolean {
        return session.phases
            .filter(phase => phase.action === 'ban' && phase.champion)
            .some(phase => phase.champion!.id === champion.id);
    }

    /**
     * Verifica se um campeão foi escolhido
     */
    private isanyPicked(champion: any, session: CustomPickBanSession): boolean {
        return session.phases
            .filter(phase => phase.action === 'pick' && phase.champion)
            .some(phase => phase.champion!.id === champion.id);
    }

    /**
     * Agenda uma ação automática do bot
     */
    scheduleBotAction(
        phase: PickBanPhase,
        session: CustomPickBanSession,
        champions: any[],
        callback: () => void
    ): number {
        console.log('🤖 [BotService] === AGENDANDO AÇÃO DO BOT ===');
        console.log('🤖 [BotService] Agendando ação para fase:', phase);
        console.log('🤖 [BotService] Tipo de ação:', phase.action);
        console.log('🤖 [BotService] currentAction:', session.currentAction);

        // ✅ CORREÇÃO: Delay de 5 segundos para bots (como solicitado)
        const delay = 5000; // 5 segundos fixos
        console.log(`🤖 [BotService] Delay agendado: ${delay}ms`);

        const timerId = setTimeout(() => {
            console.log(`🤖 [BotService] === EXECUTANDO AÇÃO AGENDADA (${phase.action}) ===`);
            console.log(`🤖 [BotService] Timer ${timerId} executando para ação ${session.currentAction + 1}`);

            this.performBotAction(phase, session, champions);

            console.log(`🤖 [BotService] Ação agendada concluída, executando callback`);
            callback();

            console.log(`🤖 [BotService] === FIM DA AÇÃO AGENDADA ===`);
        }, delay);

        console.log(`🤖 [BotService] Timer agendado: ${timerId}`);
        console.log(`🤖 [BotService] === FIM DO AGENDAMENTO ===`);

        return timerId;
    }

    /**
     * Cancela uma ação agendada do bot
     */
    cancelScheduledAction(timerId: number): void {
        if (timerId) {
            clearTimeout(timerId);
            console.log('🤖 [BotService] Ação agendada cancelada');
        }
    }

    /**
     * Verifica se um jogador específico é bot
     */
    isPlayerBot(player: any): boolean {
        return this.isBot(player);
    }

    /**
     * Obtém informações sobre bots em um time
     */
    getBotInfo(team: any[]): { botCount: number, botPlayers: any[] } {
        const botPlayers = team.filter(player => this.isBot(player));
        return {
            botCount: botPlayers.length,
            botPlayers: botPlayers
        };
    }

    /**
     * Verifica se um time tem bots
     */
    hasBots(team: any[]): boolean {
        return team.some(player => this.isBot(player));
    }

    /**
     * Obtém estatísticas de bots na sessão
     */
    getSessionBotStats(session: CustomPickBanSession): {
        blueTeamBots: number;
        redTeamBots: number;
        totalBots: number;
        botPercentage: number;
    } {
        const blueBots = this.getBotInfo(session.blueTeam);
        const redBots = this.getBotInfo(session.redTeam);
        const totalPlayers = session.blueTeam.length + session.redTeam.length;
        const totalBots = blueBots.botCount + redBots.botCount;

        return {
            blueTeamBots: blueBots.botCount,
            redTeamBots: redBots.botCount,
            totalBots: totalBots,
            botPercentage: totalPlayers > 0 ? (totalBots / totalPlayers) * 100 : 0
        };
    }

    /**
     * ✅ NOVO: Obtém o jogador esperado para uma ação específica baseado no fluxo do draft
     */
    private getExpectedPlayerForAction(session: CustomPickBanSession, actionIndex: number): string | null {
        try {
            const draftFlow = this.generateDraftFlow(session);
            const expectedPlayer = draftFlow[actionIndex];

            console.log(`🔍 [BotService] Ação ${actionIndex}: esperado ${expectedPlayer}`);
            return expectedPlayer || null;
        } catch (error) {
            console.error(`❌ [BotService] Erro ao obter jogador esperado para ação ${actionIndex}:`, error);
            return null;
        }
    }

    /**
     * ✅ NOVO: Gera o fluxo completo do draft baseado nos jogadores da sessão
     * Segue exatamente o padrão da partida ranqueada do LoL
     */
    private generateDraftFlow(session: CustomPickBanSession): string[] {
        const team1Players = session.blueTeam || [];
        const team2Players = session.redTeam || [];

        // Garantir que temos exatamente 5 jogadores por time
        if (team1Players.length !== 5 || team2Players.length !== 5) {
            console.error(`❌ [BotService] Times inválidos: Blue=${team1Players.length}, Red=${team2Players.length}`);
            return [];
        }

        // ✅ FLUXO DO DRAFT RANQUEADO (20 ações):
        const draftFlow = [
            // Primeira Fase de Banimento (6 bans - 3 por time)
            team1Players[0]?.summonerName || team1Players[0]?.name, // Ação 0: Jogador 1 Blue (Top) - Ban
            team2Players[0]?.summonerName || team2Players[0]?.name, // Ação 1: Jogador 1 Red (Top) - Ban
            team1Players[1]?.summonerName || team1Players[1]?.name, // Ação 2: Jogador 2 Blue (Jungle) - Ban
            team2Players[1]?.summonerName || team2Players[1]?.name, // Ação 3: Jogador 2 Red (Jungle) - Ban
            team1Players[2]?.summonerName || team1Players[2]?.name, // Ação 4: Jogador 3 Blue (Mid) - Ban
            team2Players[2]?.summonerName || team2Players[2]?.name, // Ação 5: Jogador 3 Red (Mid) - Ban

            // Primeira Fase de Picks (6 picks - 3 por time)
            team1Players[0]?.summonerName || team1Players[0]?.name, // Ação 6: Jogador 1 Blue (Top) - Pick (First Pick)
            team2Players[0]?.summonerName || team2Players[0]?.name, // Ação 7: Jogador 1 Red (Top) - Pick
            team2Players[1]?.summonerName || team2Players[1]?.name, // Ação 8: Jogador 2 Red (Jungle) - Pick
            team1Players[1]?.summonerName || team1Players[1]?.name, // Ação 9: Jogador 2 Blue (Jungle) - Pick
            team1Players[2]?.summonerName || team1Players[2]?.name, // Ação 10: Jogador 3 Blue (Mid) - Pick
            team2Players[2]?.summonerName || team2Players[2]?.name, // Ação 11: Jogador 3 Red (Mid) - Pick

            // Segunda Fase de Banimento (4 bans - 2 por time)
            team2Players[3]?.summonerName || team2Players[3]?.name, // Ação 12: Jogador 4 Red (ADC) - Ban
            team1Players[3]?.summonerName || team1Players[3]?.name, // Ação 13: Jogador 4 Blue (ADC) - Ban
            team2Players[4]?.summonerName || team2Players[4]?.name, // Ação 14: Jogador 5 Red (Support) - Ban
            team1Players[4]?.summonerName || team1Players[4]?.name, // Ação 15: Jogador 5 Blue (Support) - Ban

            // Segunda Fase de Picks (4 picks - 2 por time)
            team2Players[3]?.summonerName || team2Players[3]?.name, // Ação 16: Jogador 4 Red (ADC) - Pick
            team1Players[3]?.summonerName || team1Players[3]?.name, // Ação 17: Jogador 4 Blue (ADC) - Pick
            team1Players[4]?.summonerName || team1Players[4]?.name, // Ação 18: Jogador 5 Blue (Support) - Pick
            team2Players[4]?.summonerName || team2Players[4]?.name  // Ação 19: Jogador 5 Red (Support) - Pick (Last Pick)
        ];

        console.log(`✅ [BotService] Fluxo do draft gerado: ${draftFlow.length} ações`);
        return draftFlow;
    }
}

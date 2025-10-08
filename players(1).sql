-- phpMyAdmin SQL Dump
-- version 5.1.1
-- https://www.phpmyadmin.net/
--
-- Host: 10.129.76.12
-- Tempo de geração: 08/10/2025 às 23:26
-- Versão do servidor: 5.6.26-log
-- Versão do PHP: 8.0.15

SET SQL_MODE = "NO_AUTO_VALUE_ON_ZERO";
START TRANSACTION;
SET time_zone = "+00:00";


/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!40101 SET NAMES utf8mb4 */;

--
-- Banco de dados: `lolmatchmaking`
--

-- --------------------------------------------------------

--
-- Estrutura para tabela `players`
--

CREATE TABLE `players` (
  `id` bigint(20) NOT NULL,
  `summoner_name` varchar(255) NOT NULL,
  `summoner_id` varchar(255) DEFAULT NULL,
  `puuid` varchar(255) DEFAULT NULL,
  `region` varchar(10) NOT NULL,
  `current_mmr` int(11) DEFAULT '1000',
  `peak_mmr` int(11) DEFAULT '1000',
  `games_played` int(11) DEFAULT '0',
  `wins` int(11) DEFAULT '0',
  `losses` int(11) DEFAULT '0',
  `win_streak` int(11) DEFAULT '0',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `custom_mmr` int(11) DEFAULT '1000',
  `custom_peak_mmr` int(11) DEFAULT '1000',
  `custom_games_played` int(11) DEFAULT '0',
  `custom_wins` int(11) DEFAULT '0',
  `custom_losses` int(11) DEFAULT '0',
  `custom_win_streak` int(11) DEFAULT '0',
  `custom_lp` int(11) DEFAULT '0',
  `avg_kills` double DEFAULT '0',
  `avg_deaths` double DEFAULT '0',
  `avg_assists` double DEFAULT '0',
  `kda_ratio` double DEFAULT '0',
  `favorite_champion` varchar(50) DEFAULT NULL,
  `favorite_champion_games` int(11) DEFAULT '0',
  `player_stats_draft` longtext COMMENT 'Top 5 campeões mais pickados nas custom matches',
  `mastery_champions` longtext COMMENT 'Top 3 campeões de maestria da Riot API',
  `ranked_champions` longtext COMMENT 'Top 5 campeões ranked (mais jogados + maior winrate)',
  `stats_last_updated` timestamp NULL DEFAULT NULL COMMENT 'Última atualização das estatísticas',
  `lcu_port` int(11) DEFAULT '0' COMMENT 'Porta do LCU deste jogador',
  `lcu_protocol` varchar(10) DEFAULT 'https' COMMENT 'Protocolo do LCU (https ou http)',
  `lcu_password` varchar(255) DEFAULT NULL COMMENT 'Password do lockfile do LCU',
  `lcu_last_updated` timestamp NULL DEFAULT NULL COMMENT 'Última atualização da configuração LCU',
  `championship_titles` text COMMENT 'JSON array storing championship titles awarded to the player'
) ENGINE=InnoDB DEFAULT CHARSET=latin1;

--
-- Despejando dados para a tabela `players`
--

INSERT INTO `players` (`id`, `summoner_name`, `summoner_id`, `puuid`, `region`, `current_mmr`, `peak_mmr`, `games_played`, `wins`, `losses`, `win_streak`, `created_at`, `updated_at`, `custom_mmr`, `custom_peak_mmr`, `custom_games_played`, `custom_wins`, `custom_losses`, `custom_win_streak`, `custom_lp`, `avg_kills`, `avg_deaths`, `avg_assists`, `kda_ratio`, `favorite_champion`, `favorite_champion_games`, `player_stats_draft`, `mastery_champions`, `ranked_champions`, `stats_last_updated`, `lcu_port`, `lcu_protocol`, `lcu_password`, `lcu_last_updated`, `championship_titles`) VALUES
(18, 'FZD Ratoso#fzd', '1786097', '9e7d05fe-ef7f-5ecb-b877-de7e68ff06eb', 'br1', 1200, 2101, 0, 0, 0, NULL, '2025-10-02 01:03:25', '2025-10-09 01:25:13', 1200, 2101, 0, 0, 0, NULL, 0, 0, 0, 0, 0, NULL, 0, '[]', '[{\"championId\":92,\"championName\":\"Champion 92\",\"championLevel\":15,\"championPoints\":197394,\"championPointsSinceLastLevel\":66794,\"championPointsUntilNextLevel\":-55794},{\"championId\":221,\"championName\":\"Champion 221\",\"championLevel\":18,\"championPoints\":173144,\"championPointsSinceLastLevel\":9544,\"championPointsUntilNextLevel\":1456},{\"championId\":105,\"championName\":\"Champion 105\",\"championLevel\":15,\"championPoints\":172427,\"championPointsSinceLastLevel\":41827,\"championPointsUntilNextLevel\":-30827}]', '[{\"championId\":800,\"championName\":\"Champion 800\",\"gamesPlayed\":2,\"wins\":0,\"losses\":2,\"winRate\":0.0,\"tier\":\"RANKED_SOLO_5x5\"},{\"championId\":901,\"championName\":\"Champion 901\",\"gamesPlayed\":1,\"wins\":0,\"losses\":1,\"winRate\":0.0,\"tier\":\"RANKED_SOLO_5x5\"},{\"championId\":166,\"championName\":\"Champion 166\",\"gamesPlayed\":1,\"wins\":0,\"losses\":1,\"winRate\":0.0,\"tier\":\"RANKED_SOLO_5x5\"},{\"championId\":893,\"championName\":\"Champion 893\",\"gamesPlayed\":1,\"wins\":0,\"losses\":1,\"winRate\":0.0,\"tier\":\"RANKED_SOLO_5x5\"},{\"championId\":145,\"championName\":\"Champion 145\",\"gamesPlayed\":2,\"wins\":1,\"losses\":1,\"winRate\":50.0,\"tier\":\"RANKED_SOLO_5x5\"}]', '2025-10-06 06:45:07', 53118, 'https', '0ta6r2X-Sn2ZYT7hFPZhjg', '2025-10-08 03:12:17', '[]'),
(21, 'FZD SherlokGaz#FZD', '1926325', 'bc79453d-7dc2-551e-8a1f-84b7fa57dc1c', 'br1', 1830, 1830, 0, 0, 0, NULL, '2025-10-05 12:14:31', '2025-10-07 03:27:26', 1766, 1830, 2, 0, 2, 0, -64, 0, 7, 3, 0.42857142857142855, 'Champion 3', 2, '[{\"wins\":0,\"championId\":3,\"championName\":\"Champion 3\",\"gamesPlayed\":2,\"winRate\":0.0,\"losses\":2}]', '[{\"championId\":91,\"championName\":\"Champion 91\",\"championLevel\":18,\"championPoints\":218561,\"championPointsSinceLastLevel\":54961,\"championPointsUntilNextLevel\":-43961},{\"championId\":222,\"championName\":\"Champion 222\",\"championLevel\":12,\"championPoints\":140285,\"championPointsSinceLastLevel\":42685,\"championPointsUntilNextLevel\":-31685},{\"championId\":38,\"championName\":\"Champion 38\",\"championLevel\":12,\"championPoints\":128971,\"championPointsSinceLastLevel\":31371,\"championPointsUntilNextLevel\":-20371}]', '[{\"championId\":6,\"championName\":\"Champion 6\",\"gamesPlayed\":20,\"wins\":15,\"losses\":5,\"winRate\":75.0,\"tier\":\"RANKED_SOLO_5x5\"}]', '2025-10-06 06:45:29', 51308, 'https', '-SvZ4NmRjmasqhSXD-ytwQ', '2025-10-07 03:27:26', '[]'),
(23, 'Eahm#patas', '49795156', '622fc414-bd0f-5c45-88f1-8d14cdd33625', 'br1', 1200, 1000, NULL, NULL, NULL, NULL, '2025-10-07 04:48:52', '2025-10-09 01:30:24', 1200, 1000, NULL, NULL, NULL, NULL, 0, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 56586, 'https', 'Z-v3MvH3G49JU6LGdnYDCQ', '2025-10-08 03:30:22', '[]');

--
-- Índices para tabelas despejadas
--

--
-- Índices de tabela `players`
--
ALTER TABLE `players`
  ADD PRIMARY KEY (`id`),
  ADD UNIQUE KEY `summoner_name` (`summoner_name`),
  ADD UNIQUE KEY `uq_players_summoner_id` (`summoner_id`),
  ADD UNIQUE KEY `uq_players_puuid` (`puuid`);

--
-- AUTO_INCREMENT para tabelas despejadas
--

--
-- AUTO_INCREMENT de tabela `players`
--
ALTER TABLE `players`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=24;
COMMIT;

/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;

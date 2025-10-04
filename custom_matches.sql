-- phpMyAdmin SQL Dump
-- version 5.1.1
-- https://www.phpmyadmin.net/
--
-- Host: 10.129.76.12
-- Tempo de geração: 04/10/2025 às 01:54
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
-- Estrutura para tabela `custom_matches`
--

CREATE TABLE `custom_matches` (
  `id` bigint(20) NOT NULL,
  `title` varchar(255) DEFAULT NULL,
  `description` text,
  `team1_players` text NOT NULL,
  `team2_players` text NOT NULL,
  `winner_team` int(11) DEFAULT NULL,
  `status` varchar(50) DEFAULT 'pending',
  `created_by` varchar(255) NOT NULL,
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `completed_at` timestamp NULL DEFAULT NULL,
  `game_mode` varchar(20) DEFAULT '5v5',
  `duration` int(11) DEFAULT NULL,
  `lp_changes` text,
  `average_mmr_team1` int(11) DEFAULT NULL,
  `average_mmr_team2` int(11) DEFAULT NULL,
  `participants_data` text,
  `riot_game_id` varchar(255) DEFAULT NULL,
  `detected_by_lcu` tinyint(4) DEFAULT '0',
  `notes` text,
  `custom_lp` int(11) DEFAULT '0',
  `updated_at` timestamp NULL DEFAULT NULL,
  `pick_ban_data` text,
  `linked_results` text,
  `actual_winner` int(11) DEFAULT NULL,
  `actual_duration` int(11) DEFAULT NULL,
  `riot_id` varchar(255) DEFAULT NULL,
  `mmr_changes` text,
  `match_leader` varchar(255) DEFAULT NULL,
  `owner_backend_id` varchar(100) DEFAULT NULL,
  `owner_heartbeat` bigint(20) DEFAULT NULL,
  `lcu_match_data` text COMMENT 'JSON completo da partida do LCU para vinculação e histórico'
) ENGINE=InnoDB DEFAULT CHARSET=latin1;

--
-- Despejando dados para a tabela `custom_matches`
--

INSERT INTO `custom_matches` (`id`, `title`, `description`, `team1_players`, `team2_players`, `winner_team`, `status`, `created_by`, `created_at`, `completed_at`, `game_mode`, `duration`, `lp_changes`, `average_mmr_team1`, `average_mmr_team2`, `participants_data`, `riot_game_id`, `detected_by_lcu`, `notes`, `custom_lp`, `updated_at`, `pick_ban_data`, `linked_results`, `actual_winner`, `actual_duration`, `riot_id`, `mmr_changes`, `match_leader`, `owner_backend_id`, `owner_heartbeat`, `lcu_match_data`) VALUES
(105, 'Partida Customizada', 'Partida gerada automaticamente pelo sistema de matchmaking', 'Bot9,FZD Ratoso#fzd,Bot5,Bot6,Bot4', 'Bot8,Bot1,Bot7,Bot2,Bot3', NULL, 'in_progress', 'system', '2025-10-04 04:52:45', NULL, '5v5', NULL, NULL, 1962, 1623, NULL, NULL, NULL, NULL, NULL, '2025-10-04 04:53:47', '{\"currentTeam\":null,\"teams\":{\"red\":{\"players\":[{\"mmr\":1637,\"gameName\":\"Bot8\",\"tagLine\":\"\",\"teamIndex\":5,\"summonerName\":\"Bot8\",\"assignedLane\":\"top\",\"actions\":[{\"phase\":\"ban1\",\"championId\":\"10\",\"championName\":\"Kayle\",\"index\":1,\"type\":\"ban\",\"status\":\"completed\"},{\"phase\":\"pick1\",\"championId\":\"98\",\"championName\":\"Shen\",\"index\":7,\"type\":\"pick\",\"status\":\"completed\"}],\"playerId\":-8},{\"mmr\":1970,\"gameName\":\"Bot1\",\"tagLine\":\"\",\"teamIndex\":6,\"summonerName\":\"Bot1\",\"assignedLane\":\"jungle\",\"actions\":[{\"phase\":\"ban1\",\"championId\":\"89\",\"championName\":\"Leona\",\"index\":3,\"type\":\"ban\",\"status\":\"completed\"},{\"phase\":\"pick1\",\"championId\":\"4\",\"championName\":\"TwistedFate\",\"index\":8,\"type\":\"pick\",\"status\":\"completed\"}],\"playerId\":-1},{\"mmr\":1965,\"gameName\":\"Bot7\",\"tagLine\":\"\",\"teamIndex\":7,\"summonerName\":\"Bot7\",\"assignedLane\":\"mid\",\"actions\":[{\"phase\":\"ban1\",\"championId\":\"901\",\"championName\":\"Smolder\",\"index\":5,\"type\":\"ban\",\"status\":\"completed\"},{\"phase\":\"pick1\",\"championId\":\"203\",\"championName\":\"Kindred\",\"index\":11,\"type\":\"pick\",\"status\":\"completed\"}],\"playerId\":-7},{\"mmr\":1672,\"gameName\":\"Bot2\",\"tagLine\":\"\",\"teamIndex\":8,\"summonerName\":\"Bot2\",\"assignedLane\":\"bot\",\"actions\":[{\"phase\":\"ban2\",\"championId\":\"154\",\"championName\":\"Zac\",\"index\":12,\"type\":\"ban\",\"status\":\"completed\"},{\"phase\":\"pick2\",\"championId\":\"221\",\"championName\":\"Zeri\",\"index\":16,\"type\":\"pick\",\"status\":\"completed\"}],\"playerId\":-2},{\"mmr\":871,\"gameName\":\"Bot3\",\"tagLine\":\"\",\"teamIndex\":9,\"summonerName\":\"Bot3\",\"assignedLane\":\"support\",\"actions\":[{\"phase\":\"ban2\",\"championId\":\"121\",\"championName\":\"Khazix\",\"index\":14,\"type\":\"ban\",\"status\":\"completed\"},{\"phase\":\"pick2\",\"championId\":\"37\",\"championName\":\"Sona\",\"index\":19,\"type\":\"pick\",\"status\":\"completed\"}],\"playerId\":-3}],\"allPicks\":[\"98\",\"4\",\"203\",\"221\",\"37\"],\"name\":\"Red Team\",\"teamNumber\":2,\"averageMmr\":1623,\"allBans\":[\"10\",\"89\",\"901\",\"154\",\"121\"]},\"blue\":{\"players\":[{\"mmr\":1835,\"gameName\":\"Bot9\",\"tagLine\":\"\",\"teamIndex\":0,\"summonerName\":\"Bot9\",\"assignedLane\":\"top\",\"actions\":[{\"phase\":\"ban1\",\"championId\":\"429\",\"championName\":\"Kalista\",\"index\":0,\"type\":\"ban\",\"status\":\"completed\"},{\"phase\":\"pick1\",\"championId\":\"141\",\"championName\":\"Kayn\",\"index\":6,\"type\":\"pick\",\"status\":\"completed\"}],\"playerId\":-9},{\"mmr\":2101,\"gameName\":\"FZD Ratoso\",\"tagLine\":\"fzd\",\"teamIndex\":1,\"summonerName\":\"FZD Ratoso#fzd\",\"assignedLane\":\"jungle\",\"actions\":[{\"phase\":\"ban1\",\"championId\":\"166\",\"championName\":\"Akshan\",\"index\":2,\"type\":\"ban\",\"status\":\"completed\"},{\"phase\":\"pick1\",\"championId\":\"84\",\"championName\":\"Akali\",\"index\":9,\"type\":\"pick\",\"status\":\"completed\"}],\"playerId\":1786097},{\"mmr\":1986,\"gameName\":\"Bot5\",\"tagLine\":\"\",\"teamIndex\":2,\"summonerName\":\"Bot5\",\"assignedLane\":\"mid\",\"actions\":[{\"phase\":\"ban1\",\"championId\":\"40\",\"championName\":\"Janna\",\"index\":4,\"type\":\"ban\",\"status\":\"completed\"},{\"phase\":\"pick1\",\"championId\":\"67\",\"championName\":\"Vayne\",\"index\":10,\"type\":\"pick\",\"status\":\"completed\"}],\"playerId\":-5},{\"mmr\":1944,\"gameName\":\"Bot6\",\"tagLine\":\"\",\"teamIndex\":3,\"summonerName\":\"Bot6\",\"assignedLane\":\"bot\",\"actions\":[{\"phase\":\"ban2\",\"championId\":\"517\",\"championName\":\"Sylas\",\"index\":13,\"type\":\"ban\",\"status\":\"completed\"},{\"phase\":\"pick2\",\"championId\":\"119\",\"championName\":\"Draven\",\"index\":17,\"type\":\"pick\",\"status\":\"completed\"}],\"playerId\":-6},{\"mmr\":1947,\"gameName\":\"Bot4\",\"tagLine\":\"\",\"teamIndex\":4,\"summonerName\":\"Bot4\",\"assignedLane\":\"support\",\"actions\":[{\"phase\":\"ban2\",\"championId\":\"711\",\"championName\":\"Vex\",\"index\":15,\"type\":\"ban\",\"status\":\"completed\"},{\"phase\":\"pick2\",\"championId\":\"102\",\"championName\":\"Shyvana\",\"index\":18,\"type\":\"pick\",\"status\":\"completed\"}],\"playerId\":-4}],\"allPicks\":[\"141\",\"84\",\"67\",\"119\",\"102\"],\"name\":\"Blue Team\",\"teamNumber\":1,\"averageMmr\":1963,\"allBans\":[\"429\",\"166\",\"40\",\"517\",\"711\"]}},\"currentPlayer\":null,\"team1\":[{\"mmr\":1835,\"teamIndex\":0,\"primaryLane\":\"jungle\",\"summonerName\":\"Bot9\",\"assignedLane\":\"top\",\"secondaryLane\":\"jungle\",\"isAutofill\":false,\"playerId\":-9},{\"mmr\":2101,\"teamIndex\":1,\"primaryLane\":\"jungle\",\"summonerName\":\"FZD Ratoso#fzd\",\"assignedLane\":\"jungle\",\"secondaryLane\":\"bot\",\"isAutofill\":false,\"playerId\":1786097},{\"mmr\":1986,\"teamIndex\":2,\"primaryLane\":\"mid\",\"summonerName\":\"Bot5\",\"assignedLane\":\"mid\",\"secondaryLane\":\"bot\",\"isAutofill\":false,\"playerId\":-5},{\"mmr\":1944,\"teamIndex\":3,\"primaryLane\":\"bot\",\"summonerName\":\"Bot6\",\"assignedLane\":\"bot\",\"secondaryLane\":\"jungle\",\"isAutofill\":false,\"playerId\":-6},{\"mmr\":1947,\"teamIndex\":4,\"primaryLane\":\"jungle\",\"summonerName\":\"Bot4\",\"assignedLane\":\"support\",\"secondaryLane\":\"support\",\"isAutofill\":false,\"playerId\":-4}],\"currentPhase\":\"completed\",\"currentActionType\":null,\"team2\":[{\"mmr\":1637,\"teamIndex\":5,\"primaryLane\":\"jungle\",\"summonerName\":\"Bot8\",\"assignedLane\":\"top\",\"secondaryLane\":\"jungle\",\"isAutofill\":false,\"playerId\":-8},{\"mmr\":1970,\"teamIndex\":6,\"primaryLane\":\"jungle\",\"summonerName\":\"Bot1\",\"assignedLane\":\"jungle\",\"secondaryLane\":\"support\",\"isAutofill\":false,\"playerId\":-1},{\"mmr\":1965,\"teamIndex\":7,\"primaryLane\":\"mid\",\"summonerName\":\"Bot7\",\"assignedLane\":\"mid\",\"secondaryLane\":\"jungle\",\"isAutofill\":false,\"playerId\":-7},{\"mmr\":1672,\"teamIndex\":8,\"primaryLane\":\"bot\",\"summonerName\":\"Bot2\",\"assignedLane\":\"bot\",\"secondaryLane\":\"jungle\",\"isAutofill\":false,\"playerId\":-2},{\"mmr\":871,\"teamIndex\":9,\"primaryLane\":\"jungle\",\"summonerName\":\"Bot3\",\"assignedLane\":\"support\",\"secondaryLane\":\"bot\",\"isAutofill\":false,\"playerId\":-3}],\"currentIndex\":20}', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL);

--
-- Índices para tabelas despejadas
--

--
-- Índices de tabela `custom_matches`
--
ALTER TABLE `custom_matches`
  ADD PRIMARY KEY (`id`),
  ADD KEY `idx_custom_matches_riot_game_id` (`riot_game_id`),
  ADD KEY `idx_custom_matches_status_completed` (`status`,`completed_at`);

--
-- AUTO_INCREMENT para tabelas despejadas
--

--
-- AUTO_INCREMENT de tabela `custom_matches`
--
ALTER TABLE `custom_matches`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=106;
COMMIT;

/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;

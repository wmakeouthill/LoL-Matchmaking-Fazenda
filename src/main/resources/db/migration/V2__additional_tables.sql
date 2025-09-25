-- Additional tables matching Node backend (partial)
CREATE TABLE IF NOT EXISTS queue_players (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  player_id BIGINT NOT NULL,
  summoner_name VARCHAR(255) NOT NULL UNIQUE,
  region VARCHAR(10) NOT NULL,
  custom_lp INT DEFAULT 0,
  primary_lane VARCHAR(20),
  secondary_lane VARCHAR(20),
  join_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  queue_position INT,
  is_active TINYINT DEFAULT 1,
  acceptance_status TINYINT DEFAULT 0,
  INDEX idx_queue_acceptance (acceptance_status)
);

CREATE TABLE IF NOT EXISTS discord_lol_links (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  discord_id VARCHAR(255) UNIQUE NOT NULL,
  discord_username VARCHAR(255) NOT NULL,
  game_name VARCHAR(255) NOT NULL,
  tag_line VARCHAR(10) NOT NULL,
  summoner_name VARCHAR(255) NOT NULL,
  verified TINYINT DEFAULT 0,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  last_used TIMESTAMP NULL
);

CREATE TABLE IF NOT EXISTS matches (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  match_id VARCHAR(255) UNIQUE NOT NULL,
  team1_players TEXT NOT NULL,
  team2_players TEXT NOT NULL,
  winner_team INT,
  average_mmr_team1 INT,
  average_mmr_team2 INT,
  mmr_changes TEXT,
  status VARCHAR(50) DEFAULT 'pending',
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  completed_at TIMESTAMP NULL,
  riot_game_id VARCHAR(255),
  actual_winner INT,
  actual_duration INT,
  riot_id VARCHAR(255)
);

-- settings already covered if needed; ensure acceptance index exists
CREATE INDEX IF NOT EXISTS idx_custom_status ON custom_matches(status);

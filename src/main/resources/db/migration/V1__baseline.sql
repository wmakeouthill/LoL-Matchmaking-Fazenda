-- Baseline schema reflecting current Node.js MySQL structure (simplified)
CREATE TABLE IF NOT EXISTS players (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    summoner_name VARCHAR(255) UNIQUE NOT NULL,
    summoner_id VARCHAR(255) UNIQUE,
    puuid VARCHAR(255) UNIQUE,
    region VARCHAR(10) NOT NULL,
    current_mmr INT DEFAULT 1000,
    peak_mmr INT DEFAULT 1000,
    games_played INT DEFAULT 0,
    wins INT DEFAULT 0,
    losses INT DEFAULT 0,
    win_streak INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    custom_mmr INT DEFAULT 1000,
    custom_peak_mmr INT DEFAULT 1000,
    custom_games_played INT DEFAULT 0,
    custom_wins INT DEFAULT 0,
    custom_losses INT DEFAULT 0,
    custom_win_streak INT DEFAULT 0,
    custom_lp INT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS custom_matches (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(255),
    description TEXT,
    team1_players TEXT NOT NULL,
    team2_players TEXT NOT NULL,
    winner_team INT,
    status VARCHAR(50) DEFAULT 'pending',
    created_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP NULL,
    game_mode VARCHAR(20) DEFAULT '5v5',
    duration INT,
    lp_changes TEXT,
    average_mmr_team1 INT,
    average_mmr_team2 INT,
    participants_data TEXT,
    riot_game_id VARCHAR(255),
    detected_by_lcu TINYINT DEFAULT 0,
    notes TEXT,
    custom_lp INT DEFAULT 0,
    updated_at TIMESTAMP NULL,
    pick_ban_data TEXT,
    linked_results TEXT,
    actual_winner INT,
    actual_duration INT,
    riot_id VARCHAR(255),
    mmr_changes TEXT,
    match_leader VARCHAR(255) DEFAULT NULL,
    owner_backend_id VARCHAR(100) DEFAULT NULL,
    owner_heartbeat BIGINT DEFAULT NULL
);
CREATE INDEX IF NOT EXISTS idx_owner_backend_id ON custom_matches(owner_backend_id);
CREATE INDEX IF NOT EXISTS idx_owner_heartbeat ON custom_matches(owner_heartbeat);
CREATE INDEX IF NOT EXISTS idx_custom_status ON custom_matches(status);

CREATE TABLE IF NOT EXISTS event_inbox (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(191) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    match_id BIGINT NULL,
    backend_id VARCHAR(100) NOT NULL,
    payload TEXT,
    timestamp BIGINT NOT NULL,
    received_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uniq_event_id (event_id),
    INDEX idx_event_type (event_type),
    INDEX idx_match_id (match_id),
    INDEX idx_backend (backend_id)
);

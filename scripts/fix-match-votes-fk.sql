-- Fix match_votes foreign key to point to custom_matches instead of matches

USE lolmatchmaking;

-- 1. Drop the old incorrect foreign key
ALTER TABLE match_votes DROP FOREIGN KEY fk_match_votes_match;

-- 2. Create new foreign key pointing to custom_matches
ALTER TABLE match_votes 
ADD CONSTRAINT fk_match_votes_custom_match 
FOREIGN KEY (match_id) REFERENCES custom_matches(id) 
ON DELETE CASCADE;

-- Verify
SHOW CREATE TABLE match_votes;

-- Per-song measured loudness (EBU R128 integrated, LUFS) used to equalize
-- playback volume between tracks from different masters.

CREATE TABLE IF NOT EXISTS music_song_loudness (
    platform VARCHAR(16) NOT NULL,
    song_id VARCHAR(64) NOT NULL,
    lufs FLOAT NOT NULL,
    analyzed_at DATETIME NOT NULL,
    PRIMARY KEY (platform, song_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

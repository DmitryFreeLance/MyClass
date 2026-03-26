CREATE TABLE IF NOT EXISTS bot_state (
  key TEXT PRIMARY KEY,
  value TEXT
);

CREATE TABLE IF NOT EXISTS users (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  max_user_id INTEGER NOT NULL UNIQUE,
  moyklass_user_id INTEGER,
  first_name TEXT,
  last_name TEXT,
  username TEXT,
  last_seen INTEGER
);

CREATE TABLE IF NOT EXISTS dialogs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  admin_id INTEGER NOT NULL,
  active INTEGER NOT NULL,
  started_at INTEGER NOT NULL,
  ended_at INTEGER
);

CREATE TABLE IF NOT EXISTS messages (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  dialog_id INTEGER NOT NULL,
  from_role TEXT NOT NULL,
  text TEXT NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS user_states (
  max_user_id INTEGER PRIMARY KEY,
  state TEXT NOT NULL,
  data TEXT,
  updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS user_children (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  max_user_id INTEGER NOT NULL,
  moyklass_user_id INTEGER NOT NULL,
  child_name TEXT,
  created_at INTEGER NOT NULL,
  UNIQUE(max_user_id, moyklass_user_id)
);

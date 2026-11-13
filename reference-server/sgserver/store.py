"""SQLite persistence: one JSON document per player, plus session tokens and device bindings."""
import json
import secrets
import sqlite3
import threading
import time


class Store:
    def __init__(self, path):
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.lock = threading.RLock()
        with self.lock:
            self.db.executescript('''
                CREATE TABLE IF NOT EXISTS users (id TEXT PRIMARY KEY, data TEXT NOT NULL, updated REAL);
                CREATE TABLE IF NOT EXISTS devices (device_id TEXT PRIMARY KEY, user_id TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS sessions (token TEXT PRIMARY KEY, user_id TEXT NOT NULL, created REAL);
                CREATE TABLE IF NOT EXISTS accounts (email TEXT PRIMARY KEY, password TEXT, user_id TEXT NOT NULL);
            ''')
            self.db.commit()

    def load_user(self, user_id):
        with self.lock:
            row = self.db.execute('SELECT data FROM users WHERE id=?', (user_id,)).fetchone()
        return json.loads(row[0]) if row else None

    def save_user(self, user):
        with self.lock:
            self.db.execute('INSERT OR REPLACE INTO users (id, data, updated) VALUES (?,?,?)',
                            (user['id'], json.dumps(user, separators=(',', ':')), time.time()))
            self.db.commit()

    def all_user_ids(self):
        with self.lock:
            return [r[0] for r in self.db.execute('SELECT id FROM users')]

    def user_for_device(self, device_id):
        with self.lock:
            row = self.db.execute('SELECT user_id FROM devices WHERE device_id=?', (device_id,)).fetchone()
        return row[0] if row else None

    def bind_device(self, device_id, user_id):
        with self.lock:
            self.db.execute('INSERT OR REPLACE INTO devices (device_id, user_id) VALUES (?,?)', (device_id, user_id))
            self.db.commit()

    def account(self, email):
        with self.lock:
            return self.db.execute('SELECT password, user_id FROM accounts WHERE email=?', (email.lower(),)).fetchone()

    def set_account(self, email, password, user_id):
        with self.lock:
            self.db.execute('INSERT OR REPLACE INTO accounts (email, password, user_id) VALUES (?,?,?)',
                            (email.lower(), password, user_id))
            self.db.commit()

    def new_session(self, user_id):
        token = secrets.token_hex(24)
        with self.lock:
            self.db.execute('INSERT INTO sessions (token, user_id, created) VALUES (?,?,?)', (token, user_id, time.time()))
            self.db.commit()
        return token

    def session_user(self, token):
        with self.lock:
            row = self.db.execute('SELECT user_id FROM sessions WHERE token=?', (token,)).fetchone()
        return row[0] if row else None

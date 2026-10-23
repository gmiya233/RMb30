"""Private per-group default-query preferences, not account ownership claims."""
import hashlib
from pathlib import Path
import re
import sqlite3
from contextlib import closing


class Bindings:
    def __init__(self, path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute('CREATE TABLE IF NOT EXISTS bindings (member TEXT PRIMARY KEY, qq TEXT NOT NULL)')
        self.path.chmod(0o600)

    @staticmethod
    def key(group, member):
        if not group or not member or len(group) > 256 or len(member) > 256:
            raise ValueError('Invalid member identity')
        return hashlib.sha256((group + '\0' + member).encode()).hexdigest()

    def get(self, group, member):
        with closing(sqlite3.connect(self.path)) as db, db:
            row = db.execute('SELECT qq FROM bindings WHERE member=?', (self.key(group, member),)).fetchone()
        return row[0] if row else None

    def set(self, group, member, qq):
        if not re.fullmatch('[1-9][0-9]{4,11}', qq):
            raise ValueError('用法：绑定 QQ号（5～12位数字，不能以0开头）')
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute('INSERT INTO bindings VALUES (?,?) ON CONFLICT(member) DO UPDATE SET qq=excluded.qq', (self.key(group, member), qq))

    def remove(self, group, member):
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute('DELETE FROM bindings WHERE member=?', (self.key(group, member),))

"""Cross-group query preferences for one bot application; not ownership claims."""
import hashlib
from pathlib import Path
import re
import sqlite3
from contextlib import closing


class Bindings:
    def __init__(self, path, legacy_groups=()):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute('CREATE TABLE IF NOT EXISTS bindings (member TEXT PRIMARY KEY, qq TEXT NOT NULL)')
            db.execute('CREATE TABLE IF NOT EXISTS shared_bindings (member TEXT PRIMARY KEY, qq TEXT)')
            db.execute('CREATE TABLE IF NOT EXISTS binding_groups (group_id TEXT PRIMARY KEY)')
            groups=set(legacy_groups)
            # Existing schedules retain known group IDs, allowing migration of the
            # old group+member hashes without ever storing a raw member identity.
            old=self.path.parent/'recommendations.sqlite3'
            if old.is_file():
                try:
                    with closing(sqlite3.connect(old)) as source:
                        groups.update(r[0] for r in source.execute('SELECT group_id FROM recommendations'))
                except sqlite3.Error:
                    pass
            for group in groups:
                if isinstance(group,str) and 0<len(group)<=256:
                    db.execute('INSERT OR IGNORE INTO binding_groups VALUES (?)',(group,))
        self.path.chmod(0o600)

    @staticmethod
    def key(group, member):
        if not group or not member or len(group) > 256 or len(member) > 256:
            raise ValueError('Invalid member identity')
        return hashlib.sha256((group + '\0' + member).encode()).hexdigest()

    def get(self, group, member):
        self.key(group,member)
        shared=self.shared_key(member)
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute('INSERT OR IGNORE INTO binding_groups VALUES (?)',(group,))
            row=db.execute('SELECT qq FROM shared_bindings WHERE member=?',(shared,)).fetchone()
            if row is not None:return row[0]  # NULL is an explicit global unbind.
            groups=[r[0] for r in db.execute('SELECT group_id FROM binding_groups')]
            values=set()
            for known in groups:
                old=db.execute('SELECT qq FROM bindings WHERE member=?',(self.key(known,member),)).fetchone()
                if old:values.add(old[0])
            if not values:return None
            qq=next(iter(values)) if len(values)==1 else None
            db.execute('INSERT INTO shared_bindings VALUES (?,?)',(shared,qq))
            return qq

    @staticmethod
    def shared_key(member):
        if not isinstance(member,str) or not 0<len(member)<=256:raise ValueError('Invalid member identity')
        return hashlib.sha256(('qqbot-member-v1\0'+member).encode()).hexdigest()

    def set(self, group, member, qq):
        if not re.fullmatch('[1-9][0-9]{4,11}', qq):
            raise ValueError('用法：绑定 QQ号（5～12位数字，不能以0开头）')
        with closing(sqlite3.connect(self.path)) as db, db:
            self.key(group,member)
            db.execute('INSERT OR IGNORE INTO binding_groups VALUES (?)',(group,))
            db.execute('INSERT INTO shared_bindings VALUES (?,?) ON CONFLICT(member) DO UPDATE SET qq=excluded.qq', (self.shared_key(member), qq))

    def remove(self, group, member):
        with closing(sqlite3.connect(self.path)) as db, db:
            self.key(group,member)
            db.execute('INSERT OR IGNORE INTO binding_groups VALUES (?)',(group,))
            db.execute('INSERT INTO shared_bindings VALUES (?,NULL) ON CONFLICT(member) DO UPDATE SET qq=NULL',(self.shared_key(member),))

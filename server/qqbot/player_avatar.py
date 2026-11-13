"""Bounded optional avatar data; invalid images never prevent score rendering."""
import base64
import hashlib
import json
from pathlib import Path


def load(root, qq, server):
    import re
    if not re.fullmatch('[1-9][0-9]{4,11}', qq): return None
    path = Path(root) / qq / 'player-avatar.json'
    try:
        if path.is_symlink() or path.stat().st_size > 1500000: return None
        doc = json.loads(path.read_bytes())
        if doc.get('schema') != 1 or doc.get('qq') != qq or doc.get('server') != server: return None
        data = base64.b64decode(doc.get('image', ''), validate=True)
        if not data or len(data) > 1048576 or hashlib.sha256(data).hexdigest() != doc.get('sha256'): return None
        return data
    except (OSError, ValueError, TypeError):
        return None

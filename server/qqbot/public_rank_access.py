"""Keep only published ranking directories traversable by read-only consumers."""
from pathlib import Path
import re
import stat


def ensure_public_access(rankings, revision):
    if not re.fullmatch('[a-f0-9]{64}', revision):
        raise ValueError('Invalid public revision')
    root = Path(rankings)
    if root.is_symlink():
        raise ValueError('Ranking root cannot be a symlink')
    for directory in (root, root/revision, root/revision/'charts'):
        if directory.is_symlink():
            raise ValueError('Public ranking directory cannot be a symlink')
        if not directory.exists():
            continue
        if not directory.is_dir():
            raise ValueError('Expected public ranking directory')
        permissions = stat.S_IMODE(directory.stat().st_mode)
        # Leave owner/group permissions intact; grant no public write access.
        if permissions & 0o005 != 0o005:
            directory.chmod(permissions | 0o005)

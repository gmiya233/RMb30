"""Systemd entry point; installation paths live in an external config."""
import json
import os
from pathlib import Path
import sys
from export_catalog import export

config = json.loads(Path('/etc/malody-ranking-paths.json').read_text())
catalogue = Path('/var/lib/malody-ranking/catalog.json')
if len(sys.argv) > 1 and sys.argv[1] == 'export':
    export(Path(config['database']), catalogue, Path(config['root']))
else:
    args = ['java', '-Xmx256m', '-cp',
            '/opt/malody-ranking/ranking-server.jar:/opt/malody-ranking/json.jar',
            'cn.gmiya.malodylink.RankingDaemon', config['root'], str(catalogue),
            '/var/lib/malody-ranking/state']
    if len(sys.argv) > 1:
        args.append('--' + sys.argv[1])
    os.execvp(args[0], args)

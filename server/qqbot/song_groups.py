"""Curated song groups for browsing. CID and scoring identity stay intact."""

SONG_GROUPS = {
    600029: ('Megaburn', (600169,)),
    600209: ('Air', (601111,)),
    600124: ('怪獣になりたい（想成为怪兽）', (600994,)),
    600982: ('来吧我的爱（VEM MEU AMOR）', (601104,)),
    601095: ('RAMBLING BLADE', (600100,)),
    601107: ('RPG', (601004,)),
}
CANONICAL_SIDS = {alternate: sid for sid, (_, alternates) in SONG_GROUPS.items() for alternate in alternates}
DUPLICATE_CIDS = {600229: 600037}


def canonical_sid(sid):
    return CANONICAL_SIDS.get(sid, sid)


def member_sids(sid):
    canonical = canonical_sid(sid)
    return (canonical, *SONG_GROUPS[canonical][1]) if canonical in SONG_GROUPS else (sid,)


def display_title(sid, fallback):
    group = SONG_GROUPS.get(canonical_sid(sid))
    return group[0] if group else fallback

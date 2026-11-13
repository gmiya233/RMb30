"""Extract official playlist URLs from received cards, never download arbitrary URLs."""
import json,re,html

def extract_playlist(*parts):
    from music_recommendations import playlist_id
    ids=set()
    def visit(value,depth=0):
        if depth>8:return
        if isinstance(value,dict):
            for child in list(value.values())[:100]:visit(child,depth+1)
        elif isinstance(value,list):
            for child in value[:100]:visit(child,depth+1)
        elif isinstance(value,str) and len(value)<=32768:
            clean=html.unescape(value.replace('\\/','/'))
            for url in re.findall(r'https://[^\s<>"\x27]+',clean):
                try:ids.add(playlist_id(url.rstrip('，。)）]')))
                except ValueError:pass
            if value.lstrip().startswith(('{','[')):
                try:visit(json.loads(value),depth+1)
                except ValueError:pass
    for part in parts:visit(part)
    return next(iter(ids)) if len(ids)==1 else None

def install_card_fields():
    # The pinned botpy SDK discards newer card fields. Retain only share-related data
    # in memory on group events, without logging raw messages or changing permissions.
    import botpy.connection as connection
    original=connection.GroupMessage
    if getattr(original,'retains_music_share',False):return
    class SharedGroupMessage(original):
        __slots__=('share_data',)
        retains_music_share=True
        def __init__(self,api,event_id,data):
            super().__init__(api,event_id,data)
            self.share_data={k:data[k] for k in ('ark_data','ark','msg_elements','embeds') if k in data}
    connection.GroupMessage=SharedGroupMessage

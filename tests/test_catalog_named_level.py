from pathlib import Path
import sys
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'server'))
from export_catalog import recover_named_level, export

class NamedLevelTests(unittest.TestCase):
    def test_export_keeps_raw_level_for_existing_app_revision(self):
        import json,sqlite3,tempfile
        root=Path(__file__).resolve().parents[1]/'build/catalog-level-tests'
        root.mkdir(parents=True,exist_ok=True)
        with tempfile.TemporaryDirectory(dir=root) as temporary:
            directory=Path(temporary);database=directory/'malody.db';target=directory/'catalog.json'
            with sqlite3.connect(database) as db:
                db.executescript('CREATE TABLE songs(sid,title,artist,promote,cover,background,bpm,exist); CREATE TABLE charts(cid,sid,hash,path,mc_name,version,mode,level,creator,exist);')
                db.execute('INSERT INTO songs VALUES(1,"Test","Artist",0,"","",180,1)')
                db.execute('INSERT INTO charts VALUES(1,1,"hash","absent","test.mc",?,7,0,"Mapper",1)',('大师9级(4k)',))
            db.close()
            target.write_text(json.dumps(dict(server='test',charts=[])),encoding='utf-8')
            export(database,target)
            row=json.loads(target.read_text(encoding='utf-8'))['charts'][0]
            self.assertEqual(row['level'],0)
            self.assertEqual(row['display_level'],9)
    def test_positive_named_levels(self):
        for name,value in [('大师9级(4k)',9),('大师10级(5k)',10),('(正)困难10级',10),('困难15级',15),('困难13级',13)]:
            row=dict(level=0,version=name);recover_named_level(row)
            self.assertEqual(row['level'],value)
    def test_existing_and_ambiguous_preserved(self):
        row=dict(level=12,version='大师9级');recover_named_level(row);self.assertEqual(row['level'],12)
        for name in ['(逆)简单-10级','简单－10级','简单−10级','4k','SC9','大师9级/10级','大师99级','大师16级']:
            row=dict(level=0,version=name);recover_named_level(row);self.assertEqual(row['level'],0,name)

if __name__=='__main__':unittest.main()

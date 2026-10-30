"""Three-way preference merge used for a later, explicitly requested TV transfer."""
import copy
import json
from pathlib import Path
import sys

def merge_preferences(current,base,patch):
    conflicts={key:{'base':base.get(key),'tv':current.get(key),'draft':value} for key,value in patch.items() if current.get(key)!=base.get(key) and current.get(key)!=value}
    if conflicts:return None,conflicts
    result=copy.deepcopy(current);result.update(patch)
    # Maintain the existing compatibility fields without replacing runtime history.
    if 'home' in patch:result['dock']=result['home'][:]
    if 'wall' in patch:result['wallpaper']=result['wall']
    if isinstance(result.get('look'),dict):
        for key in ['bg','wall','layout','apps','font','controls','focus','material']:
            if key in patch:result['look'][key]=patch[key]
    return result,{}
if __name__=='__main__':
    if len(sys.argv)!=4:raise SystemExit('Usage: editor_preferences.py CURRENT_TV.json RELEASE/preferences.json OUTPUT.json')
    current=json.loads(Path(sys.argv[1]).read_text());change=json.loads(Path(sys.argv[2]).read_text())
    merged,conflicts=merge_preferences(current,change['base'],change['patch'])
    if conflicts:print(json.dumps({'conflicts':conflicts},ensure_ascii=False,indent=2));raise SystemExit(2)
    from editor_server import atomic
    atomic(Path(sys.argv[3]),merged)

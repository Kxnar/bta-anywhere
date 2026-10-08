import json, subprocess
from pathlib import Path
root=Path.cwd()
spec=json.loads((root/'.local/demo-launch.json').read_text())
base=root/'.local/game-demo-2026-10-08'
base.mkdir(exist_ok=False)
for name in ('DemoHost','DemoGuest'):
    folder=base/name
    folder.mkdir()
    command=['java','-Xmx1536m',*spec['jvmArgs'],spec['main'],'--username',name,'--gameDir',str(folder)]
    with (base/(name+'.log')).open('wb') as log:
        proc=subprocess.Popen(command,cwd=folder,stdout=log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
    (base/(name+'.pid')).write_text(str(proc.pid))
    print(name,proc.pid)

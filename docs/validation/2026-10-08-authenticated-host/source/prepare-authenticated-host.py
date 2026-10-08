import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

archive = Path('.local/authenticated-host-input/bta_fabric_instance_8.0.1.zip')
instance = Path(os.environ['APPDATA']) / 'PrismLauncher/instances/BTAAnywhere-HostValidation-20261008'
instance.mkdir(exist_ok=False)
with zipfile.ZipFile(archive) as zipped:
    for member in zipped.infolist():
        target = instance / member.filename
        assert target.resolve().is_relative_to(instance.resolve())
        assert (member.external_attr >> 16) & 0o170000 != 0o120000
        if member.is_dir():
            target.mkdir(parents=True, exist_ok=True)
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(zipped.read(member))
mod = Path('bta-mod/build/libs/bta-anywhere-0.1.0+bta8.0.1.jar')
shutil.copyfile(mod, instance / 'minecraft/mods' / mod.name)
config = instance / 'instance.cfg'
text = config.read_text().replace('MaxMemAlloc=4096', 'MaxMemAlloc=1536').replace('AutomaticJava=true', 'AutomaticJava=false')
text += '\nname=BTA Anywhere - Disposable Host Validation\nOverrideJavaLocation=true\nJavaPath=C:/Program Files/Microsoft/jdk-21.0.12.101-hotspot/bin/javaw.exe\n'
config.write_text(text)
patch = instance / 'patches/net.minecraft.json'
data = json.loads(patch.read_text())
data['compatibleJavaMajors'] = [17, 21]
patch.write_text(json.dumps(data, indent=2))
receipt = {'instance': str(instance), 'source': 'https://github.com/Turnip-Labs/bta-fabric-instance-repo/releases/download/v8.0.1/bta_fabric_instance_8.0.1.zip',
           'archive_sha256': hashlib.sha256(archive.read_bytes()).hexdigest(),
           'mod_sha256': hashlib.sha256(mod.read_bytes()).hexdigest(),
           'changes': ['new display name', '1536 MiB heap', 'explicit local Java 21', 'compatibility list includes tested Java 21', 'built bundled mod added'],
           'existing_worlds_copied': False, 'existing_account_files_read_or_modified': False}
Path('.local/authenticated-host-input/preparation.json').write_text(json.dumps(receipt, indent=2))
print(json.dumps(receipt, indent=2))

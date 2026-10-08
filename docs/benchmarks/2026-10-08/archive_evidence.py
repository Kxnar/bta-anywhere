"""Publish numerical evidence while omitting workstation paths and hostnames."""
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[3]
source = root / 'benchmark-results' / '2026-10-08'
destination = root / 'docs' / 'benchmarks' / '2026-10-08' / 'results'
destination.mkdir(parents=True, exist_ok=True)
entries = []


def clean(text):
    for value in (str(root), root.as_posix(), root.as_posix().replace(' ', '%20')):
        text = text.replace(value, '<workspace>')
    for value in ('D:\\a\\bta-anywhere\\bta-anywhere', 'D:/a/bta-anywhere/bta-anywhere'):
        text = text.replace(value, '<ci-workspace>')
    text = re.sub(r'C:\\Users\\[^\\]+\\AppData\\Local\\Temp\\[^\\\s"<>]+', '<temporary-directory>', text)
    text = re.sub(r'C:/Users/[^/]+/AppData/Local/Temp/[^/\s"<>]+', '<temporary-directory>', text)
    text = re.sub(r'C:\\Users\\[^\\\s"<>]+', '<user-profile>', text)
    text = re.sub(r'C:/Users/[^/\s"<>]+', '<user-profile>', text)
    text = re.sub(r'(Session ID: )[0-9a-f-]{36}', r'\1<local-session>', text)
    text = re.sub(r'(rollout-\d{4}-\d{2}-\d{2}T\d{2}-\d{2}-\d{2}-)[0-9a-f-]{36}(\.jsonl)', r'\1<local-session>\2', text)
    text = re.sub(r'Command execution ID: exec-[0-9a-f-]+', 'Command execution ID: <local-execution>', text)
    # JUnit embeds the machine's hostname; reports have no reason to identify it.
    text = re.sub(r'hostname="[^"]*"', 'hostname="windows-loopback-host"', text)
    return text


def clean_json(value):
    if isinstance(value, str):
        return clean(value)
    if isinstance(value, list):
        return [clean_json(v) for v in value]
    if isinstance(value, dict):
        return {k: clean_json(v) for k, v in value.items()}
    return value


for path in sorted(source.rglob('*')):
    if not path.is_file() or path.suffix not in {'.json', '.md', '.txt', '.xml'}:
        continue
    if '.checkpoint.' in path.name:
        continue  # Final output includes its complete sample series.
    relative = path.relative_to(source)
    target = destination / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    raw = path.read_bytes()
    text = raw.decode('utf-8-sig')
    if path.suffix == '.json':
        published = json.dumps(clean_json(json.loads(text)), indent=2) + '\n'
    elif path.suffix == '.xml':
        tree = ET.fromstring(text)
        for element in tree.iter():
            for key, value in list(element.attrib.items()):
                element.set(key, 'windows-loopback-host' if key == 'hostname' else clean(value))
            if element.text:
                element.text = clean(element.text)
            if element.tail:
                element.tail = clean(element.tail)
        published = ET.tostring(tree, encoding='unicode') + '\n'
    else:
        published = clean(text).replace('\r\n', '\n')
        if path.suffix == '.txt':
            published = re.sub(r'[ \t]+$', '', published, flags=re.MULTILINE)
        if path.name == 'python-baseline-failure.txt':
            published = 'Published copy: local paths and execution identifiers are redacted. The original file hash is retained in archive-index.json.\n\n' + published
    target.write_text(published, encoding='utf-8', newline='\n')
    entries.append({'file': relative.as_posix(), 'original_sha256': hashlib.sha256(raw).hexdigest(),
                    'published_sha256': hashlib.sha256(target.read_bytes()).hexdigest()})

(destination.parent / 'archive-index.json').write_text(json.dumps({
    'note': 'Original files remain under ignored benchmark-results/2026-10-08. Published copies normalize UTF-8/LF, trim trailing log whitespace and omit workstation paths and JUnit hostnames. Numerical results and outcomes are unchanged. Checkpoints are superseded by final sample series.',
    'files': entries}, indent=2) + '\n', encoding='utf-8', newline='\n')
print(f'Archived {len(entries)} completed evidence files.')

#!/usr/bin/env python3
"""규칙 파일의 각 `&&`/`||` 줄(조건 하나)을 `true`/`false`로 무력화한 사본을 만든다. 사용: make-mutants.py <출력 폴더> [시작 줄]"""
import json, re, sys, os
out = sys.argv[1]; start = int(sys.argv[2]) if len(sys.argv) > 2 else 1
os.makedirs(out, exist_ok=True)
src = open(os.path.join(os.path.dirname(__file__), '..', 'firestore.rules'), encoding='utf-8').read().split('\n')
index = []
for i, line in enumerate(src):
    m = re.match(r'^(\s*)(&&|\|\|)\s+(.*)$', line)
    if not m or i + 1 < start: continue
    ind, op, t = m.groups()
    if t.rstrip().endswith('{'): continue
    cut = next((c for c in range(len(t), -1, -1)
                if t[:c].count('(') == t[:c].count(')') and t[:c].count('[') == t[:c].count(']')
                and all(ch in ');' for ch in t[c:])), None)
    if cut is None or t[:cut].strip() in ('true', 'false'): continue
    lines = src[:]
    lines[i] = f"{ind}{op} {'true' if op == '&&' else 'false'}{t[cut:]}"
    open(os.path.join(out, f'r{i + 1}.rules'), 'w', encoding='utf-8').write('\n'.join(lines))
    index.append((i + 1, line.strip()))
json.dump(index, open(os.path.join(out, 'index.json'), 'w', encoding='utf-8'), ensure_ascii=False)
print(len(index), 'mutants')

// 규칙 변이 검증: 규칙 파일의 각 `&&`/`||` 조건을 하나씩 무력화한 사본(MUT_DIR, index.json 포함)을 추적과 비교한다.
// 불일치가 없으면(= 살아남은 변이) 그 조건은 모델 시나리오로 검증되지 않는다. 사본은 make-mutants.py가 만든다.
// 사용: MUT_DIR=<사본 폴더> TRACE_DIR=<추적> npm run mutate
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { replayAll } from './replay.mjs';

const dir = process.env.TRACE_DIR;
const mutDir = process.env.MUT_DIR;
const index = JSON.parse(readFileSync(join(mutDir, 'index.json'), 'utf8'));
const files = readdirSync(mutDir).filter((f) => f.endsWith('.rules')).sort((a, b) => parseInt(a.slice(1)) - parseInt(b.slice(1)));
let killed = 0;
const survivors = [];
for (const f of files) {
  const line = parseInt(f.slice(1));
  const r = await replayAll(dir, join(mutDir, f), { stopAtFirst: true });
  if (r.mismatches.length) killed++;
  else survivors.push(line);
  process.stdout.write(r.mismatches.length ? '.' : 'S');
}
console.log(`\n변이 ${files.length}개 중 검출 ${killed}개, 살아남음 ${survivors.length}개`);
for (const line of survivors) console.log(`  L${line}: ${index.find(([n]) => n === line)?.[1]}`);

// 사용법: (core에서) gradle :remote:test -Pruletrace=<dir>  ->  TRACE_DIR=<dir> npm test
// 모델이 기록한 접근 판단을 같은 순서로 실제 규칙에 던져 허용/거부가 일치하는지 본다.
import { initializeTestEnvironment } from '@firebase/rules-unit-testing';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import firebase from 'firebase/compat/app';
import 'firebase/compat/firestore';

// 모델은 서버 시각을 표식 {"__serverTimestamp__": true}로 기록한다(core ServerValues). 실제 규칙에는 serverTimestamp()로 보낸다.
function resolve(value) {
  if (Array.isArray(value)) return value.map(resolve);
  if (value && typeof value === 'object') {
    const keys = Object.keys(value);
    if (keys.length === 1 && keys[0] === '__serverTimestamp__' && value.__serverTimestamp__ === true) {
      return firebase.firestore.FieldValue.serverTimestamp();
    }
    return Object.fromEntries(keys.map((k) => [k, resolve(value[k])]));
  }
  return value;
}

export async function replayAll(dir, rulesPath, { stopAtFirst = false } = {}) {
  const env = await initializeTestEnvironment({
    projectId: 'demo-coup',
    firestore: { rules: readFileSync(rulesPath, 'utf8'), host: '127.0.0.1', port: 8080 },
  });
  const denied = (e) => e && (e.code === 'permission-denied' || /PERMISSION_DENIED|permission/i.test(String(e.message)));

  function applyOps(db, ops) {
    const batch = db.batch();
    for (const o of ops) {
      const ref = db.doc(o.path);
      if (o.op === 'set') batch.set(ref, resolve(o.data));
      else if (o.op === 'merge') batch.set(ref, resolve(o.data), { merge: true });
      else batch.delete(ref);
    }
    return batch.commit();
  }

  async function run(ev) {
    if (ev.t === 'admin') {
      await env.withSecurityRulesDisabled(async (c) => applyOps(c.firestore(), ev.ops));
      return true;
    }
    const db = env.authenticatedContext(ev.auth.uid, ev.auth.email ? { email: ev.auth.email } : {}).firestore();
    try {
      if (ev.t === 'write') await applyOps(db, ev.ops);
      else if (ev.t === 'get') await db.doc(ev.path).get();
      else if (ev.t === 'query') {
        let q = db.collection(ev.path);
        if (ev.query?.where) q = q.where(ev.query.where[0], '==', ev.query.where[1]);
        if (ev.query?.orderBy) q = q.orderBy(ev.query.orderBy, ev.query.dir).limit(ev.query.limit);
        await q.get();
      }
      return true;
    } catch (e) {
      if (denied(e)) return false;
      throw e;
    }
  }


  const files = readdirSync(dir).filter((f) => f.endsWith('.jsonl')).sort();
  const mismatches = [];
  let events = 0;
  let denies = 0;
  outer: for (const f of files) {
    await env.clearFirestore();
    const lines = readFileSync(join(dir, f), 'utf8').trim().split('\n').map((l) => JSON.parse(l));
    const test = lines[0].test;
    for (let i = 1; i < lines.length; i++) {
      const ev = lines[i];
      const actual = await run(ev);
      events++;
      if (!ev.allowed) denies++;
      if (actual !== ev.allowed) {
        mismatches.push({ test, file: f, index: i, expected: ev.allowed ? '허용' : '거부', actual: actual ? '허용' : '거부', ev });
        if (stopAtFirst) break outer;
        break; // 이 시나리오의 이후 상태는 모델과 달라진다
      }
    }
  }
  await env.cleanup();
  return { scenarios: files.length, events, denies, mismatches };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const dir = process.env.TRACE_DIR;
  if (!dir) throw new Error('TRACE_DIR(규칙 추적 폴더)가 필요하다');
  const rules = process.env.RULES ?? new URL('../firestore.rules', import.meta.url).pathname;
  const r = await replayAll(dir, rules);
  console.log(`시나리오 ${r.scenarios}개, 판단 ${r.events}건(거부 ${r.denies}건) 중 불일치 ${r.mismatches.length}건`);
  for (const m of r.mismatches) {
    console.log(`\n✗ ${m.test} (#${m.index}) 모델=${m.expected} 규칙=${m.actual}\n  ${JSON.stringify(m.ev).slice(0, 700)}`);
  }
  process.exit(r.mismatches.length ? 1 : 0);
}

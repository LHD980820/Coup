// 모델과 무관하게 실제 규칙 엔진에서만 확인할 수 있는 동작들(모델은 단순화했거나 모르는 것).
import { initializeTestEnvironment } from '@firebase/rules-unit-testing';
import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const env = await initializeTestEnvironment({
  projectId: 'demo-coup',
  firestore: { rules: readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8'), host: '127.0.0.1', port: 8080 },
});
const as = (uid, email) => env.authenticatedContext(uid, email ? { email } : {}).firestore();
const denied = (e) => e && (e.code === 'permission-denied' || /PERMISSION_DENIED/i.test(String(e.message)));
async function result(promise) {
  try { await promise; return 'allowed'; } catch (e) { if (denied(e)) return 'denied'; throw e; }
}
const seed = (fn) => env.withSecurityRulesDisabled(async (c) => fn(c.firestore()));
const results = [];
const check = async (name, promise, expected, note = '') => {
  const actual = await result(promise);
  results.push({ name, expected, actual, note });
};

// 1. 아직 없는 명령 문서를 구독(get)하는 경우 — 모델은 허용으로 단순화했다. 실제 규칙은?
await env.clearFirestore();
await seed(async (db) => {
  await db.doc('games/g').set({ hostUid: 'h', seats: ['h', 'p'], status: 'PLAYING' });
});
await check('게스트가 아직 없는 자기 명령 문서를 get', as('p').doc('games/g/commands/c1').get(), 'allowed', '규칙이 resource == null을 명시적으로 허용한다(없으면 resource.data 접근 오류로 거부되던 것을 고침)');
await check('방장이 아직 없는 명령 문서를 get', as('h').doc('games/g/commands/c1').get(), 'allowed');
await as('p').doc('games/g/commands/c1').set({ senderUid: 'p', status: 'PENDING', commandJson: '{}', createdAt: 1 });
await check('게스트가 만든 뒤 자기 명령 문서를 get', as('p').doc('games/g/commands/c1').get(), 'allowed');
await check('다른 게스트가 남의 명령 문서를 get', as('q').doc('games/g/commands/c1').get(), 'denied');
await check('좌석이 아닌 사람이 없는 명령 문서를 get', as('outsider').doc('games/g/commands/zzz').get(), 'denied');

// 2. 닉네임 길이: 규칙의 size()는 UTF-16 단위인가 코드 포인트인가
await env.clearFirestore();
const profile = (nickname) => ({ nickname, rating: 1000, plays: 0, wins: 0, createdAt: 1, migratedFromLegacy: false });
await check('한글 12자 닉네임', as('u1').doc('users/u1').set(profile('가나다라마바사아자차카타')), 'allowed');
await check('한글 13자 닉네임', as('u2').doc('users/u2').set(profile('가나다라마바사아자차카타파')), 'denied');
// size()는 UTF-16 단위: 앱의 Nicknames(String.length)와 같아야 한다
await check('이모지 6개 닉네임(UTF-16 12)', as('u3').doc('users/u3').set(profile('😀'.repeat(6))), 'allowed');
await check('이모지 7개 닉네임(UTF-16 14)', as('u4').doc('users/u4').set(profile('😀'.repeat(7))), 'denied');
await check('이모지 1개 닉네임(UTF-16 2)', as('u5').doc('users/u5').set(profile('😀')), 'allowed');
await check('한 글자 닉네임', as('u6').doc('users/u6').set(profile('가')), 'denied');

// 3. 레이팅 표 범위 밖(7인 결과, 순위 범위 밖)에서 반영이 오류가 아닌 거부로 끝나는지
await env.clearFirestore();
await seed(async (db) => {
  await db.doc('users/a').set(profile('에이'));
  await db.doc('results/g7').set({ rated: true, ranking: ['a', 'b', 'c', 'd', 'e', 'f', 'g'], playerCount: 7, ratingDeltas: { a: 130 } });
});
await check('7인 결과 반영 시도', as('a').doc('users/a').update({ rating: 1130, plays: 1, wins: 1, lastAppliedResult: 'g7', lastAppliedRank: 0 }), 'denied');

await env.cleanup();
let bad = 0;
for (const r of results) {
  const ok = r.expected === r.actual;
  if (!ok) bad++;
  console.log(`${ok ? '✔' : '✗'} ${r.name}: 기대=${r.expected} 실제=${r.actual}${r.note ? `  (${r.note})` : ''}`);
}
process.exit(bad ? 1 : 0);

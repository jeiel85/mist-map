// 큰 기록 파일(몇 년 치 타임라인)을 읽고 경로를 채우는 일을 화면 밖에서 한다 (이슈 #2)
// 메시지: { id, file, dedupM } → { id, type: 'progress', fraction } … { id, type: 'done', result } | { id, type: 'error', message, importError }
import { planImport } from './importPlan.js';
import { ImportError } from './importers.js';

const EARTH_C = 40075016.686;

self.onmessage = async ({ data }) => {
  const { id, file, dedupM } = data;
  const k = EARTH_C / dedupM;
  const keyOf = (ll) => {
    const s = Math.sin((ll.lat * Math.PI) / 180);
    const my = 0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI);
    return `${Math.floor(((ll.lng + 180) / 360) * k)},${Math.floor(my * k)}`;
  };
  try {
    self.postMessage({ id, type: 'progress', fraction: 0 });
    const text = await file.text();
    const result = planImport(text, keyOf, (fraction) => self.postMessage({ id, type: 'progress', fraction }));
    self.postMessage({ id, type: 'done', result });
  } catch (e) {
    self.postMessage({ id, type: 'error', message: e.message, importError: e instanceof ImportError });
  }
};

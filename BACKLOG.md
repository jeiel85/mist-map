# Backlog

완료된 작업을 다시 열지 않고 나중에 볼 개선점. 항목당 무엇을 · 왜 · 영향 범위.

## 간단히 보기 · 30분 걷기 (2026-10-26)

- [ ] **멈춤 모드 GPS 비용** — 멈춤에서도 고정밀 1분 간격이라 오래 머물 때 배터리를 씀 · M3 Activity Recognition으로 멈춤을 감지하면 GPS를 끔 · `TrackingService.requestUpdates`
- [ ] **간단히 보기 진입 시간 설정** — 1분 고정 · 더 빨리/늦게 원하는 사용자 · `MapScreen.SIMPLE_AFTER_MS`

## M2 가져오기 (2026-10-26)

- [ ] **큰 파일 스트리밍 파싱** — 지금은 파일 전체를 문자열로 읽어 힙의 1/16(최대 32MB)에서 막음 · 몇 년 치 Records.json(수백 MB) 사용자 · `MapViewModel.readText`, `HistoryParser`
- [ ] **가져오기 진행률** — "읽는 중…" 문구만 있고 몇 %인지 없음 · 큰 파일 · `MapViewModel.importFrom`
- [ ] **이동 수단별 필터** — 1.5km 안쪽의 차량 이동도 길로 이어져 걷지 않은 곳이 밝혀짐(웹 이슈 #2와 같은 문제) · 구글 타임라인 activity 구간 · `ImportPlanner`
- [ ] **내보내기에 가져온 기록 포함 여부** — 지금 GPX는 걸어서 남긴 원시 위치만 담음 · 앱을 옮기려는 사용자 · `GpxExporter`

## M1 기록 (2026-10-26)

- [ ] **오프라인 렌더러** — MapLibre·글꼴을 CDN(unpkg·Google Fonts)에서 받아, 오프라인으로 앱을 열면 안개도 그리지 못하고 오류 카드만 뜸 · 설계 5장은 "오프라인이면 칸과 종이 안개만" · `index.html`, `android/` 자산 복사
- [ ] **화면 영역 칸만 보내기(`viewport`)** — 지금은 연결 때 칸 전체를 보냄 · 칸이 수십만 개로 늘면 첫 화면이 느려짐 · `MapViewModel.attach`, `src/main.js`
- [ ] **처음 연 화면 안내** — 위치 기록이 하나도 없으면 캐릭터 없이 빈 안개만 보임 · 첫 실행 사용자가 무엇을 할지 모름 · `MapScreen`
- [ ] **일시정지 상태 유지** — 일시정지 중 프로세스가 죽으면 세션이 "끊김"으로 안내됨 · 일시정지를 오래 두는 사용자 · `TrackingService`
- [ ] **칸 firstAt 기준 새 칸 감지** — 위치 시각(`fix.time`)이 앞선 칸은 화면이 열려 있는 동안 추가 표시가 빠질 수 있음(다음 실행 때는 보임) · 기기 시계 보정 직후 · `MapViewModel.attach`

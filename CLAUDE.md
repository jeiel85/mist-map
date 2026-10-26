# Mist Map

걸은 만큼 안개가 걷히는 손그림 지도. Life in Mist(iOS) 같은 앱을 직접 만들어 보는 프로젝트다.
사용자가 가장 중요하게 보는 것은 **예쁜가**다. 기능을 더하기 전에 화면이 여전히 예쁜지 스크린샷으로 확인한다.

- 저장소: https://github.com/jeiel85/mist-map (공개, main에 직접 커밋)
- 라이브 데모: https://jeiel85.github.io/mist-map/ (main 루트를 GitHub Pages로 배포, 빌드 없음, 푸시 후 1~2분)

## 현재 상태 (2026-10-26)

- **웹 프로토타입 완료·배포됨**: 등고선 안개 셰이더, 손그림 지도 스타일, 새싹 젤리 캐릭터, 걷기·산책·GPS, 기록 가져오기(GPX·KML·GeoJSON·구글 타임라인), `localStorage` 저장
- **앱 설계 완료, 구현 전**: [docs/app-architecture.md](docs/app-architecture.md), [DECISIONS.md](DECISIONS.md)의 D1~D4는 모두 **제안** 상태다. 사용자가 확정하기 전에는 Android 코드를 만들지 않는다.
- 열린 이슈: #1 `Hardening candidates: v0.1.0`, #2 `Hardening candidates: v0.2.0`

## 다음 단계

1. 사용자에게 D1~D4 확정 여부를 묻는다 (수정 요청이 있으면 설계 문서와 DECISIONS.md를 먼저 고친다).
2. 확정되면 **M1 기록**: `android/` 프로젝트 생성 → 포그라운드 위치 서비스·필터 → Room 칸 저장 → WebView 렌더러 앱 모드 → 오늘 거리 HUD.
   완료 기준: 실기기에서 화면을 끈 채 30분 걷고 앱을 열면 그 길이 걷혀 있음.
3. M1에 필요한 웹 쪽 선행 작업: 앱 모드(칸 목록 입력, 웹 HUD 숨김), 유휴 시 렌더 중단(이슈 #1 첫 항목).

## 구조

```
index.html          HUD 마크업, 가져오기 시트
src/main.js         상태·이동·밝히기·저장·가져오기·HUD (진입점)
src/fog.js          안개 커스텀 레이어 (WebGL2 2패스: 가우시안 필드 → 거리 기반 합성)
src/mapStyle.js     OpenFreeMap 벡터 타일 손그림 팔레트 스타일
src/importers.js    기록 파서 (DOM 없이 동작, Node 테스트 가능)
src/style.css       HUD·캐릭터·시트·애니메이션
tests/              파서 단위 테스트 (node --test)
tools/              헤드리스 Chrome 검증·스크린샷 스크립트
samples/            가져오기 샘플 (서울숲 GPX, 구글 타임라인)
docs/               README 스크린샷, 앱 설계 문서
```

## 실행과 검증

```bash
npm start                          # http://localhost:5174 정적 서버
npm test                           # 파서 단위 테스트
npm run e2e                        # 가져오기 흐름 점검 (서버가 떠 있어야 함)
node tools/e2e-import.cjs https://jeiel85.github.io/mist-map/   # 배포본 점검
npm run screenshots                # docs/ 스크린샷 다시 찍기
```

- **화면 확인은 `tools/`의 헤드리스 Chrome으로 한다.** 데스크톱 앱 내장 브라우저 패널은 백그라운드일 때 requestAnimationFrame이 멈춰서 애니메이션과 걷기가 진행되지 않고, 스크린샷에 중간 프레임(안개·HUD 없는 지도)이 찍힌다.
- `?debug`를 붙이면 `window.__mist`(`state`, `update`, `keys`, `map`, `importFiles`)가 노출된다. `update(1/60)`을 반복 호출해 이동을 시뮬레이션한다.
- 셰이더를 고치면 줌 15.0·15.7·16.2와 모바일(390px)에서 스크린샷을 찍어 확인한다. 실제로 있었던 문제: 블롭 쿼드 경계의 직선 자국, 축소 시 링이 굵은 띠로 뭉침.

## 관례

- 문서·주석·UI 문구는 한국어. 커밋 메시지도 한국어 제목 + 본문 목록.
- 렌더링 상수(반경 55m, 샘플 간격 24m, 중복 격자 18m)는 `src/main.js` 상단에 모여 있다. 바꾸면 저장된 기록의 모양도 바뀐다.
- 지도 타일·폰트·MapLibre는 외부 CDN(OpenFreeMap, Google Fonts, unpkg `maplibre-gl@4.7.1`)에서 받는다.
- 위치 기록은 기기 밖으로 보내지 않는다. 새 기능도 이 원칙을 깨지 않는다.

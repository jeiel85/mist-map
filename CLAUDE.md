# Mist Map

반딧불 반디와 걸으면 밤안개가 걷히는 동네 지도. Life in Mist(iOS) 같은 "걸으면 걷히는 지도"를 직접 만들어 보는 프로젝트다.
**표현은 우리 것으로 한다(2026-10-26 결정):** 처음 프로토타입은 참고 앱의 화면(종이 위 등고선 안개, 노란 젤리 캐릭터, 잉크 외곽선 지도)을 거의 그대로 따랐다. 지금은 남색 밤안개·별·등불 빛 번짐, 반딧불 캐릭터 반디, 복숭아·살구 톤 지도로 바꿨다. 참고 앱의 캐릭터·질감·구도를 다시 가져오지 않는다.
사용자가 가장 중요하게 보는 것은 **예쁜가**다. 기능을 더하기 전에 화면이 여전히 예쁜지 스크린샷으로 확인한다.

- 저장소: https://github.com/jeiel85/mist-map (공개, main에 직접 커밋)
- 라이브 데모: https://jeiel85.github.io/mist-map/ (main 루트를 GitHub Pages로 배포, 빌드 없음, 푸시 후 1~2분)

## 현재 상태 (2026-10-26)

- **웹 프로토타입 완료·배포됨**: 밤안개 셰이더(별·등불 번짐), 등불빛 지도 스타일, 반딧불 캐릭터 반디, 걷기·산책·GPS, 기록 가져오기(GPX·KML·GeoJSON·구글 타임라인), `localStorage` 저장
- **D1~D4 확정(2026-10-26), M1 기록 구현됨**: `android/`에 포그라운드 위치 서비스·필터·Room 칸 저장·WebView 렌더러(`?app` 모드)·오늘 거리 HUD. 에뮬레이터에서 화면을 끈 채 걷고 앱을 열면 길이 걷혀 있는 것까지 확인. 설계와 달라진 점은 [docs/app-architecture.md](docs/app-architecture.md) 9장.
- 열린 이슈: #1 `Hardening candidates: v0.1.0`(유휴 렌더 중단은 M1에서 처리), #2 `Hardening candidates: v0.2.0`

## 다음 단계

1. M1 완료 기준은 사용자 요청으로 **에뮬레이터 30분 걷기로 확인**했다(설계 문서 11장, 멈춤 모드 버그를 찾아 고침). 배터리 %는 실기기에서만 잴 수 있어 M3에서 본다.
2. M2 가져오기 완료·배포(v0.4.0, 설계 문서 10장). 사용자가 샘플 확인으로 완료를 받아들였다(2026-10-26). 실제 구글 타임라인 파일을 받으면 그때 한 번 더 확인한다.
   - 파서를 고치면 웹 `src/importers.js`와 `HistoryParser.kt`, 두 쪽 테스트를 함께 고친다.
3. M3 하루 종일 기록 구현(설계 문서 12장): 활동 인식으로 쉬는 동안 GPS 끄기, 끊김 알림, 오늘 기록 상태, 절전 예외 안내. **완료 기준(8시간 연속, 시간당 4%/1%)은 실기기에서 재야 한다.**
4. 남은 항목은 [BACKLOG.md](BACKLOG.md).

## 구조

```
index.html          HUD 마크업, 가져오기 시트
src/main.js         상태·이동·밝히기·저장·가져오기·HUD (진입점)
src/fog.js          안개 커스텀 레이어 (WebGL2 2패스: 가우시안 필드 → 거리 기반 합성)
src/mapStyle.js     OpenFreeMap 벡터 타일 등불빛 팔레트 스타일
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
node tools/app-mode.cjs            # 앱 모드(?app) 브리지 점검 (서버가 떠 있어야 함)
npm test                           # 파서 단위 테스트
npm run e2e                        # 가져오기 흐름 점검 (서버가 떠 있어야 함)
node tools/e2e-import.cjs https://jeiel85.github.io/mist-map/   # 배포본 점검
npm run screenshots                # docs/ 스크린샷 다시 찍기
```

- **화면 확인은 `tools/`의 헤드리스 Chrome으로 한다.** 데스크톱 앱 내장 브라우저 패널은 백그라운드일 때 requestAnimationFrame이 멈춰서 애니메이션과 걷기가 진행되지 않고, 스크린샷에 중간 프레임(안개·HUD 없는 지도)이 찍힌다.
- `?debug`를 붙이면 `window.__mist`(`state`, `update`, `keys`, `map`, `importFiles`)가 노출된다. `update(1/60)`을 반복 호출해 이동을 시뮬레이션한다.
- 셰이더를 고치면 줌 15.0·15.7·16.2와 모바일(390px)에서 스크린샷을 찍어 확인한다. 실제로 있었던 문제: 블롭 쿼드 경계의 직선 자국(빛 번짐은 D < 1.9 안에서 사라져야 함), 축소 시 링이 굵은 띠로 뭉침(등고선 시절).

### Android (`android/`)

```bash
cd android
./gradlew testDebugUnitTest assembleDebug     # 단위 테스트 + 디버그 APK
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- 웹 렌더러는 빌드 때 루트 `index.html`·`src/`를 `assets/web/`으로 복사한다(`syncWebAssets`). 웹을 고치면 앱도 다시 빌드해야 반영된다.
- 앱 ↔ 웹 메시지 형식은 `src/main.js`의 "앱 브리지" 주석과 `RendererBridge.kt`가 짝이다. 한쪽을 바꾸면 다른 쪽과 `tools/app-mode.cjs`도 고친다.
- 에뮬레이터 검증: `adb emu geo fix <lng> <lat>`를 3초마다 6m씩 옮기며 걷고, `adb shell input keyevent KEYCODE_SLEEP`으로 화면을 끈다. 잠금 해제 스와이프가 지도를 끌어 따라가기가 풀릴 수 있다.
- WebView 디버깅: 디버그 빌드는 원격 디버깅이 켜져 있다. `adb forward tcp:9333 localabstract:webview_devtools_remote_<pid>` 후 CDP로 평가한다. 웹 콘솔은 logcat `MistMap` 태그로도 나온다.
- 릴리스 서명: 업로드 키는 `android/.keystore/`(git 제외)에 있고(로컬 원본: 메인 체크아웃 `D:\Project\mist-map\android\.keystore\`), 백업은 `G:\내 드라이브\모바일 앱 개발\Mist Map\릴리즈 키스토어`. 비밀번호는 `release-signing.properties`에만 있다.

## 관례

- 문서·주석·UI 문구는 한국어. 커밋 메시지도 한국어 제목 + 본문 목록.
- 렌더링 상수(반경 55m, 샘플 간격 24m, 중복 격자 18m)는 `src/main.js` 상단에 모여 있다. 바꾸면 저장된 기록의 모양도 바뀐다.
- 지도 타일·폰트·MapLibre는 외부 CDN(OpenFreeMap, Google Fonts, unpkg `maplibre-gl@4.7.1`)에서 받는다.
- 위치 기록은 기기 밖으로 보내지 않는다. 새 기능도 이 원칙을 깨지 않는다.

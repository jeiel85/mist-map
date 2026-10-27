# Mist Map

반딧불 반디와 걸으면 밤안개가 걷히는 동네 지도. Life in Mist(iOS) 같은 "걸으면 걷히는 지도"를 직접 만들어 보는 프로젝트다.
**표현은 우리 것으로 한다(2026-10-26 결정):** 처음 프로토타입은 참고 앱의 화면(종이 위 등고선 안개, 노란 젤리 캐릭터, 잉크 외곽선 지도)을 거의 그대로 따랐다. 지금은 남색 밤안개·별·등불 빛 번짐, 반딧불 캐릭터 반디, 복숭아·살구 톤 지도로 바꿨다. 참고 앱의 캐릭터·질감·구도를 다시 가져오지 않는다.
사용자가 가장 중요하게 보는 것은 **예쁜가**다. 기능을 더하기 전에 화면이 여전히 예쁜지 스크린샷으로 확인한다.

- 저장소: https://github.com/jeiel85/mist-map (공개, main에 직접 커밋)
- 라이브 데모: https://jeiel85.github.io/mist-map/ (main 루트를 GitHub Pages로 배포, 빌드 없음, 푸시 후 1~2분)

## 현재 상태 (2026-10-27)

- **웹 프로토타입 배포됨**: 밤안개 셰이더(별·등불 번짐), 등불빛 지도, 반딧불 반디, 걷기·산책·GPS, 기록 가져오기(워커·진행률·파일별 되돌리기·차량 구간 제외), `localStorage` v2 압축 저장
- **앱(`android/`)**: M1 기록, M2 가져오기(스트리밍·진행률·취소·되돌리기/되살리기), 간단히 보기(진입 시간 설정), M3 하루 종일(활동 인식·끊김 알림·오늘 기록 상태)까지 구현. 설계와 달라진 점은 [docs/app-architecture.md](docs/app-architecture.md) 9~14장. 기록이 끊긴 곳은 기록 모음에서 눌러 지도로 볼 수 있다(0.8.0).
- **하드닝 이슈 #1·#2·#4·#6·#8을 한 번에 정리**(설계 문서 13장). 실기기 확인은 사용자 요청으로 에뮬레이터로 대신했다.
- 스토어 출시는 사용자 계정 문제로 보류, 1주일 실사용은 제외(2026-10-26 사용자 결정).

## 다음 단계

1. 남은 항목은 [BACKLOG.md](BACKLOG.md): 실기기 배터리 실측, 실제 구글 타임라인 파일로 형식 검증 등 에뮬레이터·샘플로 할 수 없는 것만 남았다.
   - 파서를 고치면 웹 `src/importers.js`와 `HistoryParser.kt`, 두 쪽 테스트를 함께 고친다.
2. 스토어 출시는 사용자가 계정 문제를 풀면 진행한다(릴리스 서명 키는 준비됨).

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
node tools/context-loss.cjs        # WebGL 컨텍스트 손실·복구 뒤에도 안개가 그려지는지
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

- CI(`.github/workflows/ci.yml`): PR·main 푸시마다 `npm test`, 헤드리스 Chrome 점검(`tools/`의 가져오기·앱 모드·컨텍스트 복구, 스크린샷은 `browser-shots` 아티팩트), Android 단위 테스트·lint·디버그 빌드.

## 관례

- 문서·주석·UI 문구는 한국어. 커밋 메시지도 한국어 제목 + 본문 목록.
- 렌더링 상수(반경 55m, 샘플 간격 24m, 중복 격자 18m)는 `src/main.js` 상단에 모여 있다. 바꾸면 저장된 기록의 모양도 바뀐다.
- MapLibre GL JS 4.7.1은 `vendor/`에 함께 둔다(오프라인 앱·CDN 변조 방지, BSD-3 라이선스 동봉). 지도 타일·폰트는 외부(OpenFreeMap, Google Fonts)에서 받고, 오프라인이면 밤안개와 밝힌 칸만 보인다.
- 위치 기록은 기기 밖으로 보내지 않는다. 새 기능도 이 원칙을 깨지 않는다.

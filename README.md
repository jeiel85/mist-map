<div align="center">

# ☁️ Mist Map

**걸은 만큼 안개가 걷히는 손그림 지도**

종이 위 등고선 안개를 헤치며 동네를 밝혀 나가는 탐험 지도의 웹 프로토타입입니다.

### [▶ 라이브 데모 열기](https://jeiel85.github.io/mist-map/)

<img src="docs/screenshot.png" alt="Mist Map 데스크톱 화면: 등고선 안개 가운데로 손그림 지도가 드러나 있다" width="820" />

</div>

## 이런 걸 할 수 있어요

- **걸으면 걷히는 안개** — 지나간 자리마다 안개가 스프링처럼 부드럽게 열리고, 둘레의 등고선이 물결처럼 밀려납니다.
- **손그림 일러스트 지도** — 잉크 외곽선을 두른 노란 건물, 크림색 길, 분홍 점선 산책로. 라벨 없이 그림책처럼 그렸습니다.
- **새싹 젤리 캐릭터** — 서 있을 땐 숨 쉬듯, 걸을 땐 통통 뛰며 따라다닙니다.
- **탐험 기록** — 밝힌 면적, 걸은 거리, 발자국 수를 보여 주고, 다시 열면 가까운 곳부터 차례로 기억이 걷힙니다.

<img src="docs/screenshot-mobile.png" alt="모바일 화면" width="260" align="right" />

## 조작

| 동작 | 방법 |
| --- | --- |
| 걷기 / 달리기 | `W` `A` `S` `D` 또는 방향키 / `Shift` |
| 원하는 곳까지 이동 | 지도 클릭 (모바일은 탭) |
| 자동 산책 | **산책** 버튼 |
| 실제로 걸으며 밝히기 | **내 위치** 버튼 (위치 권한 필요) |
| 처음부터 다시 | **초기화** 버튼 |

기록은 사용 중인 브라우저의 `localStorage`에만 저장되고, 위치 정보는 어떤 서버로도 보내지 않습니다.

<br clear="right" />

## 어떻게 그리나요

안개는 MapLibre 커스텀 레이어 위에서 WebGL2 셰이더 두 번으로 그립니다.

1. **밝음 필드** — 밝힌 지점마다 가우시안 블롭을 float 텍스처에 더합니다. 가까운 지점끼리는 자연스럽게 녹아 붙습니다.
2. **안개 합성** — 필드로 경계까지의 거리를 추정해 칸을 나눕니다.
   - 안쪽: 지도가 보이고, 가장자리에 옅은 그림자가 져 움푹 드러난 느낌을 줍니다.
   - 경계: 손으로 그린 듯 살짝 흔들리는 잉크선.
   - 바깥: 경계에서 멀어질수록 간격이 넓어지는 등고선 링, 그 너머엔 노이즈로 만든 지형 등고선과 종이 질감.
3. **걷힘 애니메이션** — 새 지점의 반경을 살짝 넘쳤다 자리 잡는 스프링 곡선으로 키우면, 링들이 따라 밀려나며 안개가 걷힙니다.

## 로컬에서 실행

빌드 과정 없는 정적 사이트입니다. ES 모듈을 쓰므로 정적 서버로 엽니다.

```bash
python -m http.server 5174
```

`http://localhost:5174`를 열면 됩니다. URL에 `?debug`를 붙이면 콘솔에서 `window.__mist`로 이동을 시뮬레이션할 수 있습니다.

**요구 사항:** WebGL2와 float 렌더 타깃을 지원하는 브라우저 (최신 Chrome · Edge · Safari · Firefox)

## 구조

```
index.html        HUD 마크업
src/main.js       캐릭터 이동, 밝히기, 저장, HUD
src/fog.js        안개 커스텀 레이어 (WebGL2 2패스)
src/mapStyle.js   종이·잉크 팔레트 지도 스타일
src/style.css     HUD · 캐릭터 · 애니메이션
```

## 상태

모바일 앱으로 옮기기 전에 안개 표현의 느낌을 확정하기 위한 **프로토타입**입니다. 배경 위치 추적, 배터리 최적화, 계정·동기화는 아직 없습니다.

## 크레딧

- 지도 데이터 © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors
- 벡터 타일 [OpenFreeMap](https://openfreemap.org) · 스키마 [OpenMapTiles](https://openmaptiles.org)
- 렌더링 [MapLibre GL JS](https://maplibre.org)
- 글꼴 [Gaegu](https://fonts.google.com/specimen/Gaegu), [Gowun Dodum](https://fonts.google.com/specimen/Gowun+Dodum)

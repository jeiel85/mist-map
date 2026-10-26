// 등불에 비친 밤 동네 지도 스타일 (OpenFreeMap · OpenMapTiles 스키마)
// 라벨 없이 면·선만으로 구성하고, 반딧불 빛이 닿은 곳처럼 따뜻한 살구·복숭아 톤으로 칠한다.

export const INK = '#4a3b5c'; // 밤의 자두빛 선

const PALETTE = {
  land: '#fcefe0',
  residential: '#f8e6d4',
  grass: '#d3e6c4',
  wood: '#bcd8b0',
  park: '#c9e2b8',
  parkEdge: '#7da77a',
  water: '#acd2ea',
  waterEdge: '#5b86b0',
  road: '#fffaf3',
  path: '#eaa53e',
  rail: '#7a6a88',
  buildingShadow: '#e7cdbd',
  buildingPeach: '#f7b79b',
  buildingApricot: '#f9cfa8',
  buildingTall: '#ddd3ea',
};

const roadWidth = (base) => [
  'interpolate', ['exponential', 1.6], ['zoom'],
  12, base * 0.35,
  15, base * 1.4,
  18, base * 6,
];

const MAJOR = ['motorway', 'trunk', 'primary', 'secondary', 'tertiary'];
const MINOR = ['minor', 'service', 'street', 'residential'];

export function createMapStyle() {
  return {
    version: 8,
    name: 'mist-map-lantern',
    sources: {
      omt: { type: 'vector', url: 'https://tiles.openfreemap.org/planet' },
    },
    layers: [
      { id: 'land', type: 'background', paint: { 'background-color': PALETTE.land } },

      {
        id: 'landuse-residential', type: 'fill', source: 'omt', 'source-layer': 'landuse',
        filter: ['in', ['get', 'class'], ['literal', ['residential', 'suburb', 'neighbourhood']]],
        paint: { 'fill-color': PALETTE.residential },
      },
      {
        id: 'landcover-grass', type: 'fill', source: 'omt', 'source-layer': 'landcover',
        filter: ['in', ['get', 'class'], ['literal', ['grass', 'farmland', 'wetland']]],
        paint: { 'fill-color': PALETTE.grass },
      },
      {
        id: 'landcover-wood', type: 'fill', source: 'omt', 'source-layer': 'landcover',
        filter: ['==', ['get', 'class'], 'wood'],
        paint: { 'fill-color': PALETTE.wood },
      },
      {
        id: 'park', type: 'fill', source: 'omt', 'source-layer': 'park',
        paint: { 'fill-color': PALETTE.park },
      },
      {
        id: 'park-edge', type: 'line', source: 'omt', 'source-layer': 'park',
        paint: { 'line-color': PALETTE.parkEdge, 'line-width': 1, 'line-dasharray': [3, 2], 'line-opacity': 0.7 },
      },

      {
        id: 'water', type: 'fill', source: 'omt', 'source-layer': 'water',
        paint: { 'fill-color': PALETTE.water },
      },
      {
        id: 'water-edge', type: 'line', source: 'omt', 'source-layer': 'water',
        paint: { 'line-color': PALETTE.waterEdge, 'line-width': ['interpolate', ['linear'], ['zoom'], 12, 0.6, 17, 1.6] },
      },
      {
        id: 'waterway', type: 'line', source: 'omt', 'source-layer': 'waterway',
        paint: { 'line-color': PALETTE.waterEdge, 'line-width': 1.2, 'line-opacity': 0.6 },
      },

      // 도로: 자두빛 테두리(casing) 위에 크림색 채움
      {
        id: 'road-minor-casing', type: 'line', source: 'omt', 'source-layer': 'transportation',
        filter: ['in', ['get', 'class'], ['literal', MINOR]],
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': INK, 'line-width': roadWidth(1.6), 'line-opacity': 0.85 },
      },
      {
        id: 'road-major-casing', type: 'line', source: 'omt', 'source-layer': 'transportation',
        filter: ['in', ['get', 'class'], ['literal', MAJOR]],
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': INK, 'line-width': roadWidth(2.8) },
      },
      {
        id: 'road-minor', type: 'line', source: 'omt', 'source-layer': 'transportation',
        filter: ['in', ['get', 'class'], ['literal', MINOR]],
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': PALETTE.road, 'line-width': roadWidth(1.2) },
      },
      {
        id: 'road-major', type: 'line', source: 'omt', 'source-layer': 'transportation',
        filter: ['in', ['get', 'class'], ['literal', MAJOR]],
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': PALETTE.road, 'line-width': roadWidth(2.3) },
      },
      {
        id: 'path', type: 'line', source: 'omt', 'source-layer': 'transportation',
        filter: ['in', ['get', 'class'], ['literal', ['path', 'track']]],
        layout: { 'line-cap': 'round' },
        paint: {
          'line-color': PALETTE.path,
          'line-width': ['interpolate', ['linear'], ['zoom'], 14, 1, 18, 2.6],
          'line-dasharray': [1.5, 1.8],
        },
      },
      {
        id: 'rail', type: 'line', source: 'omt', 'source-layer': 'transportation',
        filter: ['in', ['get', 'class'], ['literal', ['rail', 'transit']]],
        paint: { 'line-color': PALETTE.rail, 'line-width': 1.4, 'line-dasharray': [4, 3] },
      },

      // 건물: 살짝 어긋난 그림자 + 복숭아·살구 채움 + 자두빛 외곽선
      {
        id: 'building-shadow', type: 'fill', source: 'omt', 'source-layer': 'building', minzoom: 14,
        paint: {
          'fill-color': PALETTE.buildingShadow,
          'fill-translate': ['interpolate', ['linear'], ['zoom'], 14, ['literal', [0.5, 0.5]], 18, ['literal', [3, 3]]],
        },
      },
      {
        id: 'building', type: 'fill', source: 'omt', 'source-layer': 'building', minzoom: 13,
        paint: {
          'fill-color': [
            'case',
            ['>', ['coalesce', ['get', 'render_height'], 0], 30], PALETTE.buildingTall,
            ['==', ['%', ['to-number', ['id'], 0], 3], 1], PALETTE.buildingApricot,
            PALETTE.buildingPeach,
          ],
        },
      },
      {
        id: 'building-outline', type: 'line', source: 'omt', 'source-layer': 'building', minzoom: 14,
        paint: {
          'line-color': INK,
          'line-width': ['interpolate', ['linear'], ['zoom'], 14, 0.3, 16, 0.9, 18, 1.8],
        },
      },
    ],
  };
}

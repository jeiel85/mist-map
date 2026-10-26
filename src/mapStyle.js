// 손그림 일러스트 느낌의 지도 스타일 (OpenFreeMap · OpenMapTiles 스키마)
// 라벨 없이 면·선만으로 구성해 안개가 걷혔을 때 그림책처럼 보이도록 한다.

export const INK = '#3d342b';

const PALETTE = {
  land: '#f9f0dc',
  residential: '#f5e9cf',
  grass: '#dde6b6',
  wood: '#cbdba0',
  park: '#d5e2a8',
  parkEdge: '#8fa36a',
  water: '#bcdcd9',
  waterEdge: '#5f8f8f',
  road: '#fffaf0',
  path: '#e58f8f',
  rail: '#7a6a58',
  buildingShadow: '#e0cfae',
  buildingYellow: '#f6cd6a',
  buildingButter: '#f8dc93',
  buildingTall: '#e9e0cc',
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
    name: 'mist-map-paper',
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

      // 도로: 잉크 테두리(casing) 위에 크림색 채움
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

      // 건물: 살짝 어긋난 그림자 + 노란 채움 + 잉크 외곽선
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
            ['==', ['%', ['to-number', ['id'], 0], 3], 1], PALETTE.buildingButter,
            PALETTE.buildingYellow,
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

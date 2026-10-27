// 안개 레이어 (MapLibre custom layer, WebGL2)
//
// 1패스: 밝힌 지점마다 가우시안 블롭을 float 텍스처에 가산 합성 → "밝음 필드"
// 2패스: 필드에서 경계까지의 거리 D를 추정해
//        - D < 0  : 지도 노출 (가장자리에 따뜻한 그늘)
//        - D ≈ 0  : 밝은 불빛 테
//        - D > 0  : 남색 밤안개 + 별, 경계 가까이는 등불 빛이 번지고 안개가 옅어짐
// 블롭 반경이 스프링으로 커지면 불빛이 밀려나며 밤안개가 걷힌다.

const BLOB_EXTENT = 3.5; // 블롭 쿼드 반경 (블롭 반지름 배수). exp(-12.25)에서 잘림
// 쿼드 경계에서 필드가 끊기므로 빛 번짐은 그보다 안쪽(D < 1.9)에서 사라져야 직선 자국이 생기지 않는다

const BLOB_VS = `#version 300 es
in vec2 a_corner;
in vec3 a_inst; // 화면 px(x, y, 좌상단 기준), 반지름 px
uniform vec2 u_res;
out vec2 v_local;
void main() {
  v_local = a_corner * ${BLOB_EXTENT.toFixed(1)};
  vec2 p = a_inst.xy + v_local * a_inst.z;
  vec2 clip = p / u_res * 2.0 - 1.0;
  gl_Position = vec4(clip.x, -clip.y, 0.0, 1.0);
}`;

const BLOB_FS = `#version 300 es
precision highp float;
in vec2 v_local;
out vec4 o;
void main() {
  o = vec4(exp(-dot(v_local, v_local)), 0.0, 0.0, 1.0);
}`;

const FOG_VS = `#version 300 es
in vec2 a_corner;
void main() { gl_Position = vec4(a_corner, 0.0, 1.0); }`;

const FOG_FS = `#version 300 es
precision highp float;
uniform sampler2D u_field;
uniform vec2 u_res;
uniform vec2 u_origin;  // 세계 원점의 화면 위치(디바이스 px, 좌상단 기준)
uniform float u_ppm;    // 디바이스 px / m
uniform float u_dpr;
uniform float u_time;
out vec4 o;

const vec3 NIGHT = vec3(0.098, 0.125, 0.239);  // 깊은 밤 남색
const vec3 MIST = vec3(0.208, 0.247, 0.420);   // 흘러가는 밤안개
const vec3 GLOW = vec3(1.0, 0.690, 0.330);     // 등불이 번진 빛
const vec3 RIM = vec3(1.0, 0.937, 0.769);      // 경계의 가장 밝은 테
const vec3 STAR = vec3(0.886, 0.902, 1.0);
const vec3 EMBER = vec3(0.329, 0.196, 0.106);  // 드러난 땅 가장자리의 따뜻한 그늘

float hash12(vec2 p) {
  vec3 p3 = fract(vec3(p.xyx) * 0.1031);
  p3 += dot(p3, p3.yzx + 33.33);
  return fract((p3.x + p3.y) * p3.z);
}
float noise(vec2 p) {
  vec2 i = floor(p), f = fract(p);
  vec2 u = f * f * (3.0 - 2.0 * f);
  return mix(mix(hash12(i), hash12(i + vec2(1, 0)), u.x),
             mix(hash12(i + vec2(0, 1)), hash12(i + vec2(1, 1)), u.x), u.y);
}
float fbm(vec2 p) {
  float v = 0.0, a = 0.5;
  for (int i = 0; i < 4; i++) { v += a * noise(p); p = p * 2.03 + 17.1; a *= 0.5; }
  return v;
}
vec4 over(vec4 top, vec4 bottom) { return top + bottom * (1.0 - top.a); }

// 지도와 함께 움직이되 줌과 상관없이 같은 밀도로 깔리는 별
float stars(vec2 sp) {
  vec2 g = sp / 26.0;
  vec2 cell = floor(g);
  float r = hash12(cell);
  if (r < 0.9) return 0.0;
  vec2 pos = cell + 0.2 + 0.6 * vec2(hash12(cell + 7.3), hash12(cell + 19.1));
  float d = length(g - pos) * 26.0;
  float size = mix(0.7, 1.6, fract(r * 37.0));
  float twinkle = 0.55 + 0.45 * sin(u_time * (0.8 + 2.4 * fract(r * 13.0)) + r * 40.0);
  return smoothstep(size + 0.9, 0.0, d) * twinkle;
}

void main() {
  vec2 uv = gl_FragCoord.xy / u_res;
  vec2 px = vec2(gl_FragCoord.x, u_res.y - gl_FragCoord.y);
  vec2 w = (px - u_origin) / u_ppm; // 미터 단위 세계 좌표

  // 경계까지 거리 (블롭 반지름 단위). 가장자리가 연기처럼 흔들리고 아주 천천히 일렁인다.
  // 크게 축소하면 흔들림 무늬가 화면 px보다 잘아져 경계가 모래처럼 깨지므로 줌에 따라 줄인다
  float f = texture(u_field, uv).r;
  float wob = (fbm(w / 70.0 + u_time * 0.015) - 0.5) * 0.32 * smoothstep(0.01, 0.04, u_ppm)
            + (noise(w / 9.0 - u_time * 0.08) - 0.5) * 0.07 * smoothstep(0.05, 0.2, u_ppm);
  float D = sqrt(max(-log(max(f, 1e-6)), 0.0)) - 0.8326 + wob;
  float dPx = D / max(fwidth(D), 1e-5); // 경계까지 화면 px

  float fog = smoothstep(-1.0, 1.0, dPx);

  // 밤하늘: 남색 위로 큰 안개 덩어리가 천천히 흘러간다. 크게 축소해도 덩어리가 화면에서 모래알만 해지지 않게 한다
  vec2 wc = (px - u_origin) / max(u_ppm, 0.12 * u_dpr);
  float m = fbm(wc / 460.0 + vec2(u_time * 0.006, u_time * 0.002));
  float swirl = fbm(wc / 150.0 - vec2(u_time * 0.012, 0.0));
  vec3 fogCol = mix(NIGHT, MIST, smoothstep(0.42, 0.78, m) * 0.7 + (swirl - 0.5) * 0.18);
  float vig = smoothstep(1.2, 0.3, length((uv - 0.5) * vec2(u_res.x / u_res.y, 1.0)));
  fogCol *= mix(0.78, 1.0, vig);
  fogCol += STAR * stars((px - u_origin) / u_dpr) * smoothstep(0.7, 1.8, D) * 0.9;

  // 등불이 번진 빛: 경계 바깥으로 따뜻하게 퍼지고 숨 쉬듯 밝아졌다 어두워진다
  float breathe = 0.88 + 0.12 * sin(u_time * 1.1);
  float glow = exp(-max(D, 0.0) * 3.4) * breathe;
  fogCol = mix(fogCol, GLOW, glow * 0.5);
  // 경계 가까이에선 안개가 옅어져 그 너머의 지도가 어렴풋이 비친다
  float alpha = fog * mix(0.66, 1.0, smoothstep(0.0, 1.1, D));
  vec4 fogLayer = vec4(fogCol * alpha, alpha);

  // 경계의 밝은 테
  float rim = 1.0 - smoothstep(0.5 * u_dpr, 0.5 * u_dpr + 2.2, abs(dPx));
  vec4 rimLayer = vec4(RIM, 1.0) * rim * 0.8 * breathe;

  // 드러난 땅의 가장자리는 불빛이 닿는 끝처럼 살짝 어둡고 따뜻하게
  float shade = (1.0 - fog) * smoothstep(-0.5, 0.0, D) * 0.2;
  vec4 shadeLayer = vec4(EMBER, 1.0) * shade;

  o = over(rimLayer, over(fogLayer, shadeLayer));
}`;

function compile(gl, type, src) {
  const s = gl.createShader(type);
  gl.shaderSource(s, src);
  gl.compileShader(s);
  if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) {
    throw new Error(`셰이더 컴파일 실패: ${gl.getShaderInfoLog(s)}`);
  }
  return s;
}

function program(gl, vs, fs) {
  const p = gl.createProgram();
  gl.attachShader(p, compile(gl, gl.VERTEX_SHADER, vs));
  gl.attachShader(p, compile(gl, gl.FRAGMENT_SHADER, fs));
  gl.linkProgram(p);
  if (!gl.getProgramParameter(p, gl.LINK_STATUS)) {
    throw new Error(`셰이더 링크 실패: ${gl.getProgramInfoLog(p)}`);
  }
  return p;
}

/**
 * @param {() => {blobs: Float32Array, count: number, origin: [number, number], ppm: number, time: number}} getFrame
 *   매 프레임 블롭 목록(CSS px x, y, 반지름)과 세계 원점 화면 위치(CSS px), CSS px/m를 돌려준다.
 */
export function createFogLayer(getFrame) {
  let blobProg, fogProg, quadBuf, instBuf, blobVao, fogVao, fbo, tex;
  let fboW = 0, fboH = 0;
  let instData = new Float32Array(0);

  function ensureTarget(gl, w, h) {
    if (w === fboW && h === fboH) return;
    fboW = w; fboH = h;
    gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA16F, w, h, 0, gl.RGBA, gl.HALF_FLOAT, null);
    gl.bindFramebuffer(gl.FRAMEBUFFER, fbo);
    gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, tex, 0);
    if (gl.checkFramebufferStatus(gl.FRAMEBUFFER) !== gl.FRAMEBUFFER_COMPLETE) {
      throw new Error('안개 필드 프레임버퍼를 만들 수 없습니다 (RGBA16F 렌더 미지원)');
    }
  }

  return {
    id: 'mist',
    type: 'custom',
    renderingMode: '2d',

    onAdd(map, gl) {
      if (!(gl instanceof WebGL2RenderingContext)) {
        throw new Error('이 브라우저는 WebGL2를 지원하지 않아 안개를 그릴 수 없습니다');
      }
      if (!gl.getExtension('EXT_color_buffer_float') && !gl.getExtension('EXT_color_buffer_half_float')) {
        throw new Error('이 기기는 float 렌더 타깃을 지원하지 않아 안개를 그릴 수 없습니다');
      }
      blobProg = program(gl, BLOB_VS, BLOB_FS);
      fogProg = program(gl, FOG_VS, FOG_FS);

      quadBuf = gl.createBuffer();
      gl.bindBuffer(gl.ARRAY_BUFFER, quadBuf);
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
      instBuf = gl.createBuffer();

      blobVao = gl.createVertexArray();
      gl.bindVertexArray(blobVao);
      const aCorner = gl.getAttribLocation(blobProg, 'a_corner');
      gl.bindBuffer(gl.ARRAY_BUFFER, quadBuf);
      gl.enableVertexAttribArray(aCorner);
      gl.vertexAttribPointer(aCorner, 2, gl.FLOAT, false, 0, 0);
      const aInst = gl.getAttribLocation(blobProg, 'a_inst');
      gl.bindBuffer(gl.ARRAY_BUFFER, instBuf);
      gl.enableVertexAttribArray(aInst);
      gl.vertexAttribPointer(aInst, 3, gl.FLOAT, false, 0, 0);
      gl.vertexAttribDivisor(aInst, 1);

      fogVao = gl.createVertexArray();
      gl.bindVertexArray(fogVao);
      const aCorner2 = gl.getAttribLocation(fogProg, 'a_corner');
      gl.bindBuffer(gl.ARRAY_BUFFER, quadBuf);
      gl.enableVertexAttribArray(aCorner2);
      gl.vertexAttribPointer(aCorner2, 2, gl.FLOAT, false, 0, 0);
      gl.bindVertexArray(null);

      tex = gl.createTexture();
      gl.bindTexture(gl.TEXTURE_2D, tex);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      fbo = gl.createFramebuffer();
    },

    render(gl) {
      const frame = getFrame();
      const w = gl.drawingBufferWidth, h = gl.drawingBufferHeight;
      const dpr = w / gl.canvas.clientWidth || 1;
      const prevFbo = gl.getParameter(gl.FRAMEBUFFER_BINDING);
      const prevViewport = gl.getParameter(gl.VIEWPORT);

      gl.disable(gl.DEPTH_TEST);
      gl.disable(gl.STENCIL_TEST);
      gl.disable(gl.CULL_FACE);
      gl.disable(gl.SCISSOR_TEST);
      gl.colorMask(true, true, true, true);

      // 1패스: 밝음 필드
      ensureTarget(gl, w, h);
      gl.bindFramebuffer(gl.FRAMEBUFFER, fbo);
      gl.viewport(0, 0, w, h);
      gl.clearColor(0, 0, 0, 0);
      gl.clear(gl.COLOR_BUFFER_BIT);
      if (frame.count > 0) {
        if (instData.length < frame.count * 3) instData = new Float32Array(frame.count * 3 * 2);
        for (let i = 0; i < frame.count * 3; i++) instData[i] = frame.blobs[i] * dpr;
        gl.bindBuffer(gl.ARRAY_BUFFER, instBuf);
        gl.bufferData(gl.ARRAY_BUFFER, instData.subarray(0, frame.count * 3), gl.DYNAMIC_DRAW);
        gl.useProgram(blobProg);
        gl.uniform2f(gl.getUniformLocation(blobProg, 'u_res'), w, h);
        gl.enable(gl.BLEND);
        gl.blendFunc(gl.ONE, gl.ONE);
        gl.bindVertexArray(blobVao);
        gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, frame.count);
      }

      // 2패스: 안개 합성 (premultiplied)
      gl.bindFramebuffer(gl.FRAMEBUFFER, prevFbo);
      gl.viewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
      gl.useProgram(fogProg);
      gl.activeTexture(gl.TEXTURE0);
      gl.bindTexture(gl.TEXTURE_2D, tex);
      gl.uniform1i(gl.getUniformLocation(fogProg, 'u_field'), 0);
      gl.uniform2f(gl.getUniformLocation(fogProg, 'u_res'), w, h);
      gl.uniform2f(gl.getUniformLocation(fogProg, 'u_origin'), frame.origin[0] * dpr, frame.origin[1] * dpr);
      gl.uniform1f(gl.getUniformLocation(fogProg, 'u_ppm'), frame.ppm * dpr);
      gl.uniform1f(gl.getUniformLocation(fogProg, 'u_dpr'), dpr);
      gl.uniform1f(gl.getUniformLocation(fogProg, 'u_time'), frame.time);
      gl.enable(gl.BLEND);
      gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
      gl.bindVertexArray(fogVao);
      gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
      gl.bindVertexArray(null);
    },
  };
}

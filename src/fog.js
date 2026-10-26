// 안개 레이어 (MapLibre custom layer, WebGL2)
//
// 1패스: 밝힌 지점마다 가우시안 블롭을 float 텍스처에 가산 합성 → "밝음 필드"
// 2패스: 필드에서 경계까지의 거리 D를 추정해
//        - D < 0  : 지도 노출 (경계 안쪽에 옅은 그림자)
//        - D ≈ 0  : 잉크 경계선
//        - D > 0  : 종이 + 경계를 감싸는 등고선 링 + 멀리 지형 등고선
// 블롭 반경이 스프링으로 커지면 링들이 물결처럼 밀려나며 안개가 걷힌다.

const BLOB_EXTENT = 3.0; // 블롭 쿼드 반경 (블롭 반지름 배수). exp(-9)에서 잘림

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

const vec3 PAPER = vec3(0.957, 0.922, 0.851);
const vec3 INK = vec3(0.24, 0.204, 0.169);
const vec3 SOFT_INK = vec3(0.56, 0.49, 0.40);

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
// 정수 등치선까지의 화면 거리 기반 안티앨리어싱 선
float isoLine(float k, float widthPx) {
  float w = fwidth(k);
  float d = abs(fract(k + 0.5) - 0.5) / max(w, 1e-5);
  return 1.0 - smoothstep(widthPx * 0.5, widthPx * 0.5 + 1.0, d);
}
vec4 over(vec4 top, vec4 bottom) { return top + bottom * (1.0 - top.a); }

void main() {
  vec2 uv = gl_FragCoord.xy / u_res;
  vec2 px = vec2(gl_FragCoord.x, u_res.y - gl_FragCoord.y);
  vec2 w = (px - u_origin) / u_ppm; // 미터 단위 세계 좌표

  // 경계까지 거리 (블롭 반지름 단위). 손그림처럼 흔들리고 아주 천천히 숨 쉰다.
  float f = texture(u_field, uv).r;
  float wob = (fbm(w / 85.0) - 0.5) * 0.26
            + (noise(w / 11.0 + u_time * 0.05) - 0.5) * 0.06
            + sin(u_time * 0.7 + fbm(w / 60.0) * 6.283) * 0.015;
  float D = sqrt(max(-log(max(f, 1e-6)), 0.0)) - 0.8326 + wob;
  float dPx = D / max(fwidth(D), 1e-5); // 경계까지 화면 px

  float fog = smoothstep(-0.7, 0.7, dPx);

  // 종이: 큰 얼룩 + 미세 그레인 + 비네트
  vec3 paper = PAPER * (0.965 + 0.06 * fbm(w / 380.0));
  paper += (hash12(floor(px / u_dpr)) - 0.5) * 0.03;
  float vig = smoothstep(1.15, 0.35, length((uv - 0.5) * vec2(u_res.x / u_res.y, 1.0)));
  paper *= mix(0.93, 1.0, vig);

  // 멀리 깔린 지형 등고선 (경계 근처에선 링에게 자리를 내준다)
  float h = fbm(w / 520.0 + 3.7) + 0.35 * fbm(w / 170.0);
  float k = h * 11.0;
  float bold = step(abs(mod(floor(k + 0.5), 5.0)), 0.5);
  float terr = isoLine(k, (0.9 + bold * 0.8) * u_dpr);
  terr *= smoothstep(0.45, 0.12, fwidth(k)); // 축소 시 무아레 방지
  terr *= smoothstep(0.35, 1.9, D) * (0.22 + bold * 0.14);

  // 경계를 감싸는 링: 경계 가까이 촘촘하고 멀어질수록 넓어진다
  float t = sqrt(max(D, 0.0) / 2.1) * 6.0;
  float ring = isoLine(t, 1.1 * u_dpr) * step(0.5, t) * (1.0 - smoothstep(0.5, 2.05, D)) * 0.75;

  vec3 fogCol = paper;
  fogCol = mix(fogCol, SOFT_INK, terr);
  fogCol = mix(fogCol, INK, ring);
  vec4 fogLayer = vec4(fogCol, 1.0) * fog;

  // 경계 잉크선
  float edge = 1.0 - smoothstep(1.1 * u_dpr, 1.1 * u_dpr + 1.2, abs(dPx));
  vec4 edgeLayer = vec4(INK, 1.0) * edge;

  // 안쪽 그림자: 안개 아래로 움푹 드러난 느낌
  float shade = (1.0 - fog) * smoothstep(-0.45, 0.0, D) * 0.22;
  vec4 shadeLayer = vec4(vec3(0.42, 0.33, 0.22), 1.0) * shade;

  o = over(edgeLayer, over(fogLayer, shadeLayer));
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

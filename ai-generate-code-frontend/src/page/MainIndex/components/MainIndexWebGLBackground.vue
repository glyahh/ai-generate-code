<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import * as THREE from 'three'

const hostRef = ref<HTMLElement | null>(null)

let renderer: THREE.WebGLRenderer | null = null
let scene: THREE.Scene | null = null
let camera: THREE.OrthographicCamera | null = null
let mesh: THREE.Mesh<THREE.PlaneGeometry, THREE.ShaderMaterial> | null = null
let frameId = 0
let prefersReducedMotion = false

const mouse = new THREE.Vector2(0.5, 0.5)
const uniforms = {
  u_time: { value: 0 },
  u_mouse: { value: mouse.clone() },
  u_resolution: { value: new THREE.Vector2(1, 1) },
  u_intensity: { value: 0 },
}

const vertexShader = `
  varying vec2 vUv;
  void main() {
    vUv = uv;
    gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
  }
`

const fragmentShader = `
  precision highp float;
  uniform float u_time;
  uniform vec2 u_mouse;
  uniform vec2 u_resolution;
  uniform float u_intensity;
  varying vec2 vUv;

  float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
  }

  void main() {
    vec2 uv = vUv;
    vec3 deep = vec3(0.019, 0.047, 0.04);
    vec3 mid = vec3(0.032, 0.082, 0.065);
    vec3 top = vec3(0.02, 0.055, 0.045);
    vec3 col = mix(deep, mid, uv.y * 1.12);
    col = mix(col, top, smoothstep(0.32, 1.0, uv.y) * 0.48);

    float t = u_time * 0.12;
    vec2 c1 = vec2(0.2 + 0.07 * sin(t), 0.68 + 0.06 * cos(t * 0.85));
    vec2 c2 = vec2(0.82 + 0.06 * cos(t * 0.72), 0.22 + 0.05 * sin(t * 0.93));
    float a1 = smoothstep(0.58, 0.0, distance(uv, c1)) * 0.16;
    float a2 = smoothstep(0.52, 0.0, distance(uv, c2)) * 0.13;
    col += vec3(0.07, 0.42, 0.26) * a1;
    col += vec3(0.1, 0.38, 0.3) * a2;

    vec2 m = u_mouse;
    float dg = smoothstep(0.48, 0.0, distance(uv, m));
    col += vec3(0.09, 0.36, 0.22) * dg * (0.06 + 0.2 * u_intensity);

    vec2 px = uv * u_resolution.xy;
    vec2 fq = floor(px * 0.38);
    float n = hash(fq + floor(u_time * 18.0));
    col += (n - 0.5) * 0.032;

    vec2 b = mod(floor(px * 0.25), 4.0);
    float bayer = fract(sin(dot(b, vec2(12.9898, 78.233))) * 43758.5453);
    col += (bayer - 0.5) * 0.014;

    float scan = sin(uv.y * u_resolution.y * 0.22 + u_time * 0.45) * 0.006 + 0.997;
    col *= scan;

    float vig = smoothstep(1.08, 0.32, distance(uv, vec2(0.5)));
    col *= mix(0.9, 1.0, vig);

    gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
  }
`

function initScene() {
  const host = hostRef.value
  if (!host) return

  prefersReducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches

  scene = new THREE.Scene()
  camera = new THREE.OrthographicCamera(-1, 1, 1, -1, 0.1, 10)
  camera.position.z = 1

  renderer = new THREE.WebGLRenderer({ antialias: true, alpha: false })
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
  renderer.setClearColor(0x030a08, 1)
  host.appendChild(renderer.domElement)

  const material = new THREE.ShaderMaterial({
    uniforms,
    vertexShader,
    fragmentShader,
  })

  mesh = new THREE.Mesh(new THREE.PlaneGeometry(2, 2), material)
  scene.add(mesh)

  syncSize()
  renderer.render(scene, camera)
}

function syncSize() {
  if (!renderer) return
  const w = Math.max(window.innerWidth, 1)
  const h = Math.max(window.innerHeight, 1)
  renderer.setSize(w, h, false)
  uniforms.u_resolution.value.set(w, h)
}

function handleResize() {
  syncSize()
}

function handlePointerMove(event: PointerEvent) {
  const w = Math.max(window.innerWidth, 1)
  const h = Math.max(window.innerHeight, 1)
  const x = event.clientX / w
  const y = event.clientY / h
  uniforms.u_mouse.value.set(Math.min(Math.max(x, 0), 1), 1 - Math.min(Math.max(y, 0), 1))
  uniforms.u_intensity.value = Math.min(uniforms.u_intensity.value + 0.06, 1)
}

function handlePointerLeave() {
  uniforms.u_intensity.value = Math.max(uniforms.u_intensity.value - 0.22, 0)
}

function animate(time: number) {
  if (!renderer || !scene || !camera) return
  const t = time * 0.001
  uniforms.u_time.value = prefersReducedMotion ? t * 0.04 : t
  uniforms.u_intensity.value = Math.max(uniforms.u_intensity.value * 0.985, 0)
  renderer.render(scene, camera)
  frameId = window.requestAnimationFrame(animate)
}

function dispose() {
  if (frameId) window.cancelAnimationFrame(frameId)
  window.removeEventListener('resize', handleResize)
  window.removeEventListener('pointermove', handlePointerMove)
  window.removeEventListener('pointerleave', handlePointerLeave)

  if (mesh) {
    mesh.geometry.dispose()
    mesh.material.dispose()
    scene?.remove(mesh)
    mesh = null
  }

  if (renderer) {
    renderer.dispose()
    const host = hostRef.value
    if (host && renderer.domElement.parentNode === host) {
      host.removeChild(renderer.domElement)
    }
  }

  renderer = null
  scene = null
  camera = null
}

onMounted(() => {
  initScene()
  window.addEventListener('resize', handleResize)
  window.addEventListener('pointermove', handlePointerMove)
  window.addEventListener('pointerleave', handlePointerLeave)
  frameId = window.requestAnimationFrame(animate)
})

onBeforeUnmount(() => {
  dispose()
})
</script>

<template>
  <div ref="hostRef" class="main-index-webgl-bg" aria-hidden="true" />
</template>

<style scoped>
.main-index-webgl-bg {
  position: fixed;
  inset: 0;
  z-index: 0;
  pointer-events: none;
  overflow: hidden;
}

.main-index-webgl-bg :deep(canvas) {
  display: block;
  width: 100%;
  height: 100%;
}
</style>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import * as THREE from 'three'

interface NavItem {
  label: string
}

const props = withDefaults(
  defineProps<{
    brandText?: string
    titleText?: string
    navItems?: NavItem[]
  }>(),
  {
    brandText: 'Glyahh',
    titleText: 'AI GENERATE CODE',
    navItems: () => [],
  },
)

const shellRef = ref<HTMLElement | null>(null)
const canvasHostRef = ref<HTMLElement | null>(null)

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

  void main() {
    vec2 uv = vUv;
    vec3 deepBase = vec3(0.05, 0.11, 0.10);
    vec3 softBase = vec3(0.10, 0.18, 0.15);
    vec3 tone = mix(deepBase, softBase, uv.y * 0.8);

    float pulse = 0.5 + 0.5 * sin(u_time * 0.6);
    vec2 drift = vec2(sin(u_time * 0.18), cos(u_time * 0.15)) * 0.04;
    vec2 cursorPos = u_mouse + drift;
    float d = distance(uv, cursorPos);
    float glow = smoothstep(0.55, 0.02, d) * (0.32 + 0.46 * u_intensity);
    vec3 glowColor = mix(vec3(0.15, 0.70, 0.45), vec3(0.13, 0.86, 0.39), pulse);

    float vignette = smoothstep(1.02, 0.12, distance(uv, vec2(0.5)));
    vec3 color = tone + glowColor * glow;
    color *= mix(0.75, 1.0, vignette);

    gl_FragColor = vec4(color, 1.0);
  }
`

/**
 * 初始化 Three.js 画布与着色器场景。
 * 输入：无（依赖已挂载的 DOM 容器）。
 * 输出：创建 renderer/scene/camera/mesh 并渲染首帧。
 * 边界：容器不存在时直接返回，避免空引用。
 */
function initThreeScene() {
  const shellEl = shellRef.value
  const hostEl = canvasHostRef.value
  if (!shellEl || !hostEl) return

  prefersReducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches

  scene = new THREE.Scene()
  camera = new THREE.OrthographicCamera(-1, 1, 1, -1, 0.1, 10)
  camera.position.z = 1

  renderer = new THREE.WebGLRenderer({ antialias: true, alpha: false })
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
  renderer.setClearColor(0x0f1714, 1)
  hostEl.appendChild(renderer.domElement)

  const material = new THREE.ShaderMaterial({
    uniforms,
    vertexShader,
    fragmentShader,
  })

  mesh = new THREE.Mesh(new THREE.PlaneGeometry(2, 2), material)
  scene.add(mesh)

  handleResize()
  renderer.render(scene, camera)
}

/**
 * 让画布尺寸与容器同步，避免高 DPI 下模糊或拉伸。
 * 输入：无（读取容器 clientWidth/clientHeight）。
 * 输出：更新 renderer 尺寸与分辨率 uniform。
 * 边界：高度最小兜底 1，避免除零。
 */
function handleResize() {
  const shellEl = shellRef.value
  if (!renderer || !shellEl) return
  const width = shellEl.clientWidth
  const height = Math.max(shellEl.clientHeight, 1)
  renderer.setSize(width, height, false)
  uniforms.u_resolution.value.set(width, height)
}

/**
 * 鼠标移动时更新光晕中心位置，制造轻微交互反馈。
 * 输入：PointerEvent。
 * 输出：更新 u_mouse 与 u_intensity。
 * 边界：仅在组件容器存在时生效。
 */
function handlePointerMove(event: PointerEvent) {
  const shellEl = shellRef.value
  if (!shellEl) return
  const rect = shellEl.getBoundingClientRect()
  const x = (event.clientX - rect.left) / rect.width
  const y = (event.clientY - rect.top) / rect.height
  uniforms.u_mouse.value.set(Math.min(Math.max(x, 0), 1), 1 - Math.min(Math.max(y, 0), 1))
  uniforms.u_intensity.value = Math.min(uniforms.u_intensity.value + 0.08, 1)
}

/**
 * 鼠标离开时降低交互强度，确保视觉回到稳定状态。
 * 输入：无。
 * 输出：降低 u_intensity。
 * 边界：不会降到负值。
 */
function handlePointerLeave() {
  uniforms.u_intensity.value = Math.max(uniforms.u_intensity.value - 0.25, 0)
}

/**
 * 帧循环更新时间与渐变强度，并持续重绘场景。
 * 输入：time（requestAnimationFrame 提供，毫秒）。
 * 输出：刷新 uniforms 并渲染。
 * 边界：尊重 reduced-motion，仅保留低频变化。
 */
function animate(time: number) {
  if (!renderer || !scene || !camera) return
  const t = time * 0.001
  uniforms.u_time.value = prefersReducedMotion ? t * 0.2 : t
  uniforms.u_intensity.value = Math.max(uniforms.u_intensity.value * 0.97, 0)
  renderer.render(scene, camera)
  frameId = window.requestAnimationFrame(animate)
}

/**
 * 销毁 Three.js 资源与监听器，防止内存泄漏。
 * 输入：无。
 * 输出：释放 renderer/material/geometry 并清空容器。
 * 边界：按对象存在性安全释放。
 */
function disposeThreeScene() {
  if (frameId) window.cancelAnimationFrame(frameId)
  window.removeEventListener('resize', handleResize)

  const shellEl = shellRef.value
  if (shellEl) {
    shellEl.removeEventListener('pointermove', handlePointerMove)
    shellEl.removeEventListener('pointerleave', handlePointerLeave)
  }

  if (mesh) {
    mesh.geometry.dispose()
    mesh.material.dispose()
    scene?.remove(mesh)
    mesh = null
  }

  if (renderer) {
    renderer.dispose()
    const hostEl = canvasHostRef.value
    if (hostEl && renderer.domElement.parentNode === hostEl) {
      hostEl.removeChild(renderer.domElement)
    }
  }

  renderer = null
  scene = null
  camera = null
}

onMounted(() => {
  initThreeScene()
  window.addEventListener('resize', handleResize)

  const shellEl = shellRef.value
  if (shellEl) {
    shellEl.addEventListener('pointermove', handlePointerMove)
    shellEl.addEventListener('pointerleave', handlePointerLeave)
  }

  frameId = window.requestAnimationFrame(animate)
})

onBeforeUnmount(() => {
  disposeThreeScene()
})
</script>

<template>
  <section ref="shellRef" class="hero-aura-shell" role="banner" aria-label="main index hero">
    <div ref="canvasHostRef" class="hero-aura-canvas" aria-hidden="true" />
    <div class="hero-aura-overlay" aria-hidden="true" />

    <header class="hero-aura-topbar">
      <nav v-if="props.navItems.length" class="hero-aura-nav" aria-label="capability tags">
        <span v-for="item in props.navItems" :key="item.label" class="hero-aura-link">
          {{ item.label }}
        </span>
      </nav>
      <!-- 已按需求临时注释 Unicorn Studio 画布渲染区域 -->
      <!--
      <div class="hero-aura-brand" ref="unicornLogoRef" aria-label="glyahh logo animation">
        <div class="hero-aura-brand-unicorn" data-us-project="TR0q22qXckFsVDOVIiq5" />
      </div>
      -->
    </header>

    <h1 class="hero-aura-title">{{ props.titleText }}</h1>
  </section>
</template>

<style scoped>
.hero-aura-shell {
  --font-family-arizona-flare: 'ABC Arizona Flare Variable', 'Times New Roman', serif;
  --color-seed-100: #eae6df;
  --color-soil-100: #2a1a1d;
  --color-craft-green: #26d862;
  position: relative;
  width: 100%;
  min-height: 52vh;
  max-height: 72vh;
  height: min(62vh, 875px);
  padding: 24px 28px 22px;
  margin: 0;
  overflow: hidden;
  box-sizing: border-box;
  border: 1px solid rgba(42, 26, 29, 0.55);
  color: var(--color-seed-100);
  font-family: var(--font-family-arizona-flare);
  -webkit-font-smoothing: antialiased;
  user-select: none;
}

.hero-aura-canvas,
.hero-aura-canvas :deep(canvas) {
  position: absolute;
  inset: 0;
  display: block;
  width: 100%;
  height: 100%;
}

.hero-aura-overlay {
  position: absolute;
  inset: 0;
  background:
    radial-gradient(circle at 78% 22%, rgba(38, 216, 98, 0.15), transparent 42%),
    linear-gradient(180deg, rgba(8, 14, 12, 0.28) 0%, rgba(12, 18, 15, 0.32) 100%);
  pointer-events: none;
  z-index: 1;
}

.hero-aura-topbar {
  position: relative;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
}

.hero-aura-nav {
  display: flex;
  align-items: center;
  gap: 14px;
}

.hero-aura-link {
  color: #d7f3dd;
  font-size: clamp(13px, 1.8vw, 20px);
  line-height: 1.2;
}

.hero-aura-brand {
  width: clamp(186px, 21vw, 254px);
  height: clamp(56px, 7.4vw, 78px);
  display: flex;
  align-items: flex-start;
  justify-content: flex-end;
  overflow: hidden;
  border-radius: 0;
}

.hero-aura-brand-unicorn {
  position: relative;
  isolation: isolate;
  width: 100%;
  height: 100%;
  min-width: 160px;
  min-height: 52px;
  overflow: hidden;
  background:
    radial-gradient(circle at 62% 22%, rgba(46, 204, 113, 0.22), transparent 48%),
    linear-gradient(135deg, #091313 0%, #0d1f1b 48%, #0b1815 100%);
}

/* 使用墨绿色叠层把 logo 内部黑底统一到项目基调。 */
.hero-aura-brand-unicorn::after {
  content: '';
  position: absolute;
  inset: 0;
  z-index: 3;
  pointer-events: none;
  background:
    radial-gradient(circle at 70% 26%, rgba(52, 211, 153, 0.38), transparent 56%),
    linear-gradient(135deg, rgba(6, 48, 36, 0.76), rgba(8, 58, 44, 0.72));
  mix-blend-mode: screen;
}

/* 让 Unicorn 渲染层贴满容器四边，避免出现顶边留白。 */
.hero-aura-brand-unicorn :deep(*) {
  box-sizing: border-box;
}

.hero-aura-brand-unicorn :deep(canvas),
.hero-aura-brand-unicorn :deep(svg),
.hero-aura-brand-unicorn :deep(iframe),
.hero-aura-brand-unicorn :deep(.unicorn-embed) {
  position: relative;
  z-index: 2;
  width: 100% !important;
  height: 100% !important;
  margin: 0 !important;
  padding: 0 !important;
}

/* 轻微放大并上移，确保 logo 视觉边界和你截图红框更贴合。 */
.hero-aura-brand-unicorn :deep(canvas),
.hero-aura-brand-unicorn :deep(svg),
.hero-aura-brand-unicorn :deep(iframe) {
  transform: scale(1.08) translateY(-4%);
  transform-origin: center center;
}

/* 隐藏 UnicornStudio 默认注入的底部品牌徽标图片。 */
.hero-aura-brand :deep(img[src*='assets.unicorn.studio/media/made_in_us_small_web.svg']) {
  display: none !important;
}

/* 同时隐藏徽标的外层容器，避免只去掉图片后残留白色背景。 */
.hero-aura-brand :deep(a[href*='unicorn.studio']),
.hero-aura-brand :deep([class*='made_in_us']),
.hero-aura-brand :deep([class*='made-in-us']) {
  display: none !important;
}

.hero-aura-title {
  position: relative;
  z-index: 2;
  margin: 0;
  margin-top: auto;
  max-width: 12ch;
  font-family: var(--font-family-arizona-flare);
  color: var(--color-seed-100);
  font-size: clamp(38px, 9vw, 112px);
  line-height: 0.88;
  letter-spacing: 0.02em;
  text-transform: uppercase;
}

@media (max-width: 768px) {
  .hero-aura-shell {
    min-height: 48vh;
    height: 52vh;
    padding: 18px 18px 16px;
  }

  .hero-aura-brand {
    width: 170px;
    height: 54px;
  }
}
</style>

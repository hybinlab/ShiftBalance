import { useEffect, useRef, type CSSProperties } from 'react'
import { Geometry, Mesh, Program, Renderer, RenderTarget, Texture, Triangle } from 'ogl'

const MAX_WAVES = 100
const QUALITY_SCALE = { low: 0.4, medium: 0.7, high: 1 }
const START_SCALE = 1.5
const LIFE_CONSTANT = Math.log(500)

const waveVertex = `
precision highp float;
attribute vec2 position;
attribute vec2 uv;
attribute vec2 iOffset;
attribute vec2 iScale;
attribute float iOpacity;
varying vec2 vUv;
varying float vOpacity;
void main() {
  vUv = uv;
  vOpacity = iOpacity;
  gl_Position = vec4(iOffset + position * iScale, 0.0, 1.0);
}
`

const waveFragment = `
precision highp float;
varying vec2 vUv;
varying float vOpacity;
uniform float uRings;
const float PI = 3.141592653589793;
const float EDGE = 0.006737947;
void main() {
  vec2 p = vUv * 2.0 - 1.0;
  float r = dot(p, p);
  if (r > 1.0) discard;
  float brush = (exp(-r * 5.0) - EDGE) / (1.0 - EDGE);
  brush *= 0.55 + 0.45 * cos(sqrt(r) * PI * 2.0 * uRings);
  gl_FragColor = vec4(vec3(brush * vOpacity * vOpacity), 1.0);
}
`

const screenVertex = `
precision highp float;
attribute vec2 position;
attribute vec2 uv;
varying vec2 vUv;
void main() {
  vUv = uv;
  gl_Position = vec4(position, 0.0, 1.0);
}
`

const compositeFragment = `
precision highp float;
varying vec2 vUv;
uniform sampler2D uTexture;
uniform sampler2D uDisplacement;
uniform vec2 uResolution;
uniform vec2 uTextureSize;
uniform vec2 uTexel;
uniform vec3 uTint;
uniform vec3 uHighlight;
uniform float uStrength;
uniform float uSwirl;
uniform float uDispersion;
uniform float uGlint;
uniform float uTintAmount;
uniform float uGrayscale;
const float TAU = 6.283185307179586;
vec2 coverUV(vec2 uv) {
  vec2 safe = max(uTextureSize, vec2(1.0));
  vec2 s = uResolution / safe;
  vec2 scaledSize = safe * max(s.x, s.y);
  vec2 offset = (uResolution - scaledSize) * 0.5;
  return (uv * uResolution - offset) / scaledSize;
}
void main() {
  float amount = texture2D(uDisplacement, vUv).r;
  vec2 base = coverUV(vUv);
  float theta = amount * uSwirl * TAU;
  vec2 dir = vec2(sin(theta), cos(theta));
  vec2 push = dir * amount * uStrength;
  vec3 color;
  if (uDispersion > 0.001) {
    float split = uDispersion * 0.25;
    color.r = texture2D(uTexture, base + push * (1.0 + split)).r;
    color.g = texture2D(uTexture, base + push).g;
    color.b = texture2D(uTexture, base + push * (1.0 - split)).b;
  } else {
    color = texture2D(uTexture, base + push).rgb;
  }
  if (uGrayscale > 0.001) {
    color = mix(color, vec3(dot(color, vec3(0.2126, 0.7152, 0.0722))), uGrayscale);
  }
  if (uTintAmount > 0.001) {
    color = mix(color, color * uTint * 1.9, clamp(amount * 1.6, 0.0, 1.0) * uTintAmount);
  }
  if (uGlint > 0.001) {
    float ex = texture2D(uDisplacement, vUv + vec2(uTexel.x, 0.0)).r - texture2D(uDisplacement, vUv - vec2(uTexel.x, 0.0)).r;
    float ey = texture2D(uDisplacement, vUv + vec2(0.0, uTexel.y)).r - texture2D(uDisplacement, vUv - vec2(0.0, uTexel.y)).r;
    vec3 normal = normalize(vec3(-ex * 26.0, -ey * 26.0, 1.0));
    vec3 light = normalize(vec3(-0.35, 0.55, 1.0));
    float raw = pow(max(dot(normal, light), 0.0), 22.0);
    float flatSpec = pow(max(light.z, 0.0), 22.0);
    color += uHighlight * clamp((raw - flatSpec) / max(1.0 - flatSpec, 0.0001), 0.0, 1.0) * uGlint;
  }
  gl_FragColor = vec4(color, 1.0);
}
`

export type RippleTrigger = 'hover' | 'click' | 'both'
export type RippleQuality = 'low' | 'medium' | 'high'

type Props = {
  src?: string
  brushSize?: number
  strength?: number
  swirl?: number
  rings?: number
  spread?: number
  fade?: number
  spacing?: number
  dispersion?: number
  glint?: number
  tint?: string
  tintAmount?: number
  grayscale?: boolean
  highlightColor?: string
  trigger?: RippleTrigger
  clickStrength?: number
  quality?: RippleQuality
  enabled?: boolean
  className?: string
  style?: CSSProperties
}

type Uniform<T> = { value: T }
type WaveUniforms = { uRings: Uniform<number> }
type CompositeUniforms = {
  uTexture: Uniform<Texture>
  uDisplacement: Uniform<Texture>
  uResolution: Uniform<[number, number]>
  uTextureSize: Uniform<[number, number]>
  uTexel: Uniform<[number, number]>
  uTint: Uniform<[number, number, number]>
  uHighlight: Uniform<[number, number, number]>
  uStrength: Uniform<number>
  uSwirl: Uniform<number>
  uDispersion: Uniform<number>
  uGlint: Uniform<number>
  uTintAmount: Uniform<number>
  uGrayscale: Uniform<number>
}
type Wave = { x: number; y: number; scale: number; target: number; size: number; opacity: number }

function hexToRgb(hex: string): [number, number, number] {
  const clean = hex.replace('#', '')
  const full = clean.length === 3 ? clean.split('').map((value) => value + value).join('') : clean
  const value = Number.parseInt(full, 16)
  if (Number.isNaN(value)) return [1, 1, 1]
  return [(value >> 16 & 255) / 255, (value >> 8 & 255) / 255, (value & 255) / 255]
}

export default function RippleDistortion({
  src = '/login-ripple.svg',
  brushSize = 150,
  strength = 0.2,
  swirl = 1,
  rings = 4,
  spread = 5,
  fade = 3,
  spacing = 15,
  dispersion = 0,
  glint = 0,
  tint = '#56D9BC',
  tintAmount = 0.1,
  grayscale = true,
  highlightColor = '#ffffff',
  trigger = 'hover',
  clickStrength = 2,
  quality = 'low',
  enabled = true,
  className = '',
  style,
}: Props) {
  const mountRef = useRef<HTMLDivElement>(null)
  const propsRef = useRef({ brushSize, strength, swirl, rings, spread, fade, spacing, dispersion, glint, tint, tintAmount, grayscale, highlightColor, trigger, clickStrength, quality, enabled })
  const uniformsRef = useRef<{ wave: WaveUniforms; composite: CompositeUniforms } | null>(null)
  propsRef.current = { brushSize, strength, swirl, rings, spread, fade, spacing, dispersion, glint, tint, tintAmount, grayscale, highlightColor, trigger, clickStrength, quality, enabled }

  useEffect(() => {
    const mount = mountRef.current
    if (!mount) return
    const currentProps = propsRef.current
    const reduceMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false

    let renderer: Renderer
    try {
      renderer = new Renderer({ alpha: false, antialias: false, dpr: Math.min(window.devicePixelRatio || 1, 2) })
    } catch {
      return
    }
    const gl = renderer.gl
    gl.clearColor(0, 0, 0, 1)
    const canvas = gl.canvas
    canvas.style.cssText = 'display:block;width:100%;height:100%;opacity:0;transition:opacity .35s ease'
    mount.appendChild(canvas)

    const imageTexture = new Texture(gl, { generateMipmaps: false, minFilter: gl.LINEAR, magFilter: gl.LINEAR, wrapS: gl.CLAMP_TO_EDGE, wrapT: gl.CLAMP_TO_EDGE })
    let disposed = false
    const image = new Image()
    image.crossOrigin = 'anonymous'
    image.decoding = 'async'
    image.onload = () => {
      if (disposed) return
      const textureImage = document.createElement('canvas')
      textureImage.width = image.naturalWidth || 1600
      textureImage.height = image.naturalHeight || 1200
      const context = textureImage.getContext('2d')
      if (!context) return
      // ponytail: Chrome rejects SVG image sources in texImage2D here; rasterize once before upload.
      context.drawImage(image, 0, 0, textureImage.width, textureImage.height)
      imageTexture.image = textureImage
      compositeUniforms.uTextureSize.value = [textureImage.width, textureImage.height]
      canvas.style.opacity = '1'
    }
    image.src = src

    const offsets = new Float32Array(MAX_WAVES * 2)
    const scales = new Float32Array(MAX_WAVES * 2)
    const opacities = new Float32Array(MAX_WAVES)
    const waves: Wave[] = Array.from({ length: MAX_WAVES }, () => ({ x: 0, y: 0, scale: START_SCALE, target: START_SCALE, size: 1, opacity: 0 }))
    let current = 0
    const geometry = new Geometry(gl, {
      position: { size: 2, data: new Float32Array([-1, -1, 1, -1, -1, 1, -1, 1, 1, -1, 1, 1]) },
      uv: { size: 2, data: new Float32Array([0, 0, 1, 0, 0, 1, 0, 1, 1, 0, 1, 1]) },
      iOffset: { instanced: 1, size: 2, data: offsets },
      iScale: { instanced: 1, size: 2, data: scales },
      iOpacity: { instanced: 1, size: 1, data: opacities },
    })
    const waveUniforms: WaveUniforms = { uRings: { value: currentProps.rings } }
    const waveProgram = new Program(gl, { vertex: waveVertex, fragment: waveFragment, uniforms: waveUniforms, transparent: true, depthTest: false, depthWrite: false, cullFace: false })
    waveProgram.setBlendFunc(gl.ONE, gl.ONE)
    const waveMesh = new Mesh(gl, { geometry, program: waveProgram, frustumCulled: false })
    const displacementTarget = new RenderTarget(gl, { width: 2, height: 2, depth: false, minFilter: gl.LINEAR, magFilter: gl.LINEAR, wrapS: gl.CLAMP_TO_EDGE, wrapT: gl.CLAMP_TO_EDGE })
    const compositeUniforms: CompositeUniforms = {
      uTexture: { value: imageTexture },
      uDisplacement: { value: displacementTarget.texture },
      uResolution: { value: [1, 1] },
      uTextureSize: { value: [1, 1] },
      uTexel: { value: [1, 1] },
      uTint: { value: hexToRgb(currentProps.tint) },
      uHighlight: { value: hexToRgb(currentProps.highlightColor) },
      uStrength: { value: currentProps.strength },
      uSwirl: { value: currentProps.swirl },
      uDispersion: { value: currentProps.dispersion },
      uGlint: { value: currentProps.glint },
      uTintAmount: { value: currentProps.tintAmount },
      uGrayscale: { value: currentProps.grayscale ? 1 : 0 },
    }
    const compositeMesh = new Mesh(gl, { geometry: new Triangle(gl), program: new Program(gl, { vertex: screenVertex, fragment: compositeFragment, uniforms: compositeUniforms, depthTest: false, depthWrite: false }) })
    uniformsRef.current = { wave: waveUniforms, composite: compositeUniforms }

    let width = 1
    let height = 1
    const resize = () => {
      width = Math.max(1, mount.clientWidth)
      height = Math.max(1, mount.clientHeight)
      renderer.setSize(width, height)
      compositeUniforms.uResolution.value = [width, height]
      const scale = QUALITY_SCALE[propsRef.current.quality] ?? QUALITY_SCALE.low
      const fieldWidth = Math.max(2, Math.round(width * scale))
      const fieldHeight = Math.max(2, Math.round(height * scale))
      displacementTarget.setSize(fieldWidth, fieldHeight)
      compositeUniforms.uTexel.value = [1 / fieldWidth, 1 / fieldHeight]
    }
    const resizeObserver = new ResizeObserver(resize)
    resizeObserver.observe(mount)
    resize()

    const addWave = (x: number, y: number, power: number) => {
      const wave = waves[current]
      current = (current + 1) % MAX_WAVES
      wave.x = x
      wave.y = y
      wave.scale = START_SCALE * power
      wave.target = START_SCALE * Math.max(1, propsRef.current.spread) * power
      wave.size = Math.max(1, propsRef.current.brushSize)
      wave.opacity = 1
    }
    const localPoint = (event: PointerEvent): [number, number] | null => {
      const rect = mount.getBoundingClientRect()
      if (!rect.width || !rect.height || event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) return null
      return [event.clientX - rect.left, rect.height - (event.clientY - rect.top)]
    }
    let previousX = Number.NEGATIVE_INFINITY
    let previousY = Number.NEGATIVE_INFINITY
    const onMove = (event: PointerEvent) => {
      const props = propsRef.current
      if (!props.enabled || reduceMotion || props.trigger === 'click') return
      const point = localPoint(event)
      if (!point) return
      if (Math.abs(point[0] - previousX) > props.spacing || Math.abs(point[1] - previousY) > props.spacing) {
        addWave(point[0], point[1], 1)
        previousX = point[0]
        previousY = point[1]
      }
    }
    const onDown = (event: PointerEvent) => {
      const props = propsRef.current
      if (!props.enabled || reduceMotion || props.trigger === 'hover') return
      const point = localPoint(event)
      if (point) addWave(point[0], point[1], Math.max(1, props.clickStrength))
    }
    window.addEventListener('pointermove', onMove, { passive: true })
    window.addEventListener('pointerdown', onDown, { passive: true })

    let frame = 0
    let previousTime = 0
    const render = (now: number) => {
      frame = requestAnimationFrame(render)
      const props = propsRef.current
      const delta = previousTime ? Math.min(0.05, (now - previousTime) / 1000) : 0
      previousTime = now
      const growth = reduceMotion ? 0 : 1 - Math.exp(-delta * 1.09)
      const decay = reduceMotion ? 1 : Math.exp((-delta * LIFE_CONSTANT) / Math.max(0.15, props.fade))
      for (let index = 0; index < MAX_WAVES; index += 1) {
        const wave = waves[index]
        if (wave.opacity <= 0) {
          opacities[index] = 0
          continue
        }
        wave.opacity *= decay
        wave.scale += (wave.target - wave.scale) * growth
        if (wave.opacity < 0.002) {
          wave.opacity = 0
          opacities[index] = 0
          continue
        }
        const half = wave.scale * wave.size / 2
        offsets[index * 2] = wave.x / width * 2 - 1
        offsets[index * 2 + 1] = wave.y / height * 2 - 1
        scales[index * 2] = half / width * 2
        scales[index * 2 + 1] = half / height * 2
        opacities[index] = wave.opacity
      }
      geometry.attributes.iOffset.needsUpdate = true
      geometry.attributes.iScale.needsUpdate = true
      geometry.attributes.iOpacity.needsUpdate = true
      renderer.render({ scene: waveMesh, target: displacementTarget, clear: true })
      renderer.render({ scene: compositeMesh })
    }
    frame = requestAnimationFrame(render)

    return () => {
      disposed = true
      cancelAnimationFrame(frame)
      resizeObserver.disconnect()
      window.removeEventListener('pointermove', onMove)
      window.removeEventListener('pointerdown', onDown)
      uniformsRef.current = null
      if (canvas.parentNode === mount) mount.removeChild(canvas)
      gl.getExtension('WEBGL_lose_context')?.loseContext()
    }
  }, [src, quality])

  useEffect(() => {
    const uniforms = uniformsRef.current
    if (!uniforms) return
    uniforms.wave.uRings.value = rings
    uniforms.composite.uStrength.value = strength
    uniforms.composite.uSwirl.value = swirl
    uniforms.composite.uDispersion.value = dispersion
    uniforms.composite.uGlint.value = glint
    uniforms.composite.uTintAmount.value = tintAmount
    uniforms.composite.uGrayscale.value = grayscale ? 1 : 0
    uniforms.composite.uHighlight.value = hexToRgb(highlightColor)
    uniforms.composite.uTint.value = hexToRgb(tint)
  }, [rings, strength, swirl, dispersion, glint, tintAmount, grayscale, highlightColor, tint])

  return <div ref={mountRef} className={`ripple-distortion ${className}`} style={{ ...style, backgroundImage: `url("${src}")` }} aria-hidden="true" />
}

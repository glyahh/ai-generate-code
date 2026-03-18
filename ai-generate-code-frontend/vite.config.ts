import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'

// https://vite.dev/config/
// export default defineConfig({
//   plugins: [
//     vue(),
//     vueDevTools(),
//   ],
//   resolve: {
//     alias: {
//       '@': fileURLToPath(new URL('./src', import.meta.url))
//     },
//   },
// })

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue(), vueDevTools()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8124',
        changeOrigin: true,
        secure: false,
        // 避免后端返回绝对重定向（Location 指向 8124）导致 iframe 真正跳到跨域源
        configure: (proxy) => {
          proxy.on('proxyRes', (proxyRes) => {
            const loc = proxyRes.headers.location
            if (!loc) return
            const locStr = Array.isArray(loc) ? loc[0] : String(loc)
            try {
              const u = new URL(locStr)
              if (u.host === 'localhost:8124') {
                proxyRes.headers.location = u.pathname + u.search + u.hash
              }
            } catch {
              // 非绝对 URL（相对跳转）不处理
            }
          })
        },
      },
      // 预览静态站点：保持 iframe 同源（通过 Vite 反向代理到后端静态目录）
      '/static': {
        target: 'http://localhost:8124',
        changeOrigin: true,
        secure: false,
        configure: (proxy) => {
          proxy.on('proxyRes', (proxyRes) => {
            const loc = proxyRes.headers.location
            if (!loc) return
            const locStr = Array.isArray(loc) ? loc[0] : String(loc)
            try {
              const u = new URL(locStr)
              if (u.host === 'localhost:8124') {
                proxyRes.headers.location = u.pathname + u.search + u.hash
              }
            } catch {
              // ignore
            }
          })
        },
      },
    },
  },
})

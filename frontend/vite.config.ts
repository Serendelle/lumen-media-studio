import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { fileURLToPath, URL } from 'node:url'

// The browser only ever talks to this origin; /api is proxied to the Spring backend.
// No secret is ever exposed to the frontend (there are deliberately no VITE_* secret variables).
const backend = process.env.LUMEN_BACKEND_URL ?? 'http://localhost:8080'

export default defineConfig({
  // Vue drives the app; React renders only the .tsx landing page (src/react, src/components/ui).
  plugins: [vue(), react({ include: /\.tsx$/ }), tailwindcss()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': { target: backend, changeOrigin: false },
      '/actuator': { target: backend, changeOrigin: false },
    },
  },
  preview: {
    port: 4173,
    proxy: {
      '/api': { target: backend, changeOrigin: false },
      '/actuator': { target: backend, changeOrigin: false },
    },
  },
  build: {
    target: 'es2022',
    cssCodeSplit: true,
    rollupOptions: {
      output: {
        manualChunks: {
          gsap: ['gsap', 'gsap/ScrollTrigger'],
          vendor: ['vue', 'vue-router', 'pinia', 'axios'],
        },
      },
    },
  },
})

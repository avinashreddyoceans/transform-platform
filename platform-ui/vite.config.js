import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'

// ── Vite configuration ────────────────────────────────────────────────────────
//
// Build output:
//   vite build → platform-api/src/main/resources/static/ui/
//   Spring Boot picks this up via its auto-configuration of
//   ResourceHttpRequestHandler (classpath:static/).
//   The ui/ sub-path keeps our assets isolated from any other static files.
//
// Dev mode:
//   npm run dev → Vite on :5173
//   /api/**     → proxied to Spring Boot on :8080
//   All other routes served by Vite HMR
//   Access UI at: http://localhost:5173
//
// Production:
//   ./gradlew :platform-api:bootRun (or bootJar)
//   → :buildFrontend runs first (see platform-api/build.gradle.kts)
//   → Vite builds to src/main/resources/static/ui/
//   → Spring Boot serves UI at: http://localhost:8080/ui/

export default defineConfig({
  plugins: [react()],

  // Base path — must match where Spring Boot serves the static folder
  base: '/ui/',

  build: {
    outDir: path.resolve(__dirname, '../platform-api/src/main/resources/static/ui'),
    emptyOutDir: true,
    sourcemap: false,
    rollupOptions: {
      output: {
        // Stable chunk names for better caching
        manualChunks: {
          vendor: ['react', 'react-dom', 'react-router-dom'],
          icons:  ['lucide-react'],
        }
      }
    }
  },

  server: {
    port: 5173,
    proxy: {
      // Forward all /api calls to the Spring Boot server
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        rewrite: path => path,
      }
    }
  }
})

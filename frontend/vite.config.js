import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

const gatewayTarget = process.env.GATEWAY_TARGET || process.env.VITE_API_BASE_URL || 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    host: '0.0.0.0',
    proxy: {
      '/api': { target: gatewayTarget, changeOrigin: true }
    }
  }
});

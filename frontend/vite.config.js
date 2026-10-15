import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

const targets = {
  auth: process.env.AUTH_TARGET || 'http://localhost:8081',
  catalog: process.env.CATALOG_TARGET || 'http://localhost:8082',
  booking: process.env.BOOKING_TARGET || 'http://localhost:8091'
};

export default defineConfig({
  plugins: [react()],
  server: {
    host: '0.0.0.0',
    proxy: {
      '/api/auth': { target: targets.auth, rewrite: path => path.replace(/^\/api\/auth/, '') },
      '/api/catalog': { target: targets.catalog, rewrite: path => path.replace(/^\/api\/catalog/, '') },
      '/api/booking': { target: targets.booking, rewrite: path => path.replace(/^\/api\/booking/, '') }
    }
  }
});

import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// base './' so the built files load from file:// inside Electron. Two pages: instructor (index) and student.
export default defineConfig({
  base: './',
  plugins: [react()],
  build: { outDir: 'dist', chunkSizeWarningLimit: 2000, rollupOptions: { input: { index: 'index.html', student: 'student.html' } } },
});

This folder contains a minimal, from-scratch Electron main process used to load the frontend served by the backend.

Usage:

- Development: npm run electron:new:dev
- Run packaged/dev build: npm run electron:new
- Build installer: npm run electron:new:build

The app will probe BACKEND_URL env var or default to <http://localhost:8080/> and fallback to 127.0.0.1 when needed.

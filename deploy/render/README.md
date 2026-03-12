# BrailleAI Render Deployment

This repo includes a Render Blueprint at `render.yaml`.

## Stack

- `brailleai-ui` (Caddy + static frontend, free plan)
- `brailleai-ui` (Nginx + static frontend, free plan)
- `brailleai-backend` (Spring Boot + Liblouis, starter plan)
- `brailleai-vision` (Python vision service, free plan)

## Why this split

- Keeps monthly cost lower than running all services on paid plans.
- Keeps backend awake on Render starter plan.
- Vision can run on free plan (first request may be slower after idle spin-down).

## Deploy Steps (Render Dashboard)

1. Sign in to Render and connect your GitHub account.
2. Click **New** -> **Blueprint**.
3. Select this repo: `En135511/FYP-DigitalOcean`.
4. Confirm `render.yaml` is detected.
5. Review plans:
   - `brailleai-backend`: `starter`
   - `brailleai-ui`: `free`
   - `brailleai-vision`: `free`
6. Click **Apply** / **Create New Resources**.
7. Wait for all 3 services to reach `Live`.
8. Open the `brailleai-ui` URL.

Notes:
- Render backend health probe uses `/healthz` (lightweight startup probe).
- Functional API check remains available at `/api/braille/health`.

## Optional Next Step

- Upgrade `brailleai-vision` from free to starter if image translation cold starts are too slow.

# BrailleAI Render Deployment

This repo includes a Render Blueprint at `render.yaml`.

## Stack

- `brailleai-ui` (Nginx + static frontend, free plan)
- `brailleai-backend` (Spring Boot + Liblouis + co-located Python vision, starter plan)

## Why this layout

- Removes backend->vision network failures by running vision in the same container.
- Keeps costs predictable with only one paid compute service plus free UI.

## Deploy Steps (Render Dashboard)

1. Sign in to Render and connect your GitHub account.
2. Click **New** -> **Blueprint**.
3. Select this repo: `En135511/FYP-DigitalOcean`.
4. Confirm `render.yaml` is detected.
5. Review plans:
   - `brailleai-backend`: `starter`
   - `brailleai-ui`: `free`
6. Click **Apply** / **Create New Resources**.
7. Wait for both services to reach `Live`.
8. Open the `brailleai-ui` URL.

Notes:
- Render backend health probe uses `/healthz` (lightweight startup probe).
- Functional API check remains available at `/api/braille/health`.
- UI proxy timeouts are extended for `/api/vision/*` to avoid false 504s.
- Backend container starts local vision first on `127.0.0.1:8000`, then Spring Boot on Render `PORT`.

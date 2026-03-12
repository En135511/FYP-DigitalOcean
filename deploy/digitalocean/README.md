# BrailleAI DigitalOcean Deployment

This deployment copy is isolated from your original local project.

It includes all active services:
- `simple-test-ui` (frontend)
- `brailleai-application` and module dependencies (Spring Boot backend)
- `vision-python-service` (Python vision OCR service)

## Important Fix Included

The `lou_translate.exe` Windows-only issue is fixed for Linux containers by:
- installing `liblouis-bin` in the backend container
- using Linux CLI path: `/usr/bin/lou_translate`
- using bundled tables path: `/app/liblouis/tables/en-us-g2.ctb`

## Prerequisites on Droplet

- Ubuntu 22.04/24.04
- Docker Engine + Docker Compose plugin
- Domain A record pointing to droplet public IP
- Minimum recommended droplet: 2 GB RAM (vision model loading needs memory)

## First Deployment

```bash
cd deploy/digitalocean
cp .env.example .env
```

Edit `.env`:
- `APP_HTTP_PORT=80`
- `APP_CORS_ALLOWED_ORIGINS=https://your-domain.com`

Then deploy:

```bash
chmod +x scripts/*.sh
./scripts/deploy.sh
```

After deploy:

- Open `http://<droplet-ip>` or your domain.
- Verify API health:
  - `docker compose --env-file .env -f docker-compose.yml logs backend --tail=100`
  - `curl http://127.0.0.1/api/braille/health` (run from the droplet)

## Update Deployment

```bash
./scripts/update.sh
```

## Stop Services

```bash
./scripts/stop.sh
```

## Service Topology

- `ui` listens on port `80` and serves frontend
- `ui` proxies `/api/*` to `backend:8080`
- `backend` calls `vision` at `http://vision:8000`

No direct external port exposure for backend/vision.

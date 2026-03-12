# BrailleAI DigitalOcean Deployment Copy

This is a separate deployment copy prepared for DigitalOcean.

It does **not** modify your original project setup.

Deployment files are in:

- `deploy/digitalocean/README.md`
- `deploy/digitalocean/docker-compose.yml`
- `deploy/digitalocean/*.Dockerfile`
- `deploy/digitalocean/Caddyfile`
- `deploy/digitalocean/scripts/*.sh`
- `deploy/render/README.md`
- `render.yaml`

GitHub push flow:

```bash
git init
git add .
git commit -m "Initial DigitalOcean deployment copy"
git branch -M main
git remote add origin <NEW_GITHUB_REPO_URL>
git push -u origin main
```

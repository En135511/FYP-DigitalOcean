# BrailleAI Simple Test UI

This is a frontend for BrailleAI workspace + Perkins input, with local session recovery in browser storage.

## Features
- Professional dual-workspace navigation (Workspace + Perkins Input)
- Shared settings modal across both pages
- Cross-page synchronized theme, font size, contrast, reduced motion, and density
- Text input
- Image upload
- Camera capture
- Accessible download menu (single icon with format picker)
- Copy output button
- Service status check
- Typing animation with skip button
- Response timing badges
- Translation warnings for ambiguous/mixed input
- Error panel and raw response log
- Session recovery for Workspace and Perkins pages

## Run Locally (VS Code)

You must run a local server (camera access won't work on `file://`).

### Option A: VS Code Live Server
1. Open the `simple-test-ui` folder in VS Code.
2. Right-click `index.html` -> Open with Live Server.

### Option B: Python HTTP Server
```bash
cd simple-test-ui
python -m http.server 5173
```
Open `http://localhost:5173`.

## Perkins Lab (New Page)

An isolated Perkins-style site is available at:

- `http://localhost:5173/perkins.html`

It adds:

- Perkins chorded Braille input (`f d s` + `j k l`)
- `a` as backspace, `;` as enter, and space behavior for word separation
- Perkins-focused Braille to text conversion
- UEB and SEB (Uganda) table mapping
- Word/line/letter text-to-speech profiles
- Sound feedback and configurable Perkins key mapping / timeout behavior
- Shared hidden API base URL (defaults to same-origin routing)

This page is standalone and does not replace the existing `index.html` flow.

## Run Tests

```bash
cd simple-test-ui
npm test
```

Tests cover:
- input direction and warning logic
- download menu keyboard behavior
- copy action fallback flow
- no-scroll-jump render behavior

## Publish Online From Your PC (Spring + Python + Frontend)

The scripts in `simple-test-ui/scripts` publish your local app on the internet via ngrok.

### 1. Start with already running backend/services
If Spring Boot (`:8080`) and Python vision service (`:8000`) are already running:

```powershell
cd "C:\Users\kabug\OneDrive\Desktop\Final Year Project\Brailleai\simple-test-ui"
powershell -ExecutionPolicy Bypass -File ".\scripts\start-public.ps1" -NgrokAuthToken "<YOUR_NGROK_TOKEN>" -InstallCaddy -InstallNgrok
```

If you have a reserved ngrok domain, add `-NgrokDomain`:

```powershell
powershell -ExecutionPolicy Bypass -File ".\scripts\start-public.ps1" -NgrokAuthToken "<YOUR_NGROK_TOKEN>" -NgrokDomain "<YOUR_RESERVED_DOMAIN>" -InstallCaddy -InstallNgrok
```

### 2. Or let the script start backend/service commands for you
If they are not running yet, pass startup commands:

```powershell
powershell -ExecutionPolicy Bypass -File ".\scripts\start-public.ps1" `
  -NgrokAuthToken "<YOUR_NGROK_TOKEN>" `
  -NgrokDomain "<YOUR_RESERVED_DOMAIN>" `
  -BackendStartCommand "<your Spring Boot start command>" `
  -BackendWorkDir "<backend working directory>" `
  -VisionStartCommand "<your Python service start command>" `
  -VisionWorkDir "<python service working directory>" `
  -InstallCaddy -InstallNgrok
```

The script prints:
- public URL
- `index.html` link
- `perkins.html` link
- local/tunnel health checks
- proxy routing strips `Origin` before forwarding `/api/*`, so browser CORS blocks are avoided on public tunnels

If no backend command is provided and port `8080` is down, `start-public.ps1` auto-tries this Spring Boot start flow from repo root:
- runs `.\simple-test-ui\scripts\start-backend.ps1`
- `start-backend.ps1` installs missing local Maven module artifacts if needed, then starts Spring Boot
- uses JVM args for `server.port`, `vision.service.base-url`, `LOUIS_CLI_PATH`, and `LOUIS_TABLE`

If no vision command is provided and port `8000` is down, `start-public.ps1` will auto-try:
- `C:\dev\brailleai-vision`
- `%USERPROFILE%\dev\brailleai-vision`

### 3. Stop public processes

```powershell
powershell -ExecutionPolicy Bypass -File ".\scripts\stop-public.ps1"
```

Add `-StopBackend` and/or `-StopVision` if you also want to stop listeners on ports `8080` and `8000`.

## Endpoints Used
- `POST /api/braille/translate`
- `POST /api/braille/download`
- `GET  /api/braille/health`
- `POST /api/vision/translate`
- `POST /api/vision/translate/download`

## Notes
- The UI stores preferences in localStorage (`brailleai.settings.v1`) for settings sync.
- The UI now defaults to same-origin API routing so shared public links call the same host automatically.
- Camera requires HTTPS or localhost.
- ngrok free domains can show an interstitial warning page on first visit.

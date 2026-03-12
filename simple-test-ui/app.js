import {
  DEFAULT_TABLES,
  detectTextInputDirection,
  getDirectionLabel,
  buildTranslationWarnings,
  resolveDownloadFormats,
  validateStrictInput
} from "./src/translation-analysis.js";
import { copyTextToClipboard } from "./src/clipboard.js";
import {
  fetchBrailleTables,
  requestBrailleTranslation,
  requestVisionTranslation,
  requestHealthCheck,
  requestBrailleDownload,
  requestVisionDownload
} from "./src/api-client.js";
import { createChatRenderer } from "./src/chat-renderer.js";
import {
  applyDisplaySettings,
  getApiBaseUrl,
  getSettings,
  subscribeSettings,
  updateSettings
} from "./src/app-settings.js";
import { mountSettingsModal } from "./src/settings-modal.js";

// Shared UI state for message history and current inputs.
const state = {
  mode: "text",
  lastImageBlob: null,
  lastImageName: null,
  cameraStream: null,
  isProcessing: false,
  messages: [],
  currentTypingMessage: null,
  helpOpen: false,
  lastFocusedElement: null,
  nextMessageId: 1,
  savedSessions: [],
  autosaveSession: null
};

// Typing simulation settings to mimic an AI response cadence.
const TYPING_INTERVAL_MS = 40;
const TYPING_CHUNK = 2;
const TYPING_DELAY_MIN_MS = 200;
const TYPING_DELAY_MAX_MS = 700;
const WORKSPACE_AUTOSAVE_STORAGE_KEY = "brailleai.workspace.autosave.v1";
const WORKSPACE_SESSION_LIST_STORAGE_KEY = "brailleai.workspace.sessions.v1";
const WORKSPACE_LEGACY_SESSION_STORAGE_KEY = "brailleai.workspace.session.v1";
const MAX_WORKSPACE_SESSIONS = 12;
const MAX_WORKSPACE_MESSAGE_COUNT = 40;
const MAX_WORKSPACE_MESSAGE_CHARS = 2000;
const MAX_WORKSPACE_TEXT_CHARS = 20000;

const elements = {
  baseUrl: document.getElementById("baseUrl"),
  tableSelect: document.getElementById("tableSelect"),
  settingsOpen: document.getElementById("settingsOpen"),
  healthCheck: document.getElementById("healthCheck"),
  helpToggle: document.getElementById("helpToggle"),
  helpModal: document.getElementById("helpModal"),
  helpOverlay: document.getElementById("helpOverlay"),
  helpClose: document.getElementById("helpClose"),
  messages: document.getElementById("messages"),
  tabs: Array.from(document.querySelectorAll(".tab")),
  panels: Array.from(document.querySelectorAll(".mode-panel")),
  textInput: document.getElementById("textInput"),
  imageInput: document.getElementById("imageInput"),
  imagePreview: document.getElementById("imagePreview"),
  startCamera: document.getElementById("startCamera"),
  capturePhoto: document.getElementById("capturePhoto"),
  stopCamera: document.getElementById("stopCamera"),
  cameraVideo: document.getElementById("cameraVideo"),
  cameraCanvas: document.getElementById("cameraCanvas"),
  cameraPreview: document.getElementById("cameraPreview"),
  sendBtn: document.getElementById("sendBtn"),
  skipTyping: document.getElementById("skipTyping"),
  status: document.getElementById("status"),
  errorPanel: document.getElementById("errorPanel"),
  errorContent: document.getElementById("errorContent"),
  clearError: document.getElementById("clearError"),
  logOutput: document.getElementById("logOutput")
};

let currentSettings = getSettings();

// Status line feedback below the send button.
const setStatus = (text) => {
  elements.status.textContent = text;
};

const updateSendAvailability = () => {
  elements.sendBtn.disabled = state.isProcessing || Boolean(state.currentTypingMessage);
};

const setProcessing = (active) => {
  state.isProcessing = Boolean(active);
  updateSendAvailability();
};

// Error panel content for quick troubleshooting.
const setError = (details) => {
  if (!details) {
    elements.errorPanel.classList.add("hidden");
    elements.errorContent.textContent = "";
    return;
  }
  elements.errorPanel.classList.remove("hidden");
  elements.errorContent.textContent = details;
};

// Append a new entry at the top of the raw log drawer.
const addLogEntry = (entry) => {
  const timestamp = new Date().toLocaleTimeString();
  const line = `[${timestamp}] ${entry}`;
  const existing = elements.logOutput.textContent.trim();
  elements.logOutput.textContent = existing ? `${line}\n${existing}` : line;
};

const asLimitedString = (value, maxLength) => {
  if (typeof value !== "string") return "";
  return value.length > maxLength ? value.slice(0, maxLength) : value;
};

const parseStoredJson = (key, fallback = null) => {
  try {
    const raw = window.localStorage.getItem(key);
    if (!raw) return fallback;
    const parsed = JSON.parse(raw);
    return parsed ?? fallback;
  } catch (_) {
    return fallback;
  }
};

const createSessionId = () =>
  `s-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;

const formatSessionTime = (timestamp) => {
  const value = Number(timestamp);
  if (!Number.isFinite(value)) return "Unknown time";
  return new Date(value).toLocaleString([], {
    year: "numeric",
    month: "short",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit"
  });
};

const buildWorkspaceSessionLabel = (timestamp) => `Session ${formatSessionTime(timestamp)}`;

const normalizeWorkspaceSessionMessage = (message) => {
  if (!message || typeof message !== "object") return null;
  const role = message.role === "assistant" ? "assistant" : "user";
  const content = asLimitedString(message.content, MAX_WORKSPACE_MESSAGE_CHARS).trim();
  if (!content) return null;
  const meta = asLimitedString(message.meta, 180).trim();
  const badges = asLimitedString(message.badges, 180).trim();
  return {
    role,
    content,
    meta: meta || null,
    badges: badges || null
  };
};

const serializeWorkspaceMessages = (messages) => {
  if (!Array.isArray(messages)) return [];
  return messages
    .map(normalizeWorkspaceSessionMessage)
    .filter(Boolean)
    .slice(-MAX_WORKSPACE_MESSAGE_COUNT);
};

const normalizeWorkspaceSessionRecord = (record) => {
  if (!record || typeof record !== "object") return null;
  const savedAt = Number(record.savedAt);
  const mode = ["text", "image", "camera"].includes(record.mode) ? record.mode : "text";
  const table = asLimitedString(record.table, 240).trim();
  const textInput = asLimitedString(record.textInput, MAX_WORKSPACE_TEXT_CHARS);
  const imageName = asLimitedString(record.imageName, 200).trim();
  const messages = serializeWorkspaceMessages(record.messages);
  if (!textInput.trim() && messages.length === 0 && !imageName) {
    return null;
  }

  const id = typeof record.id === "string" && record.id.trim()
    ? record.id.trim()
    : createSessionId();
  const labelRaw = asLimitedString(record.label, 80).trim();

  return {
    id,
    label: labelRaw || buildWorkspaceSessionLabel(Number.isFinite(savedAt) ? savedAt : Date.now()),
    savedAt: Number.isFinite(savedAt) ? savedAt : Date.now(),
    mode,
    table,
    textInput,
    imageName: imageName || "",
    messages
  };
};

const buildWorkspaceSnapshotFromCurrent = (label = null) => {
  const now = Date.now();
  return normalizeWorkspaceSessionRecord({
    id: createSessionId(),
    label: label || buildWorkspaceSessionLabel(now),
    savedAt: now,
    mode: state.mode,
    table: getSelectedTable(),
    textInput: elements.textInput.value || "",
    imageName: state.lastImageName || "",
    messages: serializeWorkspaceMessages(state.messages)
  });
};

const toWorkspaceSessionViewItem = (session, { autosave = false } = {}) => {
  if (!session) return null;
  const metaParts = [session.mode.toUpperCase()];
  if (session.table) {
    metaParts.push(session.table);
  }
  if (session.messages.length > 0) {
    metaParts.push(`${session.messages.length} messages`);
  }
  if (session.imageName) {
    metaParts.push(session.imageName);
  }
  return {
    id: session.id,
    label: autosave ? "Autosave Draft" : session.label,
    savedAt: session.savedAt,
    meta: metaParts.join(" | ")
  };
};

const sessionResult = (ok, message) => ({ ok, message });

const persistWorkspaceSessionList = () => {
  try {
    window.localStorage.setItem(WORKSPACE_SESSION_LIST_STORAGE_KEY, JSON.stringify(state.savedSessions));
  } catch (_) {
    // Ignore storage errors.
  }
};

const loadWorkspaceSessionList = () => {
  const incoming = parseStoredJson(WORKSPACE_SESSION_LIST_STORAGE_KEY, []);
  const records = Array.isArray(incoming) ? incoming : [];
  state.savedSessions = records
    .map(normalizeWorkspaceSessionRecord)
    .filter(Boolean)
    .sort((a, b) => b.savedAt - a.savedAt)
    .slice(0, MAX_WORKSPACE_SESSIONS);
  persistWorkspaceSessionList();
};

const clearWorkspaceAutosaveSnapshot = () => {
  try {
    window.localStorage.removeItem(WORKSPACE_AUTOSAVE_STORAGE_KEY);
  } catch (_) {
    // Ignore storage errors.
  }
  state.autosaveSession = null;
};

const loadWorkspaceAutosaveSnapshot = () => {
  const parsed = parseStoredJson(WORKSPACE_AUTOSAVE_STORAGE_KEY, null);
  state.autosaveSession = normalizeWorkspaceSessionRecord(parsed);
};

const clearWorkspaceLegacySessionData = () => {
  try {
    window.localStorage.removeItem(WORKSPACE_LEGACY_SESSION_STORAGE_KEY);
  } catch (_) {
    // Ignore storage errors.
  }
};

const saveWorkspaceAutosaveSnapshot = () => {
  const snapshot = buildWorkspaceSnapshotFromCurrent("Autosave Draft");
  if (!snapshot) {
    clearWorkspaceAutosaveSnapshot();
    return;
  }

  state.autosaveSession = { ...snapshot, id: "autosave", label: "Autosave Draft" };
  try {
    window.localStorage.setItem(WORKSPACE_AUTOSAVE_STORAGE_KEY, JSON.stringify(state.autosaveSession));
  } catch (_) {
    // Ignore storage errors.
  }
};

const teardownMessageRuntime = (message) => {
  if (!message || typeof message !== "object") return;
  if (message._typingTimer) {
    clearInterval(message._typingTimer);
  }
  if (message._typingDelay) {
    clearTimeout(message._typingDelay);
  }
  if (message._copyResetTimer) {
    clearTimeout(message._copyResetTimer);
  }
};

const restoreWorkspaceMessages = (messages) => {
  state.messages.forEach(teardownMessageRuntime);
  state.currentTypingMessage = null;
  state.messages = [];
  state.nextMessageId = 1;

  const normalized = Array.isArray(messages)
    ? messages.map(normalizeWorkspaceSessionMessage).filter(Boolean)
    : [];
  normalized.forEach((message) => {
    state.messages.push({
      id: state.nextMessageId++,
      role: message.role,
      content: message.content,
      meta: message.meta,
      badges: message.badges,
      typingDone: true,
      isNew: false,
      copied: false,
      downloadMenuOpen: false,
      focusMenuOnOpen: false,
      copy: null,
      downloads: null,
      downloadFormats: []
    });
  });
  enableSkip(false);
  renderMessages({ scrollToBottom: true });
};

const restoreWorkspaceSessionSnapshot = (session, { fromAutosave = false } = {}) => {
  const normalized = normalizeWorkspaceSessionRecord(session);
  if (!normalized) {
    return false;
  }

  stopCamera();
  setMode(normalized.mode, { persistSession: false });
  if (normalized.table && Array.from(elements.tableSelect.options).some((option) => option.value === normalized.table)) {
    elements.tableSelect.value = normalized.table;
    updateSettings({ workspaceTable: normalized.table });
  }

  elements.textInput.value = normalized.textInput;
  restoreWorkspaceMessages(normalized.messages);

  state.lastImageBlob = null;
  state.lastImageName = normalized.imageName || null;
  elements.imagePreview.classList.add("hidden");
  elements.imagePreview.innerHTML = "";
  elements.cameraPreview.classList.add("hidden");
  elements.cameraPreview.innerHTML = "";

  saveWorkspaceAutosaveSnapshot();
  const statusMessage = fromAutosave ? "Autosave restored." : "Session restored.";
  const hasImageReference = Boolean(normalized.imageName);
  if (hasImageReference) {
    setStatus(`${statusMessage} Re-upload image "${normalized.imageName}" to continue image translation.`);
  } else {
    setStatus(statusMessage);
  }
  return true;
};

const saveWorkspaceSessionToList = () => {
  const snapshot = buildWorkspaceSnapshotFromCurrent();
  if (!snapshot) {
    return sessionResult(false, "Nothing to save yet.");
  }

  state.savedSessions = [snapshot, ...state.savedSessions]
    .sort((a, b) => b.savedAt - a.savedAt)
    .slice(0, MAX_WORKSPACE_SESSIONS);
  persistWorkspaceSessionList();
  return sessionResult(true, "Session saved.");
};

const restoreWorkspaceAutosave = () => {
  if (!state.autosaveSession) {
    return sessionResult(false, "No autosave available.");
  }
  const restored = restoreWorkspaceSessionSnapshot(state.autosaveSession, { fromAutosave: true });
  return restored
    ? sessionResult(true, "Autosave restored.")
    : sessionResult(false, "Autosave restore failed.");
};

const restoreWorkspaceSessionById = (id) => {
  const target = state.savedSessions.find((session) => session.id === id);
  if (!target) {
    return sessionResult(false, "Saved session not found.");
  }
  const restored = restoreWorkspaceSessionSnapshot(target);
  return restored
    ? sessionResult(true, "Session restored.")
    : sessionResult(false, "Session restore failed.");
};

const deleteWorkspaceSessionById = (id) => {
  const previousCount = state.savedSessions.length;
  state.savedSessions = state.savedSessions.filter((session) => session.id !== id);
  if (state.savedSessions.length === previousCount) {
    return sessionResult(false, "Saved session not found.");
  }
  persistWorkspaceSessionList();
  return sessionResult(true, "Saved session deleted.");
};

const clearWorkspaceAutosave = () => {
  if (!state.autosaveSession) {
    return sessionResult(false, "No autosave available.");
  }
  clearWorkspaceAutosaveSnapshot();
  return sessionResult(true, "Autosave cleared.");
};

const listWorkspaceSessions = () => ({
  autosave: toWorkspaceSessionViewItem(state.autosaveSession, { autosave: true }),
  saved: state.savedSessions
    .map((session) => toWorkspaceSessionViewItem(session))
    .filter(Boolean)
});

const hydrateWorkspaceSessionStore = () => {
  clearWorkspaceLegacySessionData();
  loadWorkspaceAutosaveSnapshot();
  loadWorkspaceSessionList();
};

const workspaceSessionManager = {
  title: "Workspace Session Recovery",
  description: "Save checkpoints and recover workspace progress after outage or restart.",
  listSessions: () => listWorkspaceSessions(),
  saveCurrent: () => saveWorkspaceSessionToList(),
  restoreAutosave: () => restoreWorkspaceAutosave(),
  restoreSession: (id) => restoreWorkspaceSessionById(id),
  deleteSession: (id) => deleteWorkspaceSessionById(id),
  clearAutosave: () => clearWorkspaceAutosave()
};

const normalizeTables = (tables) => {
  if (!Array.isArray(tables)) return [];
  const seen = new Set();
  tables.forEach((item) => {
    if (typeof item === "string" && item.trim()) {
      seen.add(item.trim());
    }
  });
  return Array.from(seen).sort((a, b) => a.localeCompare(b));
};

const populateTableSelect = (tables, defaultTable) => {
  const options = normalizeTables(tables);
  if (options.length === 0) {
    options.push(...DEFAULT_TABLES);
  }

  const current = elements.tableSelect.value;
  elements.tableSelect.innerHTML = "";

  options.forEach((name) => {
    const option = document.createElement("option");
    option.value = name;
    option.textContent = name;
    elements.tableSelect.appendChild(option);
  });

  const preferredByCode = currentSettings.defaultCode === "SEB"
    ? currentSettings.sebTable
    : currentSettings.uebTable;

  const preferred = [current, currentSettings.workspaceTable, preferredByCode, defaultTable, "en-us-g2.ctb", options[0]].find((value) =>
    value && options.includes(value)
  );
  if (preferred) {
    elements.tableSelect.value = preferred;
  }
};

const loadTables = async () => {
  const baseUrl = getBaseUrl();
  if (!baseUrl) {
    populateTableSelect(DEFAULT_TABLES, "en-us-g2.ctb");
    return;
  }

  try {
    setStatus("Loading tables...");
    const response = await fetchBrailleTables(baseUrl);
    const tableLog = response.ok
      ? `GET /api/braille/tables -> ${response.status} ${response.statusText}`
      : `GET /api/braille/tables -> ${response.status} ${response.statusText} | ${response.text}`;
    addLogEntry(tableLog);

    if (!response.ok) {
      throw new Error("Table list unavailable");
    }

    const data = response.data || {};
    const tables = Array.isArray(data) ? data : data.tables;
    const defaultTable = data.defaultTable || "en-us-g2.ctb";
    populateTableSelect(tables, defaultTable);
    setStatus("Tables loaded.");
  } catch (_) {
    populateTableSelect(DEFAULT_TABLES, "en-us-g2.ctb");
    setStatus("Using default tables (API list unavailable).");
  }
};

const getSelectedTable = () => elements.tableSelect.value?.trim() || "";

const enableSkip = (enabled) => {
  elements.skipTyping.disabled = !enabled;
};

const getBaseUrl = () => getApiBaseUrl(currentSettings);

const applyWorkspaceSettings = (
  settings,
  { reloadTables = false, applyStartupMode = false } = {}
) => {
  currentSettings = settings;
  applyDisplaySettings(settings);
  elements.baseUrl.value = settings.apiBaseUrl || "";
  if (settings.workspaceTable && Array.from(elements.tableSelect.options).some((opt) => opt.value === settings.workspaceTable)) {
    elements.tableSelect.value = settings.workspaceTable;
  }

  if (applyStartupMode && ["text", "image", "camera"].includes(settings.startupMode)) {
    setMode(settings.startupMode);
  }

  if (reloadTables) {
    loadTables();
  }
};

const setHelpOpen = (open) => {
  const isOpen = Boolean(open);
  state.helpOpen = isOpen;
  elements.helpModal.classList.toggle("hidden", !isOpen);
  elements.helpOverlay.classList.toggle("hidden", !isOpen);
  elements.helpModal.setAttribute("aria-hidden", String(!isOpen));
  elements.helpOverlay.setAttribute("aria-hidden", String(!isOpen));
  elements.helpToggle.setAttribute("aria-expanded", String(isOpen));
  document.body.classList.toggle("modal-open", isOpen);

  if (isOpen) {
    state.lastFocusedElement = document.activeElement;
    requestAnimationFrame(() => elements.helpClose.focus());
    return;
  }

  const focusTarget = state.lastFocusedElement;
  state.lastFocusedElement = null;
  if (focusTarget && typeof focusTarget.focus === "function") {
    focusTarget.focus();
  }
};

const maybeRedirectToStartupPage = (settings) => {
  const preferredPage = settings.startupPage === "perkins" ? "perkins" : "workspace";
  if (preferredPage !== "perkins") return false;
  if (window.location.search.includes("stay=1")) return false;

  const key = "brailleai.startup-redirected-target";
  try {
    if (window.sessionStorage.getItem(key) === preferredPage) {
      return false;
    }
    window.sessionStorage.setItem(key, preferredPage);
  } catch (_) {
    return false;
  }

  window.location.href = "./perkins.html";
  return true;
};

const setMode = (mode, { persistSession = true } = {}) => {
  state.mode = mode;
  elements.tabs.forEach((tab) => {
    tab.classList.toggle("active", tab.dataset.mode === mode);
  });
  elements.panels.forEach((panel) => {
    panel.classList.toggle("hidden", panel.dataset.panel !== mode);
  });
  setStatus("");
  if (persistSession) {
    saveWorkspaceAutosaveSnapshot();
  }
};

const closeAllDownloadMenus = (exceptMessage = null) => {
  let changed = false;
  state.messages.forEach((msg) => {
    if (msg !== exceptMessage && msg.downloadMenuOpen) {
      msg.downloadMenuOpen = false;
      msg.focusMenuOnOpen = false;
      changed = true;
    }
  });
  return changed;
};

const chatRenderer = createChatRenderer({
  messagesElement: elements.messages,
  onCopy: (msg) => msg.copy?.(),
  onToggleDownloadMenu: (msg, open, { focusFirst = false } = {}) => {
    closeAllDownloadMenus(msg);
    msg.downloadMenuOpen = open;
    msg.focusMenuOnOpen = Boolean(open && focusFirst);
    renderMessages();
  },
  onSelectDownload: (msg, format) => {
    msg.downloadMenuOpen = false;
    renderMessages();
    msg.downloads?.(format);
  }
});

const renderMessages = (options = {}) => {
  chatRenderer.render(state.messages, options);
};

const addMessage = (role, content, meta = null) => {
  state.messages.push({
    id: state.nextMessageId++,
    role,
    content,
    meta,
    isNew: true
  });
  renderMessages({ scrollToBottom: true });
  saveWorkspaceAutosaveSnapshot();
};

// Animate assistant responses to appear as if typed.
const typeAssistantMessage = (message) => {
  if (state.currentTypingMessage && state.currentTypingMessage !== message) {
    finishTyping(state.currentTypingMessage);
  }
  message.displayedContent = "";
  message.typingDone = false;
  let index = 0;
  const total = message.content.length;

  const tick = () => {
    index = Math.min(total, index + TYPING_CHUNK);
    message.displayedContent = message.content.slice(0, index);
    if (index >= total) {
      finishTyping(message);
    }
    renderMessages({ scrollToBottom: true });
  };

  state.currentTypingMessage = message;
  enableSkip(true);
  updateSendAvailability();
  message._typingTimer = setInterval(tick, TYPING_INTERVAL_MS);
  tick();
};

const finishTyping = (message) => {
  if (!message) return;
  if (message._typingTimer) {
    clearInterval(message._typingTimer);
    message._typingTimer = null;
  }
  if (message._typingDelay) {
    clearTimeout(message._typingDelay);
    message._typingDelay = null;
  }
  message.displayedContent = message.content;
  message.typingDone = true;
  if (state.currentTypingMessage === message) {
    state.currentTypingMessage = null;
    enableSkip(false);
    updateSendAvailability();
  }
  renderMessages({ scrollToBottom: true });
  saveWorkspaceAutosaveSnapshot();
};

const showImagePreview = (container, blob, name) => {
  container.classList.remove("hidden");
  container.innerHTML = "";
  const img = document.createElement("img");
  img.src = URL.createObjectURL(blob);
  const info = document.createElement("div");
  info.className = "meta";
  info.textContent = name;
  container.appendChild(img);
  container.appendChild(info);
};

const triggerDownload = (blob, filename) => {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
};

const addAssistantResponse = (
  content,
  brailleUnicode,
  source,
  imageBlob = null,
  startTime = null,
  table = null,
  extraMeta = null,
  direction = null,
  requestInput = null,
  downloadFormats = ["PDF", "DOCX"],
  copyText = null,
  warnings = [],
  uiHighlights = []
) => {
  const downloads = async (format) => {
    const baseUrl = getBaseUrl();
    try {
      setStatus(`Downloading ${format}...`);
      if (source === "image") {
        const form = new FormData();
        form.append("image", imageBlob, state.lastImageName || "image.jpg");
        form.append("format", format);
        if (table) form.append("table", table);

        const res = await requestVisionDownload(baseUrl, form);
        if (!res.ok) throw new Error("Download failed");

        const blob = await res.blob();
        addLogEntry(`POST /api/vision/translate/download -> ${res.status} ${res.statusText} (${format})`);
        triggerDownload(blob, `braille-vision-translation.${format.toLowerCase()}`);
      } else {
        const payload = { outputFormat: format };
        if (requestInput) payload.input = requestInput;
        if (brailleUnicode) payload.brailleUnicode = brailleUnicode;
        if (direction) payload.direction = direction;
        if (table) payload.table = table;

        const res = await requestBrailleDownload(baseUrl, payload);
        if (!res.ok) throw new Error("Download failed");

        const blob = await res.blob();
        addLogEntry(`POST /api/braille/download -> ${res.status} ${res.statusText} (${format})`);
        triggerDownload(blob, `braille-translation.${format.toLowerCase()}`);
      }
      setStatus("Download ready.");
    } catch (err) {
      setStatus(`Error: ${err.message}`);
      setError(`Download ${format}\n${err.message}`);
    }
  };

  if (warnings.length > 0) {
    addLogEntry(`Warnings -> ${warnings.join(" | ")}`);
  }
  const metaParts = [];
  if (source === "image" && typeof brailleUnicode === "string" && brailleUnicode.trim()) {
    metaParts.push(`Braille: ${brailleUnicode}`);
  }
  if (typeof extraMeta === "string" && extraMeta.trim()) {
    metaParts.push(extraMeta.trim());
  }
  const meta = metaParts.length > 0 ? metaParts.join(" | ") : null;

  const elapsed = startTime ? Math.round(performance.now() - startTime) : null;
  const badgeParts = [];
  if (elapsed !== null) badgeParts.push(`time: ${elapsed} ms`);
  const compactHighlights = Array.isArray(uiHighlights)
    ? uiHighlights
      .filter((item) => typeof item === "string" && item.trim())
      .slice(0, 2)
    : [];
  badgeParts.push(...compactHighlights);
  const badges = badgeParts.length > 0 ? badgeParts.join(" | ") : null;

  const message = {
    id: state.nextMessageId++,
    role: "assistant",
    content,
    meta,
    badges,
    typingDone: false,
    isNew: true,
    copied: false,
    downloadMenuOpen: false,
    focusMenuOnOpen: false
  };

  const normalizedFormats = Array.isArray(downloadFormats)
    ? downloadFormats.filter((format) => typeof format === "string" && format.trim())
    : [];
  const resolvedCopyText = typeof copyText === "string"
    ? copyText
    : (typeof content === "string" ? content : "");

  message.downloads = normalizedFormats.length > 0 ? downloads : null;
  message.downloadFormats = normalizedFormats;
  message.copy = resolvedCopyText
    ? async () => {
      try {
        await copyTextToClipboard(resolvedCopyText);
        addLogEntry(`Copied output (${resolvedCopyText.length} chars)`);
        setStatus("Output copied.");
        message.copied = true;
        if (message._copyResetTimer) {
          clearTimeout(message._copyResetTimer);
        }
        message._copyResetTimer = setTimeout(() => {
          message.copied = false;
          renderMessages();
        }, 1800);
        renderMessages();
      } catch (err) {
        setStatus(`Error: ${err.message}`);
        setError(`Copy output\n${err.message}`);
      }
    }
    : null;

  state.messages.push(message);
  if (state.currentTypingMessage && state.currentTypingMessage !== message) {
    finishTyping(state.currentTypingMessage);
  }
  state.currentTypingMessage = message;
  enableSkip(true);
  updateSendAvailability();
  const delay = Math.floor(Math.random() * (TYPING_DELAY_MAX_MS - TYPING_DELAY_MIN_MS + 1)) + TYPING_DELAY_MIN_MS;
  message._typingDelay = setTimeout(() => typeAssistantMessage(message), delay);
  saveWorkspaceAutosaveSnapshot();
};

const translateBraille = async (baseUrl, inputValue) => {
  const detected = detectTextInputDirection(inputValue);
  const strictValidation = validateStrictInput(inputValue, detected.direction);
  if (!strictValidation.ok) {
    setStatus(`Error: ${strictValidation.message}`);
    setError(`POST /api/braille/translate\n${strictValidation.message}`);
    return;
  }

  const inputWarnings = buildTranslationWarnings(inputValue, detected.direction);

  setStatus(`Translating (${getDirectionLabel(detected.direction)})...`);
  const start = performance.now();
  const table = getSelectedTable();

  if (inputWarnings.length > 0) {
    addLogEntry(`Input warnings -> ${inputWarnings.join(" | ")}`);
  }

  try {
    const payload = {
      input: inputValue,
      brailleUnicode: inputValue,
      direction: detected.direction,
      outputFormat: "PDF"
    };
    if (table) payload.table = table;

    const response = await requestBrailleTranslation(baseUrl, payload);
    const translateLog = response.ok
      ? `POST /api/braille/translate -> ${response.status} ${response.statusText}`
      : `POST /api/braille/translate -> ${response.status} ${response.statusText} | ${response.text}`;
    addLogEntry(translateLog);

    const data = response.data || {};
    if (!response.ok || data.status === "error") {
      throw new Error(data.message || "Translation failed");
    }

    const responseDirection = data.direction || detected.direction;
    const responseInputType = data.detectedInputType || detected.detectedInputType;
    const translatedText = data.translatedText || "";
    const content = `Translated (${getDirectionLabel(responseDirection)}): ${translatedText}`;

    const warnings = [...inputWarnings];
    if (responseDirection !== detected.direction) {
      warnings.push(`Direction changed by server to ${getDirectionLabel(responseDirection)}.`);
    }
    if (!translatedText.trim()) {
      warnings.push("Translation output is empty.");
    }

    const downloadFormats = resolveDownloadFormats(responseDirection, "text");
    addAssistantResponse(
      content,
      responseDirection === "BRAILLE_TO_TEXT" ? inputValue : null,
      "text",
      null,
      start,
      table,
      null,
      responseDirection,
      inputValue,
      downloadFormats,
      translatedText,
      warnings,
      [
        table ? `table: ${table}` : "table: default",
        `input: ${responseInputType}`
      ]
    );
    setStatus("Done.");
  } catch (err) {
    setStatus(`Error: ${err.message}`);
    setError(`POST /api/braille/translate\n${err.message}`);
  }
};

const translateImage = async (baseUrl, imageBlob) => {
  setStatus("Translating image...");
  const start = performance.now();
  const table = getSelectedTable();
  const inputType = state.mode === "camera" ? "camera" : "image";

  try {
    const form = new FormData();
    form.append("image", imageBlob, state.lastImageName || "image.jpg");
    if (table) form.append("table", table);

    const response = await requestVisionTranslation(baseUrl, form);
    const visionLog = response.ok
      ? `POST /api/vision/translate -> ${response.status} ${response.statusText}`
      : `POST /api/vision/translate -> ${response.status} ${response.statusText} | ${response.text}`;
    addLogEntry(visionLog);
    if (!response.ok) {
      throw new Error("Vision translation failed");
    }

    const data = response.data || {};
    const brailleUnicode = data.brailleUnicode || null;
    const translatedText = data.translatedText || "";
    const warnings = [];
    const lowConfidenceCellsCount = Number.isFinite(data.lowConfidenceCellsCount)
      ? Number(data.lowConfidenceCellsCount)
      : 0;

    if (typeof data.qualityWarning === "string" && data.qualityWarning.trim()) {
      warnings.push(data.qualityWarning.trim());
    }
    if (data.reviewRecommended) {
      warnings.push(
        `Manual review recommended before final export (${lowConfidenceCellsCount} uncertain cells).`
      );
    }
    if (!translatedText.trim()) {
      warnings.push("No text was produced from this image.");
    }

    const content = `Translated: ${translatedText || "-"}`;
    addAssistantResponse(
      content,
      brailleUnicode,
      "image",
      imageBlob,
      start,
      table,
      null,
      null,
      null,
      resolveDownloadFormats(null, "image"),
      translatedText,
      warnings,
      [
        table ? `table: ${table}` : "table: default",
        `input: ${inputType}`
      ]
    );
    setStatus("Done.");
  } catch (err) {
    setStatus(`Error: ${err.message}`);
    setError(`POST /api/vision/translate\n${err.message}`);
  }
};

const sendRequest = async () => {
  if (state.isProcessing) {
    return;
  }

  const baseUrl = getBaseUrl();

  if (!baseUrl) {
    setStatus("Base URL is required.");
    return;
  }

  if (state.mode === "text") {
    const text = elements.textInput.value.trim();
    if (!text) {
      setStatus("Enter text.");
      return;
    }
    setProcessing(true);
    try {
      addMessage("user", text);
      await translateBraille(baseUrl, text);
    } finally {
      setProcessing(false);
    }
    return;
  }

  if (state.mode === "image" || state.mode === "camera") {
    if (!state.lastImageBlob) {
      setStatus("Select or capture an image first.");
      return;
    }
    setProcessing(true);
    try {
      addMessage("user", "Image input", state.lastImageName || "Captured image");
      await translateImage(baseUrl, state.lastImageBlob);
    } finally {
      setProcessing(false);
    }
  }
};

const handleImageInput = (file) => {
  if (!file) return;
  state.lastImageBlob = file;
  state.lastImageName = file.name;
  showImagePreview(elements.imagePreview, file, file.name);
  saveWorkspaceAutosaveSnapshot();
};

const requestCameraStream = async () => {
  const attempts = [
    { audio: false, video: { facingMode: { exact: "environment" } } },
    { audio: false, video: { facingMode: { ideal: "environment" } } },
    { audio: false, video: true },
  ];

  let lastError = null;
  for (const constraints of attempts) {
    try {
      return await navigator.mediaDevices.getUserMedia(constraints);
    } catch (err) {
      lastError = err;
    }
  }

  throw lastError || new Error("Unable to access camera.");
};

const startCamera = async () => {
  try {
    stopCamera();
    const stream = await requestCameraStream();
    state.cameraStream = stream;
    elements.cameraVideo.srcObject = stream;
    elements.capturePhoto.disabled = false;
    elements.stopCamera.disabled = false;

    const track = stream.getVideoTracks()[0];
    const settings = track && typeof track.getSettings === "function"
      ? track.getSettings()
      : null;
    if (settings && settings.facingMode && settings.facingMode !== "environment") {
      setStatus("Back camera unavailable on this device; using default camera.");
    }
  } catch (err) {
    setStatus(`Camera error: ${err.message}`);
  }
};

const stopCamera = () => {
  if (state.cameraStream) {
    state.cameraStream.getTracks().forEach((track) => track.stop());
    state.cameraStream = null;
  }
  elements.capturePhoto.disabled = true;
  elements.stopCamera.disabled = true;
};

const capturePhoto = () => {
  const video = elements.cameraVideo;
  const canvas = elements.cameraCanvas;
  canvas.width = video.videoWidth || 640;
  canvas.height = video.videoHeight || 480;
  const ctx = canvas.getContext("2d");
  ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
  canvas.toBlob((blob) => {
    if (!blob) return;
    state.lastImageBlob = blob;
    state.lastImageName = "camera-capture.jpg";
    showImagePreview(elements.cameraPreview, blob, "camera-capture.jpg");
    saveWorkspaceAutosaveSnapshot();
  }, "image/jpeg", 0.9);
};

const healthCheck = async () => {
  const baseUrl = getBaseUrl();
  try {
    setStatus("Checking health...");
    const response = await requestHealthCheck(baseUrl);
    const healthLog = response.ok
      ? `GET /api/braille/health -> ${response.status} ${response.statusText}`
      : `GET /api/braille/health -> ${response.status} ${response.statusText} | ${response.text}`;
    addLogEntry(healthLog);
    setStatus(response.ok ? response.text : `Health check failed: ${response.text}`);
  } catch (err) {
    setStatus(`Health check error: ${err.message}`);
    setError(`GET /api/braille/health\n${err.message}`);
  }
};

elements.tabs.forEach((tab) => {
  tab.addEventListener("click", () => {
    const mode = tab.dataset.mode;
    if (mode === "text") {
      elements.textInput.value = "";
    }
    setMode(mode);
    updateSettings({ startupMode: mode });
  });
});

const redirectedToStartup = maybeRedirectToStartupPage(currentSettings);

if (!redirectedToStartup) {
  hydrateWorkspaceSessionStore();
  if (state.autosaveSession) {
    setStatus("Autosave available in Settings > Session Recovery.");
  }
  mountSettingsModal({
    openButton: elements.settingsOpen,
    modalTitle: "Workspace Settings",
    sessionManager: workspaceSessionManager,
    onSettingsSaved: () => {
      setStatus("Settings updated.");
    }
  });

  let previousSettings = currentSettings;
  subscribeSettings((settings, source) => {
    const shouldReloadTables = source !== "init" && (
      settings.apiBaseUrl !== previousSettings.apiBaseUrl ||
      settings.defaultCode !== previousSettings.defaultCode
    );
    applyWorkspaceSettings(settings, { reloadTables: shouldReloadTables });
    previousSettings = settings;
  });

  elements.imageInput.addEventListener("change", (e) => handleImageInput(e.target.files[0]));
  elements.textInput.addEventListener("input", () => {
    saveWorkspaceAutosaveSnapshot();
  });
  elements.tableSelect.addEventListener("change", () => {
    const selected = getSelectedTable();
    const patch = { workspaceTable: selected };
    if (currentSettings.defaultCode === "SEB") {
      patch.sebTable = selected;
    } else {
      patch.uebTable = selected;
    }
    updateSettings(patch);
    saveWorkspaceAutosaveSnapshot();
  });
  elements.helpToggle.addEventListener("click", () => setHelpOpen(!state.helpOpen));
  elements.helpClose.addEventListener("click", () => setHelpOpen(false));
  elements.helpOverlay.addEventListener("click", () => setHelpOpen(false));
  elements.startCamera.addEventListener("click", startCamera);
  elements.stopCamera.addEventListener("click", stopCamera);
  elements.capturePhoto.addEventListener("click", capturePhoto);
  elements.sendBtn.addEventListener("click", sendRequest);
  elements.healthCheck.addEventListener("click", healthCheck);
  elements.skipTyping.addEventListener("click", () => finishTyping(state.currentTypingMessage));
  elements.clearError.addEventListener("click", () => setError(null));
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "hidden") {
      saveWorkspaceAutosaveSnapshot();
    }
  });
  window.addEventListener("beforeunload", saveWorkspaceAutosaveSnapshot);
}

document.addEventListener("keydown", (event) => {
  if (event.key === "Escape") {
    if (state.helpOpen) {
      setHelpOpen(false);
    }
    if (closeAllDownloadMenus()) {
      renderMessages();
    }
  }
});

document.addEventListener("click", (event) => {
  if (event.target.closest(".download-menu")) {
    return;
  }
  if (closeAllDownloadMenus()) {
    renderMessages();
  }
});

window.addEventListener("resize", chatRenderer.positionDownloadPopovers);

if (!redirectedToStartup) {
  const startupMode = ["text", "image", "camera"].includes(currentSettings.startupMode)
    ? currentSettings.startupMode
    : "text";
  setMode(startupMode, { persistSession: false });
  loadTables();
}

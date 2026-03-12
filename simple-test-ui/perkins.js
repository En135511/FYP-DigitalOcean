import {
  fetchBrailleTables,
  requestBrailleDownload,
  requestBrailleTranslation
} from "./src/api-client.js";
import {
  applyDisplaySettings,
  getApiBaseUrl,
  getSettings,
  subscribeSettings,
  updateSettings
} from "./src/app-settings.js";
import { mountSettingsModal } from "./src/settings-modal.js";

const FALLBACK_TABLES = ["en-ueb-g2.ctb", "en-us-g2.ctb", "en-us-g1.ctb"];
const PERKINS_AUTOSAVE_STORAGE_KEY = "brailleai.perkins.autosave.v1";
const PERKINS_SESSION_LIST_STORAGE_KEY = "brailleai.perkins.sessions.v1";
const PERKINS_LEGACY_SESSION_STORAGE_KEY = "brailleai.perkins.session.v1";
const MAX_SESSION_ENTRIES = 12;
const MAX_BRAILLE_CHARS = 20000;
const MAX_TEXT_CHARS = 40000;
const MAX_DELETED_RECOVERY_ENTRIES = 120;
const ACTION_COMBO_TIMEOUT_MS = 180;

const state = {
  settings: getSettings(),
  tables: [],
  savedSessions: [],
  autosaveSession: null,
  pressedDots: new Set(),
  chordDots: new Set(),
  actionKeysDown: new Set(),
  deletedRecoveryStack: [],
  brailleBuffer: "",
  pendingSpeakType: null,
  lastTranslatedText: "",
  lastSpokenWord: "",
  lastSpokenLine: "",
  translateSeq: 0,
  debounceTimer: null,
  pendingActionTimer: null,
  pendingAction: null,
  comboRecoverActive: false,
  chordTimeoutTimer: null,
  audioContext: null,
  needsRestoreTranslation: false
};

const elements = {
  settingsOpen: document.getElementById("settingsOpen"),
  codeSelect: document.getElementById("codeSelect"),
  uebTableSelect: document.getElementById("uebTableSelect"),
  sebTableSelect: document.getElementById("sebTableSelect"),
  focusCapture: document.getElementById("focusCapture"),
  captureZone: document.getElementById("captureZone"),
  dotGrid: document.getElementById("dotGrid"),
  chordPreview: document.getElementById("chordPreview"),
  brailleBuffer: document.getElementById("brailleBuffer"),
  translatedTextOutput: document.getElementById("translatedTextOutput"),
  spaceBtn: document.getElementById("spaceBtn"),
  backspaceBtn: document.getElementById("backspaceBtn"),
  enterBtn: document.getElementById("enterBtn"),
  clearBrailleBtn: document.getElementById("clearBrailleBtn"),
  downloadBrfBtn: document.getElementById("downloadBrfBtn"),
  downloadDocxBtn: document.getElementById("downloadDocxBtn"),
  downloadPdfBtn: document.getElementById("downloadPdfBtn"),
  activeTableLabel: document.getElementById("activeTableLabel"),
  status: document.getElementById("status")
};

const setStatus = (text, isError = false) => {
  elements.status.textContent = text;
  elements.status.classList.toggle("error", isError);
};

const addLogEntry = (entry) => {
  const timestamp = new Date().toLocaleTimeString();
  console.debug(`[Perkins Lab][${timestamp}] ${entry}`);
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
  const date = new Date(value);
  return date.toLocaleString([], {
    year: "numeric",
    month: "short",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit"
  });
};

const buildSessionLabel = (timestamp) => `Session ${formatSessionTime(timestamp)}`;

const normalizeSessionRecord = (record) => {
  if (!record || typeof record !== "object") return null;
  const brailleBuffer = asLimitedString(record.brailleBuffer, MAX_BRAILLE_CHARS);
  const translatedText = asLimitedString(record.translatedText, MAX_TEXT_CHARS);
  if (!brailleBuffer.trim() && !translatedText.trim()) {
    return null;
  }

  const savedAt = Number(record.savedAt);
  const activeCode = record.activeCode === "SEB" ? "SEB" : "UEB";
  const activeTable = asLimitedString(record.activeTable, 240);
  const id = typeof record.id === "string" && record.id.trim()
    ? record.id.trim()
    : createSessionId();
  const labelRaw = asLimitedString(record.label, 80).trim();

  return {
    id,
    label: labelRaw || buildSessionLabel(Number.isFinite(savedAt) ? savedAt : Date.now()),
    savedAt: Number.isFinite(savedAt) ? savedAt : Date.now(),
    activeCode,
    activeTable,
    brailleBuffer,
    translatedText
  };
};

const buildSnapshotFromCurrent = (label = null) => {
  const now = Date.now();
  const normalized = normalizeSessionRecord({
    id: createSessionId(),
    label: label || buildSessionLabel(now),
    savedAt: now,
    activeCode: elements.codeSelect.value === "SEB" ? "SEB" : "UEB",
    activeTable: getActiveTable(),
    brailleBuffer: state.brailleBuffer,
    translatedText: elements.translatedTextOutput.value || state.lastTranslatedText
  });
  return normalized;
};

const sessionResult = (ok, message) => ({ ok, message });

const toSessionViewItem = (session, { autosave = false } = {}) => {
  if (!session) return null;
  const label = autosave ? "Autosave Draft" : session.label;
  const meta = `${session.activeCode}${session.activeTable ? ` | ${session.activeTable}` : ""}`;
  return {
    id: session.id,
    label,
    savedAt: session.savedAt,
    meta
  };
};

const persistSessionList = () => {
  try {
    window.localStorage.setItem(PERKINS_SESSION_LIST_STORAGE_KEY, JSON.stringify(state.savedSessions));
  } catch (_) {
    // Ignore storage errors.
  }
};

const loadSessionList = () => {
  const incoming = parseStoredJson(PERKINS_SESSION_LIST_STORAGE_KEY, []);
  const records = Array.isArray(incoming) ? incoming : [];
  const normalized = records
    .map(normalizeSessionRecord)
    .filter(Boolean)
    .sort((a, b) => b.savedAt - a.savedAt)
    .slice(0, MAX_SESSION_ENTRIES);
  state.savedSessions = normalized;
  persistSessionList();
};

const clearAutosaveSnapshot = () => {
  try {
    window.localStorage.removeItem(PERKINS_AUTOSAVE_STORAGE_KEY);
  } catch (_) {
    // Ignore storage errors.
  }
  state.autosaveSession = null;
};

const loadAutosaveSnapshot = () => {
  const parsed = parseStoredJson(PERKINS_AUTOSAVE_STORAGE_KEY, null);
  const normalized = normalizeSessionRecord(parsed);
  state.autosaveSession = normalized;
};

const clearLegacySessionData = () => {
  try {
    window.localStorage.removeItem(PERKINS_LEGACY_SESSION_STORAGE_KEY);
  } catch (_) {
    // Ignore storage errors.
  }
};

const listPerkinsSessions = () => ({
  autosave: toSessionViewItem(state.autosaveSession, { autosave: true }),
  saved: state.savedSessions
    .map((session) => toSessionViewItem(session))
    .filter(Boolean)
});

const clearSessionSnapshot = () => {
  clearAutosaveSnapshot();
};

const saveSessionSnapshot = () => {
  const snapshot = buildSnapshotFromCurrent("Autosave Draft");
  if (!snapshot) {
    clearAutosaveSnapshot();
    return;
  }
  state.autosaveSession = { ...snapshot, id: "autosave", label: "Autosave Draft" };
  try {
    window.localStorage.setItem(PERKINS_AUTOSAVE_STORAGE_KEY, JSON.stringify(state.autosaveSession));
  } catch (_) {
    // Ignore storage errors.
  }
};

const restoreSessionSnapshot = (session, { fromAutosave = false } = {}) => {
  const normalized = normalizeSessionRecord(session);
  if (!normalized) {
    return false;
  }

  clearPendingAction();
  state.actionKeysDown.clear();
  state.comboRecoverActive = false;
  state.deletedRecoveryStack = [];
  state.brailleBuffer = normalized.brailleBuffer;
  state.lastTranslatedText = normalized.translatedText;
  state.pendingSpeakType = null;
  state.needsRestoreTranslation = Boolean(normalized.brailleBuffer && !normalized.translatedText);

  elements.brailleBuffer.value = state.brailleBuffer;
  elements.translatedTextOutput.value = normalized.translatedText;

  if (elements.codeSelect.value !== normalized.activeCode) {
    elements.codeSelect.value = normalized.activeCode;
    if (state.settings.defaultCode !== normalized.activeCode) {
      updateSettings({
        defaultCode: normalized.activeCode,
        startupMode: "perkins"
      });
    }
  }

  if (normalized.activeTable) {
    const targetSelect = normalized.activeCode === "SEB" ? elements.sebTableSelect : elements.uebTableSelect;
    if (Array.from(targetSelect.options).some((option) => option.value === normalized.activeTable)) {
      targetSelect.value = normalized.activeTable;
    }
  }

  syncActiveTableLabel();
  saveSessionSnapshot();
  if (state.needsRestoreTranslation) {
    if (getActiveTable()) {
      state.needsRestoreTranslation = false;
      schedulePerkinsTranslation(true);
    }
  }
  elements.captureZone.focus();
  setStatus(fromAutosave ? "Autosave restored." : "Session restored.");
  return true;
};

const saveCurrentSessionToList = () => {
  const snapshot = buildSnapshotFromCurrent();
  if (!snapshot) {
    return sessionResult(false, "Nothing to save yet.");
  }

  state.savedSessions = [snapshot, ...state.savedSessions]
    .sort((a, b) => b.savedAt - a.savedAt)
    .slice(0, MAX_SESSION_ENTRIES);
  persistSessionList();
  return sessionResult(true, "Session saved.");
};

const hydrateSessionStore = () => {
  clearLegacySessionData();
  loadAutosaveSnapshot();
  loadSessionList();
};

const restoreAutosaveSession = () => {
  if (!state.autosaveSession) {
    return sessionResult(false, "No autosave available.");
  }
  const restored = restoreSessionSnapshot(state.autosaveSession, { fromAutosave: true });
  return restored
    ? sessionResult(true, "Autosave restored.")
    : sessionResult(false, "Autosave restore failed.");
};

const restoreSavedSessionById = (id) => {
  const target = state.savedSessions.find((session) => session.id === id);
  if (!target) {
    return sessionResult(false, "Saved session not found.");
  }
  const restored = restoreSessionSnapshot(target);
  return restored
    ? sessionResult(true, "Session restored.")
    : sessionResult(false, "Session restore failed.");
};

const deleteSavedSessionById = (id) => {
  const previousCount = state.savedSessions.length;
  state.savedSessions = state.savedSessions.filter((entry) => entry.id !== id);
  if (state.savedSessions.length === previousCount) {
    return sessionResult(false, "Saved session not found.");
  }
  persistSessionList();
  return sessionResult(true, "Saved session deleted.");
};

const clearAutosaveSession = () => {
  if (!state.autosaveSession) {
    return sessionResult(false, "No autosave available.");
  }
  clearAutosaveSnapshot();
  return sessionResult(true, "Autosave cleared.");
};

const perkinsSessionManager = {
  title: "Perkins Session Recovery",
  description: "Recover Perkins work after outage or restart.",
  listSessions: () => listPerkinsSessions(),
  saveCurrent: () => saveCurrentSessionToList(),
  restoreAutosave: () => restoreAutosaveSession(),
  restoreSession: (id) => restoreSavedSessionById(id),
  deleteSession: (id) => deleteSavedSessionById(id),
  clearAutosave: () => clearAutosaveSession()
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

const normalizeTables = (tables) => {
  if (!Array.isArray(tables)) return [];
  const seen = new Set();
  tables.forEach((table) => {
    if (typeof table === "string" && table.trim()) {
      seen.add(table.trim());
    }
  });
  return Array.from(seen).sort((a, b) => a.localeCompare(b));
};

const chooseDefaultTable = (tables, code) => {
  const preferred = code === "SEB" ? state.settings.sebTable : state.settings.uebTable;
  if (preferred && tables.includes(preferred)) {
    return preferred;
  }

  const patterns = code === "SEB"
    ? [/uganda/i, /\bseb\b/i, /en-us-g2/i, /en-uk-g2/i, /en-gb-g2/i]
    : [/en-ueb-g2/i, /\bueb\b/i, /en-ueb/i, /en-us-g2/i];
  for (const pattern of patterns) {
    const match = tables.find((item) => pattern.test(item));
    if (match) return match;
  }
  return tables[0] || "";
};

const fillSelectOptions = (select, options) => {
  select.innerHTML = "";
  options.forEach((value) => {
    const option = document.createElement("option");
    option.value = value;
    option.textContent = value;
    select.appendChild(option);
  });
};

const getActiveTable = () =>
  elements.codeSelect.value === "SEB" ? elements.sebTableSelect.value : elements.uebTableSelect.value;

const syncActiveTableLabel = () => {
  const activeTable = getActiveTable();
  elements.activeTableLabel.textContent = `Active table: ${activeTable || "-"}`;
};

const getBaseUrl = () => getApiBaseUrl(state.settings);

const getKeyConfig = () => state.settings.perkinsKeymap || {
  dot1: "f",
  dot2: "d",
  dot3: "s",
  dot4: "j",
  dot5: "k",
  dot6: "l",
  backspace: "a",
  enter: ";",
  space: " "
};

const matchesMappedKey = (event, mappedKey) => {
  const target = (mappedKey || "").toLowerCase();
  if (!target) return false;
  if (target === " ") {
    return event.key === " " || event.code === "Space";
  }
  if (target === ";") {
    return event.key === ";" || event.code === "Semicolon";
  }
  return event.key.toLowerCase() === target;
};

const resolveDotFromEvent = (event) => {
  const keyConfig = getKeyConfig();
  if (matchesMappedKey(event, keyConfig.dot1)) return 1;
  if (matchesMappedKey(event, keyConfig.dot2)) return 2;
  if (matchesMappedKey(event, keyConfig.dot3)) return 3;
  if (matchesMappedKey(event, keyConfig.dot4)) return 4;
  if (matchesMappedKey(event, keyConfig.dot5)) return 5;
  if (matchesMappedKey(event, keyConfig.dot6)) return 6;
  return null;
};

const parseHotkey = (value) => {
  const fallback = { ctrl: false, alt: true, shift: false, meta: false, key: "r" };
  const raw = (value || "").toLowerCase().trim();
  if (!raw) return fallback;
  const parts = raw.split("+").map((item) => item.trim()).filter(Boolean);
  const modifiers = new Set(["ctrl", "alt", "shift", "meta", "cmd", "win"]);
  const key = parts.find((part) => !modifiers.has(part)) || "r";
  return {
    ctrl: parts.includes("ctrl"),
    alt: parts.includes("alt"),
    shift: parts.includes("shift"),
    meta: parts.includes("meta") || parts.includes("cmd") || parts.includes("win"),
    key
  };
};

const isRepeatHotkey = (event) => {
  const hotkey = parseHotkey(state.settings.repeatHotkey);
  return (
    event.ctrlKey === hotkey.ctrl &&
    event.altKey === hotkey.alt &&
    event.shiftKey === hotkey.shift &&
    event.metaKey === hotkey.meta &&
    event.key.toLowerCase() === hotkey.key
  );
};

const createAudioContext = () => {
  const ContextClass = window.AudioContext || window.webkitAudioContext;
  if (!ContextClass) return null;
  if (!state.audioContext) {
    state.audioContext = new ContextClass();
  }
  return state.audioContext;
};

const playTone = (kind) => {
  const enabled = (
    (kind === "key" && state.settings.soundKeyTone) ||
    (kind === "chord" && state.settings.soundChordTone) ||
    (kind === "space" && state.settings.soundSpaceTone) ||
    (kind === "error" && state.settings.soundErrorTone)
  );
  if (!enabled) return;

  const context = createAudioContext();
  if (!context) return;

  const oscillator = context.createOscillator();
  const gain = context.createGain();
  const now = context.currentTime;
  const frequencyByKind = { key: 620, chord: 780, space: 520, error: 220 };
  oscillator.frequency.value = frequencyByKind[kind] || 600;
  oscillator.type = kind === "error" ? "square" : "sine";
  gain.gain.setValueAtTime(0.0001, now);
  gain.gain.exponentialRampToValueAtTime(0.06, now + 0.01);
  gain.gain.exponentialRampToValueAtTime(0.0001, now + 0.07);
  oscillator.connect(gain);
  gain.connect(context.destination);
  oscillator.start(now);
  oscillator.stop(now + 0.08);
};

const canSpeak = () =>
  state.settings.ttsEnabled && "speechSynthesis" in window;

const cleanWord = (value) =>
  value.replace(/^[^A-Za-z0-9']+|[^A-Za-z0-9']+$/g, "");

const extractLastWord = (text) => {
  const matches = (text || "").trim().match(/[^\s]+/g);
  if (!matches || matches.length === 0) return "";
  return cleanWord(matches[matches.length - 1]);
};

const extractLastLine = (text) => {
  const lines = (text || "").split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
  return lines.length > 0 ? lines[lines.length - 1] : "";
};

const speakText = (text) => {
  if (!canSpeak()) return;
  const value = (text || "").trim();
  if (!value) return;

  window.speechSynthesis.cancel();
  window.speechSynthesis.resume();
  const utterance = new SpeechSynthesisUtterance(value);
  utterance.rate = state.settings.ttsRate;
  utterance.pitch = state.settings.ttsPitch;
  const configuredVolume = Number(state.settings.ttsVolume);
  utterance.volume = Number.isFinite(configuredVolume)
    ? Math.min(1, Math.max(0.85, configuredVolume))
    : 1;

  if (state.settings.ttsVoice !== "default") {
    const voices = window.speechSynthesis.getVoices();
    const voice = voices.find((item) => item.voiceURI === state.settings.ttsVoice);
    if (voice) {
      utterance.voice = voice;
      utterance.lang = voice.lang;
    }
  } else {
    utterance.lang = "en-US";
  }

  window.speechSynthesis.speak(utterance);
  addLogEntry(`Spoken -> ${value}`);
};

const speakWord = (word) => {
  const normalized = (word || "").trim();
  if (!normalized) return;
  state.lastSpokenWord = normalized;
  speakText(normalized);
};

const speakLine = (line) => {
  const normalized = (line || "").trim();
  if (!normalized) return;
  state.lastSpokenLine = normalized;
  speakText(normalized);
};

const repeatLastSpokenUnit = () => {
  if (state.lastSpokenWord) {
    speakText(state.lastSpokenWord);
    return;
  }
  if (state.lastSpokenLine) {
    speakText(state.lastSpokenLine);
  }
};

const updateDotGrid = () => {
  const dotCells = elements.dotGrid.querySelectorAll(".dot-cell");
  dotCells.forEach((cell) => {
    const dot = Number(cell.dataset.dot);
    cell.classList.toggle("active", state.chordDots.has(dot));
  });

  if (state.chordDots.size === 0) {
    elements.chordPreview.textContent = "No keys pressed";
    return;
  }
  const dots = [1, 2, 3, 4, 5, 6].filter((dot) => state.chordDots.has(dot));
  elements.chordPreview.textContent = `Dots: ${dots.join(", ")}`;
};

const brailleCharFromDots = (dots) => {
  let mask = 0;
  dots.forEach((dot) => {
    mask |= 1 << (dot - 1);
  });
  return String.fromCodePoint(0x2800 + mask);
};

const getEffectiveChordTimeout = () => {
  const base = Number(state.settings.perkinsChordTimeoutMs) || 300;
  const modifierBySensitivity = { low: 120, normal: 0, high: -80 };
  const modifier = modifierBySensitivity[state.settings.perkinsHoldSensitivity] || 0;
  return Math.max(120, base + modifier);
};

const clearChordTimeout = () => {
  if (state.chordTimeoutTimer) {
    clearTimeout(state.chordTimeoutTimer);
    state.chordTimeoutTimer = null;
  }
};

const scheduleChordTimeout = () => {
  clearChordTimeout();
  state.chordTimeoutTimer = setTimeout(() => {
    if (state.chordDots.size > 0) {
      commitChord();
    }
  }, getEffectiveChordTimeout());
};

const refreshBrailleBufferView = () => {
  elements.brailleBuffer.value = state.brailleBuffer;
};

const schedulePerkinsTranslation = (immediate = false) => {
  if (state.debounceTimer) {
    clearTimeout(state.debounceTimer);
    state.debounceTimer = null;
  }
  if (immediate) {
    translatePerkins();
    return;
  }
  state.debounceTimer = setTimeout(translatePerkins, 180);
};

const pushDeletedRecoverySnapshot = () => {
  const current = state.brailleBuffer;
  const previous = state.deletedRecoveryStack[state.deletedRecoveryStack.length - 1];
  if (previous === current) return;
  state.deletedRecoveryStack.push(current);
  if (state.deletedRecoveryStack.length > MAX_DELETED_RECOVERY_ENTRIES) {
    state.deletedRecoveryStack.shift();
  }
};

const clearPendingAction = () => {
  if (state.pendingActionTimer) {
    clearTimeout(state.pendingActionTimer);
    state.pendingActionTimer = null;
  }
  state.pendingAction = null;
};

const executePerkinsAction = (action) => {
  if (action === "backspace") {
    flushChordIfAny();
    removeLastBrailleChar();
    return;
  }
  if (action === "enter") {
    insertNewLine();
  }
};

const queuePerkinsAction = (action) => {
  clearPendingAction();
  state.pendingAction = action;
  state.pendingActionTimer = setTimeout(() => {
    if (!state.comboRecoverActive && state.pendingAction === action) {
      executePerkinsAction(action);
    }
    clearPendingAction();
  }, ACTION_COMBO_TIMEOUT_MS);
};

const resolveActionKeyFromEvent = (event, keyConfig) => {
  if (matchesMappedKey(event, keyConfig.backspace)) {
    return "backspace";
  }
  if (matchesMappedKey(event, keyConfig.enter) || event.key === "Enter") {
    return "enter";
  }
  return null;
};

const recoverLastDeletedBraille = () => {
  flushChordIfAny();
  const previous = state.deletedRecoveryStack.pop();
  if (typeof previous !== "string") {
    setStatus("Nothing deleted to recover yet.");
    return;
  }
  state.brailleBuffer = previous;
  state.pendingSpeakType = null;
  state.lastTranslatedText = "";
  refreshBrailleBufferView();
  saveSessionSnapshot();
  schedulePerkinsTranslation(true);
  setStatus("Recovered deleted input.");
};

const appendBrailleChar = (char, pendingSpeakType = null) => {
  state.brailleBuffer += char;
  state.pendingSpeakType = pendingSpeakType;
  refreshBrailleBufferView();
  saveSessionSnapshot();
  schedulePerkinsTranslation(pendingSpeakType !== null);
};

const removeLastBrailleChar = () => {
  if (!state.brailleBuffer) return;
  pushDeletedRecoverySnapshot();
  state.brailleBuffer = state.brailleBuffer.slice(0, -1);
  state.pendingSpeakType = null;
  refreshBrailleBufferView();
  saveSessionSnapshot();
  schedulePerkinsTranslation(true);
};

function commitChord() {
  if (state.chordDots.size === 0) return;
  const char = brailleCharFromDots(state.chordDots);
  state.chordDots.clear();
  state.pressedDots.clear();
  clearChordTimeout();
  updateDotGrid();
  appendBrailleChar(char, null);
  playTone("chord");
}

const flushChordIfAny = () => {
  if (state.chordDots.size > 0) {
    commitChord();
  }
};

const insertSpace = () => {
  flushChordIfAny();
  appendBrailleChar(" ", "word");
  playTone("space");
};

const insertNewLine = () => {
  flushChordIfAny();
  appendBrailleChar("\n", "line");
  playTone("space");
};

const clearBraille = () => {
  if (state.brailleBuffer) {
    pushDeletedRecoverySnapshot();
  }
  clearPendingAction();
  state.actionKeysDown.clear();
  state.comboRecoverActive = false;
  state.brailleBuffer = "";
  state.pendingSpeakType = null;
  state.lastTranslatedText = "";
  state.needsRestoreTranslation = false;
  refreshBrailleBufferView();
  elements.translatedTextOutput.value = "";
  clearSessionSnapshot();
  setStatus("Perkins buffer cleared.");
};

const setDownloadButtonsDisabled = (disabled) => {
  elements.downloadBrfBtn.disabled = disabled;
  elements.downloadDocxBtn.disabled = disabled;
  elements.downloadPdfBtn.disabled = disabled;
};

const downloadPerkins = async (format) => {
  const normalizedFormat = (format || "").toUpperCase();
  if (!["BRF", "DOCX", "PDF"].includes(normalizedFormat)) {
    return;
  }

  const baseUrl = getBaseUrl();
  if (!baseUrl) {
    setStatus("Download unavailable: API is not configured.", true);
    return;
  }
  if (!state.brailleBuffer.trim()) {
    setStatus("Enter Perkins input before downloading.", true);
    return;
  }

  const table = getActiveTable();
  if (!table) {
    setStatus("Select a table for the active code first.", true);
    return;
  }

  let payload;
  let filename;

  if (normalizedFormat === "BRF") {
    const translated = (elements.translatedTextOutput.value || "").trim();
    if (!translated) {
      setStatus("Translate text first, then download BRF.", true);
      return;
    }
    payload = {
      input: translated,
      brailleUnicode: translated,
      direction: "TEXT_TO_BRAILLE",
      outputFormat: "BRF",
      table
    };
    filename = "perkins-output.brf";
  } else {
    payload = {
      input: state.brailleBuffer,
      brailleUnicode: state.brailleBuffer,
      direction: "BRAILLE_TO_TEXT",
      outputFormat: normalizedFormat,
      table
    };
    filename = `perkins-translation.${normalizedFormat.toLowerCase()}`;
  }

  try {
    setDownloadButtonsDisabled(true);
    setStatus(`Preparing ${normalizedFormat} download...`);
    const response = await requestBrailleDownload(baseUrl, payload);
    if (!response.ok) {
      const reason = await response.text();
      throw new Error(reason || `Download failed (${response.status})`);
    }

    const blob = await response.blob();
    triggerDownload(blob, filename);
    setStatus(`${normalizedFormat} download ready.`);
    addLogEntry(`POST /api/braille/download -> ${response.status} ${response.statusText} (${normalizedFormat})`);
  } catch (err) {
    setStatus(`Download error: ${err.message}`, true);
    addLogEntry(`Download error (${normalizedFormat}) -> ${err.message}`);
    playTone("error");
  } finally {
    setDownloadButtonsDisabled(false);
  }
};

const translateWithApi = async (input) => {
  const payload = {
    input,
    brailleUnicode: input,
    direction: "BRAILLE_TO_TEXT",
    outputFormat: "PDF",
    table: getActiveTable()
  };
  if (!payload.table) {
    throw new Error("Select a table for the active code.");
  }

  const response = await requestBrailleTranslation(getBaseUrl(), payload);
  if (!response.ok) {
    throw new Error(response.text || "Translation request failed.");
  }
  return response.data || {};
};

const translatePerkins = async () => {
  const input = state.brailleBuffer;
  if (!input) {
    state.lastTranslatedText = "";
    state.needsRestoreTranslation = false;
    elements.translatedTextOutput.value = "";
    saveSessionSnapshot();
    setStatus("Perkins buffer is empty.");
    return;
  }

  const seq = ++state.translateSeq;
  try {
    setStatus("Translating Braille to text...");
    const data = await translateWithApi(input);
    if (seq !== state.translateSeq) return;

    const translatedText = data.translatedText || "";
    const previous = state.lastTranslatedText;
    state.lastTranslatedText = translatedText;
    state.needsRestoreTranslation = false;
    elements.translatedTextOutput.value = translatedText;
    saveSessionSnapshot();
    setStatus("Braille translated.");

    if (state.pendingSpeakType === "word" && state.settings.speechWords) {
      speakWord(extractLastWord(translatedText));
    } else if (state.pendingSpeakType === "line" && state.settings.speechLines) {
      speakLine(extractLastLine(translatedText));
    } else if (
      state.settings.speechLetters &&
      translatedText.length > previous.length &&
      translatedText.startsWith(previous)
    ) {
      const nextChar = translatedText.slice(previous.length, previous.length + 1);
      if (nextChar && /\S/.test(nextChar)) {
        speakText(nextChar);
      }
    }
    state.pendingSpeakType = null;
  } catch (err) {
    if (seq !== state.translateSeq) return;
    setStatus(`Braille translation error: ${err.message}`, true);
    addLogEntry(`Braille translation error -> ${err.message}`);
    playTone("error");
  }
};

const refreshForCodeChange = () => {
  syncActiveTableLabel();
  saveSessionSnapshot();
  if (state.brailleBuffer) {
    schedulePerkinsTranslation(true);
  }
};

const applyPerkinsSettings = (settings, { reloadTables = false } = {}) => {
  state.settings = settings;
  applyDisplaySettings(settings);

  if (elements.codeSelect.value !== settings.defaultCode) {
    elements.codeSelect.value = settings.defaultCode;
  }
  if (settings.uebTable && Array.from(elements.uebTableSelect.options).some((opt) => opt.value === settings.uebTable)) {
    elements.uebTableSelect.value = settings.uebTable;
  }
  if (settings.sebTable && Array.from(elements.sebTableSelect.options).some((opt) => opt.value === settings.sebTable)) {
    elements.sebTableSelect.value = settings.sebTable;
  }
  if (!settings.ttsEnabled && "speechSynthesis" in window) {
    window.speechSynthesis.cancel();
  }

  if (reloadTables) {
    loadTables();
  } else {
    syncActiveTableLabel();
  }
};

const loadTables = async () => {
  const baseUrl = getBaseUrl();
  const previousUEB = elements.uebTableSelect.value;
  const previousSEB = elements.sebTableSelect.value;

  if (!baseUrl) {
    const fallback = normalizeTables(FALLBACK_TABLES);
    state.tables = fallback;
    fillSelectOptions(elements.uebTableSelect, fallback);
    fillSelectOptions(elements.sebTableSelect, fallback);
    elements.uebTableSelect.value = chooseDefaultTable(fallback, "UEB");
    elements.sebTableSelect.value = chooseDefaultTable(fallback, "SEB");
    syncActiveTableLabel();
    if (state.needsRestoreTranslation) {
      state.needsRestoreTranslation = false;
      schedulePerkinsTranslation(true);
    }
    setStatus("Using fallback table names.", true);
    return;
  }

  try {
    setStatus("Loading tables...");
    const response = await fetchBrailleTables(baseUrl);
    const payload = response.data || {};
    const incoming = Array.isArray(payload) ? payload : payload.tables;
    const tables = normalizeTables(incoming);
    const normalizedTables = tables.length > 0 ? tables : normalizeTables(FALLBACK_TABLES);

    state.tables = normalizedTables;
    fillSelectOptions(elements.uebTableSelect, normalizedTables);
    fillSelectOptions(elements.sebTableSelect, normalizedTables);

    elements.uebTableSelect.value = normalizedTables.includes(previousUEB)
      ? previousUEB
      : chooseDefaultTable(normalizedTables, "UEB");
    elements.sebTableSelect.value = normalizedTables.includes(previousSEB)
      ? previousSEB
      : chooseDefaultTable(normalizedTables, "SEB");

    syncActiveTableLabel();
    if (state.needsRestoreTranslation) {
      state.needsRestoreTranslation = false;
      schedulePerkinsTranslation(true);
    }
    setStatus("Tables loaded.");
  } catch (err) {
    const fallback = normalizeTables(FALLBACK_TABLES);
    state.tables = fallback;
    fillSelectOptions(elements.uebTableSelect, fallback);
    fillSelectOptions(elements.sebTableSelect, fallback);
    elements.uebTableSelect.value = chooseDefaultTable(fallback, "UEB");
    elements.sebTableSelect.value = chooseDefaultTable(fallback, "SEB");
    syncActiveTableLabel();
    if (state.needsRestoreTranslation) {
      state.needsRestoreTranslation = false;
      schedulePerkinsTranslation(true);
    }
    setStatus("Could not load tables from API. Using fallback tables.", true);
    addLogEntry(`Table load error -> ${err.message}`);
    playTone("error");
  }
};

const handlePerkinsKeyDown = (event) => {
  if (isRepeatHotkey(event)) {
    event.preventDefault();
    repeatLastSpokenUnit();
    return;
  }

  const focusedCapture = document.activeElement === elements.captureZone;
  const target = event.target;
  const inputLike =
    target instanceof HTMLInputElement ||
    target instanceof HTMLTextAreaElement ||
    target instanceof HTMLSelectElement;
  const shouldProcess = focusedCapture || state.pressedDots.size > 0;

  if (!shouldProcess) return;
  if (inputLike && !focusedCapture && state.pressedDots.size === 0) return;

  const keyConfig = getKeyConfig();
  const actionKey = resolveActionKeyFromEvent(event, keyConfig);
  if (actionKey) {
    event.preventDefault();
    if (event.repeat) return;
    state.actionKeysDown.add(actionKey);
    const comboPressed =
      state.actionKeysDown.has("backspace") &&
      state.actionKeysDown.has("enter");
    if (comboPressed) {
      clearPendingAction();
      if (!state.comboRecoverActive) {
        recoverLastDeletedBraille();
      }
      state.comboRecoverActive = true;
      return;
    }

    if (!state.comboRecoverActive) {
      queuePerkinsAction(actionKey);
    }
    return;
  }

  const dot = resolveDotFromEvent(event);
  if (dot) {
    event.preventDefault();
    if (event.repeat && state.settings.perkinsHoldSensitivity !== "high") {
      return;
    }
    state.pressedDots.add(dot);
    state.chordDots.add(dot);
    updateDotGrid();
    scheduleChordTimeout();
    playTone("key");
    return;
  }

  if (matchesMappedKey(event, keyConfig.space)) {
    event.preventDefault();
    insertSpace();
    return;
  }
};

const handlePerkinsKeyUp = (event) => {
  const keyConfig = getKeyConfig();
  const actionKey = resolveActionKeyFromEvent(event, keyConfig);
  if (actionKey) {
    event.preventDefault();
    const hadAction = state.actionKeysDown.has(actionKey);
    state.actionKeysDown.delete(actionKey);
    if (state.comboRecoverActive) {
      if (state.actionKeysDown.size === 0) {
        state.comboRecoverActive = false;
      }
      return;
    }
    if (hadAction && state.pendingAction === actionKey) {
      clearPendingAction();
      executePerkinsAction(actionKey);
    }
    return;
  }

  const dot = resolveDotFromEvent(event);
  if (!dot) return;
  if (!state.pressedDots.has(dot) && !state.chordDots.has(dot)) return;

  event.preventDefault();
  state.pressedDots.delete(dot);
  if (state.pressedDots.size === 0 && state.chordDots.size > 0) {
    commitChord();
  } else {
    updateDotGrid();
  }
};

mountSettingsModal({
  openButton: elements.settingsOpen,
  modalTitle: "Perkins Settings",
  sessionManager: perkinsSessionManager,
  onSettingsSaved: () => {
    setStatus("Settings updated.");
  }
});

let previousSettings = state.settings;
subscribeSettings((settings, source) => {
  const shouldReloadTables = source !== "init" && (
    settings.apiBaseUrl !== previousSettings.apiBaseUrl ||
    settings.defaultCode !== previousSettings.defaultCode
  );
  applyPerkinsSettings(settings, { reloadTables: shouldReloadTables });
  previousSettings = settings;
});

elements.codeSelect.addEventListener("change", () => {
  updateSettings({
    defaultCode: elements.codeSelect.value,
    startupMode: "perkins"
  });
  refreshForCodeChange();
});

elements.uebTableSelect.addEventListener("change", () => {
  updateSettings({ uebTable: elements.uebTableSelect.value });
  refreshForCodeChange();
});

elements.sebTableSelect.addEventListener("change", () => {
  updateSettings({ sebTable: elements.sebTableSelect.value });
  refreshForCodeChange();
});

elements.focusCapture.addEventListener("click", () => elements.captureZone.focus());

elements.captureZone.addEventListener("focus", () => {
  elements.captureZone.classList.add("active");
  elements.captureZone.textContent = "Perkins input active. Type chord keys now.";
});

elements.captureZone.addEventListener("blur", () => {
  elements.captureZone.classList.remove("active");
  elements.captureZone.textContent = "Click here, then type with Perkins keys";
});

elements.spaceBtn.addEventListener("click", insertSpace);
elements.backspaceBtn.addEventListener("click", () => {
  flushChordIfAny();
  removeLastBrailleChar();
});
elements.enterBtn.addEventListener("click", insertNewLine);
elements.clearBrailleBtn.addEventListener("click", clearBraille);
elements.downloadBrfBtn.addEventListener("click", () => downloadPerkins("BRF"));
elements.downloadDocxBtn.addEventListener("click", () => downloadPerkins("DOCX"));
elements.downloadPdfBtn.addEventListener("click", () => downloadPerkins("PDF"));

document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "hidden") {
    saveSessionSnapshot();
  }
});
window.addEventListener("beforeunload", saveSessionSnapshot);

document.addEventListener("keydown", handlePerkinsKeyDown);
document.addEventListener("keyup", handlePerkinsKeyUp);

hydrateSessionStore();
if (state.autosaveSession) {
  setStatus("Autosave available in Settings > Session Recovery.");
}

loadTables();
updateDotGrid();
elements.captureZone.focus();

const SETTINGS_STORAGE_KEY = "brailleai.settings.v1";
const SAME_ORIGIN_API = "same-origin";
const LEGACY_LOCAL_API_VALUES = new Set([
  "http://localhost:8080",
  "https://localhost:8080",
  "http://127.0.0.1:8080",
  "https://127.0.0.1:8080"
]);

const DEFAULT_SETTINGS = Object.freeze({
  theme: "system",
  fontSize: "default",
  lineSpacing: "comfortable",
  density: "comfortable",
  highContrast: false,
  reducedMotion: false,
  operatorMode: "student",
  apiBaseUrl: SAME_ORIGIN_API,
  defaultCode: "UEB",
  workspaceTable: "",
  uebTable: "",
  sebTable: "",
  startupPage: "workspace",
  startupMode: "text",
  ttsEnabled: true,
  ttsVoice: "default",
  ttsRate: 0.95,
  ttsPitch: 1,
  ttsVolume: 1,
  speechLetters: false,
  speechWords: true,
  speechLines: false,
  repeatHotkey: "Alt+R",
  soundKeyTone: false,
  soundChordTone: false,
  soundSpaceTone: false,
  soundErrorTone: true,
  perkinsChordTimeoutMs: 300,
  perkinsHoldSensitivity: "normal",
  perkinsKeymap: {
    dot1: "f",
    dot2: "d",
    dot3: "s",
    dot4: "j",
    dot5: "k",
    dot6: "l",
    backspace: "a",
    enter: ";",
    space: " "
  }
});

const THEME_VALUES = new Set(["system", "dark", "light"]);
const FONT_SIZE_VALUES = new Set(["small", "default", "large", "xl"]);
const LINE_SPACING_VALUES = new Set(["tight", "comfortable", "wide"]);
const DENSITY_VALUES = new Set(["compact", "comfortable"]);
const OPERATOR_MODE_VALUES = new Set(["student", "admin"]);
const CODE_VALUES = new Set(["UEB", "SEB"]);
const STARTUP_PAGE_VALUES = new Set(["workspace", "perkins"]);
const STARTUP_MODE_VALUES = new Set(["text", "image", "camera", "perkins"]);
const SENSITIVITY_VALUES = new Set(["low", "normal", "high"]);

const listeners = new Set();
let cachedSettings = null;

const safeStructuredClone = (value) => {
  if (typeof structuredClone === "function") {
    return structuredClone(value);
  }
  return JSON.parse(JSON.stringify(value));
};

const asBoolean = (value, fallback = false) =>
  typeof value === "boolean" ? value : fallback;

const asNumber = (value, fallback, min, max) => {
  const numeric = Number(value);
  if (!Number.isFinite(numeric)) return fallback;
  return Math.min(max, Math.max(min, numeric));
};

const asString = (value, fallback = "") =>
  typeof value === "string" ? value : fallback;

const normalizeKey = (value, fallback) => {
  const raw = asString(value, fallback).trim();
  if (!raw) return fallback;
  if (raw.toLowerCase() === "space") return " ";
  return raw.slice(0, 1).toLowerCase();
};

const resolveEnum = (value, allowed, fallback) => (
  allowed.has(value) ? value : fallback
);

const normalizeApiBaseUrl = (value) => {
  const raw = asString(value, DEFAULT_SETTINGS.apiBaseUrl).trim();
  if (!raw) return SAME_ORIGIN_API;
  const normalized = raw.replace(/\/$/, "");
  const lowered = normalized.toLowerCase();
  if (lowered === SAME_ORIGIN_API || lowered === "auto") {
    return SAME_ORIGIN_API;
  }
  if (LEGACY_LOCAL_API_VALUES.has(lowered)) {
    return SAME_ORIGIN_API;
  }
  return normalized;
};

const normalizeSettings = (incoming) => {
  const candidate = incoming && typeof incoming === "object" ? incoming : {};
  const keymap = candidate.perkinsKeymap && typeof candidate.perkinsKeymap === "object"
    ? candidate.perkinsKeymap
    : {};

  const rawStartupMode = asString(candidate.startupMode, DEFAULT_SETTINGS.startupMode);
  const normalizedStartupMode = rawStartupMode === "text-to-braille"
    ? "perkins"
    : rawStartupMode;

  return {
    theme: resolveEnum(asString(candidate.theme, DEFAULT_SETTINGS.theme), THEME_VALUES, DEFAULT_SETTINGS.theme),
    fontSize: resolveEnum(asString(candidate.fontSize, DEFAULT_SETTINGS.fontSize), FONT_SIZE_VALUES, DEFAULT_SETTINGS.fontSize),
    lineSpacing: resolveEnum(asString(candidate.lineSpacing, DEFAULT_SETTINGS.lineSpacing), LINE_SPACING_VALUES, DEFAULT_SETTINGS.lineSpacing),
    density: resolveEnum(asString(candidate.density, DEFAULT_SETTINGS.density), DENSITY_VALUES, DEFAULT_SETTINGS.density),
    highContrast: asBoolean(candidate.highContrast, DEFAULT_SETTINGS.highContrast),
    reducedMotion: asBoolean(candidate.reducedMotion, DEFAULT_SETTINGS.reducedMotion),
    operatorMode: resolveEnum(asString(candidate.operatorMode, DEFAULT_SETTINGS.operatorMode), OPERATOR_MODE_VALUES, DEFAULT_SETTINGS.operatorMode),
    apiBaseUrl: normalizeApiBaseUrl(candidate.apiBaseUrl),
    defaultCode: resolveEnum(asString(candidate.defaultCode, DEFAULT_SETTINGS.defaultCode), CODE_VALUES, DEFAULT_SETTINGS.defaultCode),
    workspaceTable: asString(candidate.workspaceTable, DEFAULT_SETTINGS.workspaceTable).trim(),
    uebTable: asString(candidate.uebTable, DEFAULT_SETTINGS.uebTable).trim(),
    sebTable: asString(candidate.sebTable, DEFAULT_SETTINGS.sebTable).trim(),
    startupPage: resolveEnum(asString(candidate.startupPage, DEFAULT_SETTINGS.startupPage), STARTUP_PAGE_VALUES, DEFAULT_SETTINGS.startupPage),
    startupMode: resolveEnum(normalizedStartupMode, STARTUP_MODE_VALUES, DEFAULT_SETTINGS.startupMode),
    ttsEnabled: asBoolean(candidate.ttsEnabled, DEFAULT_SETTINGS.ttsEnabled),
    ttsVoice: asString(candidate.ttsVoice, DEFAULT_SETTINGS.ttsVoice).trim() || DEFAULT_SETTINGS.ttsVoice,
    ttsRate: asNumber(candidate.ttsRate, DEFAULT_SETTINGS.ttsRate, 0.6, 1.6),
    ttsPitch: asNumber(candidate.ttsPitch, DEFAULT_SETTINGS.ttsPitch, 0.5, 2),
    ttsVolume: asNumber(candidate.ttsVolume, DEFAULT_SETTINGS.ttsVolume, 0, 1),
    speechLetters: asBoolean(candidate.speechLetters, DEFAULT_SETTINGS.speechLetters),
    speechWords: asBoolean(candidate.speechWords, DEFAULT_SETTINGS.speechWords),
    speechLines: asBoolean(candidate.speechLines, DEFAULT_SETTINGS.speechLines),
    repeatHotkey: asString(candidate.repeatHotkey, DEFAULT_SETTINGS.repeatHotkey).trim() || DEFAULT_SETTINGS.repeatHotkey,
    soundKeyTone: asBoolean(candidate.soundKeyTone, DEFAULT_SETTINGS.soundKeyTone),
    soundChordTone: asBoolean(candidate.soundChordTone, DEFAULT_SETTINGS.soundChordTone),
    soundSpaceTone: asBoolean(candidate.soundSpaceTone, DEFAULT_SETTINGS.soundSpaceTone),
    soundErrorTone: asBoolean(candidate.soundErrorTone, DEFAULT_SETTINGS.soundErrorTone),
    perkinsChordTimeoutMs: asNumber(candidate.perkinsChordTimeoutMs, DEFAULT_SETTINGS.perkinsChordTimeoutMs, 120, 1200),
    perkinsHoldSensitivity: resolveEnum(asString(candidate.perkinsHoldSensitivity, DEFAULT_SETTINGS.perkinsHoldSensitivity), SENSITIVITY_VALUES, DEFAULT_SETTINGS.perkinsHoldSensitivity),
    perkinsKeymap: {
      dot1: normalizeKey(keymap.dot1, DEFAULT_SETTINGS.perkinsKeymap.dot1),
      dot2: normalizeKey(keymap.dot2, DEFAULT_SETTINGS.perkinsKeymap.dot2),
      dot3: normalizeKey(keymap.dot3, DEFAULT_SETTINGS.perkinsKeymap.dot3),
      dot4: normalizeKey(keymap.dot4, DEFAULT_SETTINGS.perkinsKeymap.dot4),
      dot5: normalizeKey(keymap.dot5, DEFAULT_SETTINGS.perkinsKeymap.dot5),
      dot6: normalizeKey(keymap.dot6, DEFAULT_SETTINGS.perkinsKeymap.dot6),
      backspace: normalizeKey(keymap.backspace, DEFAULT_SETTINGS.perkinsKeymap.backspace),
      enter: normalizeKey(keymap.enter, DEFAULT_SETTINGS.perkinsKeymap.enter),
      space: normalizeKey(keymap.space, DEFAULT_SETTINGS.perkinsKeymap.space)
    }
  };
};

const saveSettings = (settings) => {
  try {
    window.localStorage.setItem(SETTINGS_STORAGE_KEY, JSON.stringify(settings));
  } catch (_) {
    // Ignore storage failures and keep in-memory settings.
  }
};

const emit = (settings, source = "local") => {
  listeners.forEach((listener) => {
    try {
      listener(safeStructuredClone(settings), source);
    } catch (_) {
      // Listener errors should not break settings propagation.
    }
  });
};

const parseStoredSettings = () => {
  try {
    const raw = window.localStorage.getItem(SETTINGS_STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    return normalizeSettings(parsed);
  } catch (_) {
    return null;
  }
};

const ensureCachedSettings = () => {
  if (cachedSettings) return cachedSettings;
  cachedSettings = parseStoredSettings() || safeStructuredClone(DEFAULT_SETTINGS);
  return cachedSettings;
};

export const getDefaultSettings = () => safeStructuredClone(DEFAULT_SETTINGS);

export const getSettings = () => safeStructuredClone(ensureCachedSettings());

export const updateSettings = (patch) => {
  const current = ensureCachedSettings();
  const merged = {
    ...current,
    ...(patch && typeof patch === "object" ? patch : {}),
    perkinsKeymap: {
      ...current.perkinsKeymap,
      ...((patch && typeof patch === "object" && patch.perkinsKeymap) ? patch.perkinsKeymap : {})
    }
  };
  cachedSettings = normalizeSettings(merged);
  saveSettings(cachedSettings);
  emit(cachedSettings, "local");
  return safeStructuredClone(cachedSettings);
};

export const replaceSettings = (nextSettings) => {
  cachedSettings = normalizeSettings(nextSettings);
  saveSettings(cachedSettings);
  emit(cachedSettings, "local");
  return safeStructuredClone(cachedSettings);
};

export const resetSettings = () => {
  cachedSettings = safeStructuredClone(DEFAULT_SETTINGS);
  saveSettings(cachedSettings);
  emit(cachedSettings, "local");
  return safeStructuredClone(cachedSettings);
};

export const subscribeSettings = (listener, { immediate = true } = {}) => {
  if (typeof listener !== "function") {
    return () => {};
  }
  listeners.add(listener);
  if (immediate) {
    listener(getSettings(), "init");
  }
  return () => {
    listeners.delete(listener);
  };
};

export const exportSettingsJson = () =>
  JSON.stringify(getSettings(), null, 2);

export const importSettingsJson = (jsonText) => {
  const parsed = JSON.parse(jsonText);
  return replaceSettings(parsed);
};

const resolveTheme = (theme) => {
  if (theme === "light" || theme === "dark") return theme;
  const prefersLight = window.matchMedia && window.matchMedia("(prefers-color-scheme: light)").matches;
  return prefersLight ? "light" : "dark";
};

const FONT_SCALE_MAP = {
  small: 0.92,
  default: 1,
  large: 1.14,
  xl: 1.28
};

const LINE_HEIGHT_MAP = {
  tight: 1.35,
  comfortable: 1.5,
  wide: 1.7
};

const DENSITY_MAP = {
  compact: 0.84,
  comfortable: 1
};

export const applyDisplaySettings = (settingsInput) => {
  const settings = normalizeSettings(settingsInput || ensureCachedSettings());
  const root = document.documentElement;
  const body = document.body;
  const resolvedTheme = resolveTheme(settings.theme);

  root.setAttribute("data-theme", resolvedTheme);
  root.setAttribute("data-theme-pref", settings.theme);
  root.style.setProperty("--app-font-scale", String(FONT_SCALE_MAP[settings.fontSize] || 1));
  root.style.setProperty("--app-line-height", String(LINE_HEIGHT_MAP[settings.lineSpacing] || 1.5));
  root.style.setProperty("--app-density", String(DENSITY_MAP[settings.density] || 1));

  if (body) {
    body.dataset.fontSize = settings.fontSize;
    body.dataset.lineSpacing = settings.lineSpacing;
    body.dataset.density = settings.density;
    body.classList.toggle("high-contrast", settings.highContrast);
    body.classList.toggle("reduce-motion", settings.reducedMotion);
  }
};

export const getApiBaseUrl = (settingsInput = null) => {
  const settings = settingsInput ? normalizeSettings(settingsInput) : ensureCachedSettings();
  const normalized = normalizeApiBaseUrl(settings.apiBaseUrl || DEFAULT_SETTINGS.apiBaseUrl);
  if (normalized === SAME_ORIGIN_API) {
    if (typeof window !== "undefined" && window.location && window.location.origin) {
      return window.location.origin.replace(/\/$/, "");
    }
    return "";
  }
  return normalized.replace(/\/$/, "");
};

if (typeof window !== "undefined") {
  window.addEventListener("storage", (event) => {
    if (event.key !== SETTINGS_STORAGE_KEY) return;
    cachedSettings = parseStoredSettings() || safeStructuredClone(DEFAULT_SETTINGS);
    emit(cachedSettings, "storage");
  });

  if (window.matchMedia) {
    const mediaQuery = window.matchMedia("(prefers-color-scheme: light)");
    const onThemeMediaChange = () => {
      const settings = ensureCachedSettings();
      if (settings.theme === "system") {
        applyDisplaySettings(settings);
      }
    };
    if (typeof mediaQuery.addEventListener === "function") {
      mediaQuery.addEventListener("change", onThemeMediaChange);
    } else if (typeof mediaQuery.addListener === "function") {
      mediaQuery.addListener(onThemeMediaChange);
    }
  }
}

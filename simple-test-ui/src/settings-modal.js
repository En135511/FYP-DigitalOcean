import {
  applyDisplaySettings,
  exportSettingsJson,
  getDefaultSettings,
  getSettings,
  importSettingsJson,
  resetSettings,
  updateSettings
} from "./app-settings.js";

const createEl = (html) => {
  const template = document.createElement("template");
  template.innerHTML = html.trim();
  return template.content.firstElementChild;
};

const showMessage = (target, text, isError = false) => {
  target.textContent = text;
  target.classList.toggle("error", isError);
};

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

const normalizeKeyValue = (value) => {
  const raw = (value || "").trim();
  if (!raw) return "";
  if (raw.toLowerCase() === "space") return " ";
  return raw.slice(0, 1).toLowerCase();
};

const populateVoices = (select, selected) => {
  if (!("speechSynthesis" in window)) {
    select.innerHTML = "<option value=\"default\">Default Voice (Unavailable)</option>";
    select.disabled = true;
    return;
  }

  const voices = window.speechSynthesis.getVoices();
  select.innerHTML = "";

  const defaultOption = document.createElement("option");
  defaultOption.value = "default";
  defaultOption.textContent = "Default Voice";
  select.appendChild(defaultOption);

  voices.forEach((voice) => {
    const option = document.createElement("option");
    option.value = voice.voiceURI;
    option.textContent = `${voice.name} (${voice.lang})`;
    select.appendChild(option);
  });

  const preferred = Array.from(select.options).find((option) => option.value === selected);
  select.value = preferred ? selected : "default";
};

const fillForm = (form, settings) => {
  form.theme.value = settings.theme;
  form.fontSize.value = settings.fontSize;
  form.lineSpacing.value = settings.lineSpacing;
  form.density.value = settings.density;
  form.highContrast.checked = settings.highContrast;
  form.reducedMotion.checked = settings.reducedMotion;
  form.operatorMode.value = settings.operatorMode;
  form.defaultCode.value = settings.defaultCode;
  form.uebTable.value = settings.uebTable;
  form.sebTable.value = settings.sebTable;
  form.startupPage.value = settings.startupPage;
  form.startupMode.value = settings.startupMode;
  form.ttsEnabled.checked = settings.ttsEnabled;
  form.ttsRate.value = String(settings.ttsRate);
  form.ttsPitch.value = String(settings.ttsPitch);
  form.ttsVolume.value = String(settings.ttsVolume);
  form.speechLetters.checked = settings.speechLetters;
  form.speechWords.checked = settings.speechWords;
  form.speechLines.checked = settings.speechLines;
  form.repeatHotkey.value = settings.repeatHotkey;
  form.soundKeyTone.checked = settings.soundKeyTone;
  form.soundChordTone.checked = settings.soundChordTone;
  form.soundSpaceTone.checked = settings.soundSpaceTone;
  form.soundErrorTone.checked = settings.soundErrorTone;
  form.perkinsChordTimeoutMs.value = String(settings.perkinsChordTimeoutMs);
  form.perkinsHoldSensitivity.value = settings.perkinsHoldSensitivity;
  form.dot1Key.value = settings.perkinsKeymap.dot1;
  form.dot2Key.value = settings.perkinsKeymap.dot2;
  form.dot3Key.value = settings.perkinsKeymap.dot3;
  form.dot4Key.value = settings.perkinsKeymap.dot4;
  form.dot5Key.value = settings.perkinsKeymap.dot5;
  form.dot6Key.value = settings.perkinsKeymap.dot6;
  form.backspaceKey.value = settings.perkinsKeymap.backspace;
  form.enterKey.value = settings.perkinsKeymap.enter;
  form.spaceKey.value = settings.perkinsKeymap.space === " " ? "space" : settings.perkinsKeymap.space;
  populateVoices(form.ttsVoice, settings.ttsVoice);
};

const readForm = (form) => ({
  theme: form.theme.value,
  fontSize: form.fontSize.value,
  lineSpacing: form.lineSpacing.value,
  density: form.density.value,
  highContrast: form.highContrast.checked,
  reducedMotion: form.reducedMotion.checked,
  operatorMode: form.operatorMode.value,
  defaultCode: form.defaultCode.value,
  uebTable: (form.uebTable.value || "").trim(),
  sebTable: (form.sebTable.value || "").trim(),
  startupPage: form.startupPage.value,
  startupMode: form.startupMode.value,
  ttsEnabled: form.ttsEnabled.checked,
  ttsVoice: form.ttsVoice.value,
  ttsRate: Number(form.ttsRate.value),
  ttsPitch: Number(form.ttsPitch.value),
  ttsVolume: Number(form.ttsVolume.value),
  speechLetters: form.speechLetters.checked,
  speechWords: form.speechWords.checked,
  speechLines: form.speechLines.checked,
  repeatHotkey: (form.repeatHotkey.value || "").trim(),
  soundKeyTone: form.soundKeyTone.checked,
  soundChordTone: form.soundChordTone.checked,
  soundSpaceTone: form.soundSpaceTone.checked,
  soundErrorTone: form.soundErrorTone.checked,
  perkinsChordTimeoutMs: Number(form.perkinsChordTimeoutMs.value),
  perkinsHoldSensitivity: form.perkinsHoldSensitivity.value,
  perkinsKeymap: {
    dot1: normalizeKeyValue(form.dot1Key.value) || "f",
    dot2: normalizeKeyValue(form.dot2Key.value) || "d",
    dot3: normalizeKeyValue(form.dot3Key.value) || "s",
    dot4: normalizeKeyValue(form.dot4Key.value) || "j",
    dot5: normalizeKeyValue(form.dot5Key.value) || "k",
    dot6: normalizeKeyValue(form.dot6Key.value) || "l",
    backspace: normalizeKeyValue(form.backspaceKey.value) || "a",
    enter: normalizeKeyValue(form.enterKey.value) || ";",
    space: normalizeKeyValue(form.spaceKey.value) || " "
  }
});

export const mountSettingsModal = ({
  openButton,
  onSettingsSaved = null,
  modalTitle = "Application Settings",
  sessionManager = null
} = {}) => {
  const overlay = createEl(`
    <div class="settings-overlay hidden" aria-hidden="true"></div>
  `);
  const modal = createEl(`
    <aside class="settings-modal hidden" role="dialog" aria-modal="true" aria-hidden="true" aria-labelledby="settingsTitle">
      <div class="settings-shell">
        <header class="settings-head">
          <div>
            <p class="settings-eyebrow">System Preferences</p>
            <h2 id="settingsTitle">${modalTitle}</h2>
          </div>
          <button type="button" class="btn ghost settings-close" aria-label="Close settings">Close</button>
        </header>
        <form class="settings-form">
          <section class="settings-group">
            <h3>Appearance</h3>
            <div class="settings-grid">
              <label class="field"><span>Theme</span>
                <select name="theme">
                  <option value="system">System</option>
                  <option value="dark">Dark</option>
                  <option value="light">Light</option>
                </select>
              </label>
              <label class="field"><span>Font Size</span>
                <select name="fontSize">
                  <option value="small">Small</option>
                  <option value="default">Default</option>
                  <option value="large">Large</option>
                  <option value="xl">XL</option>
                </select>
              </label>
              <label class="field"><span>Line Spacing</span>
                <select name="lineSpacing">
                  <option value="tight">Tight</option>
                  <option value="comfortable">Comfortable</option>
                  <option value="wide">Wide</option>
                </select>
              </label>
              <label class="field"><span>UI Density</span>
                <select name="density">
                  <option value="comfortable">Comfortable</option>
                  <option value="compact">Compact</option>
                </select>
              </label>
              <label class="field"><span>Application Mode</span>
                <select name="operatorMode">
                  <option value="student">Student</option>
                  <option value="admin">Admin</option>
                </select>
              </label>
            </div>
            <div class="settings-switches">
              <label class="toggle"><input type="checkbox" name="highContrast" /><span>High Contrast</span></label>
              <label class="toggle"><input type="checkbox" name="reducedMotion" /><span>Reduced Motion</span></label>
            </div>
          </section>

          <section class="settings-group">
            <h3>Translation & Startup</h3>
            <div class="settings-grid">
              <label class="field"><span>Default Code</span>
                <select name="defaultCode">
                  <option value="UEB">UEB</option>
                  <option value="SEB">SEB (Uganda)</option>
                </select>
              </label>
              <label class="field"><span>Startup Page</span>
                <select name="startupPage">
                  <option value="workspace">Workspace</option>
                  <option value="perkins">Perkins Input</option>
                </select>
              </label>
              <label class="field"><span>Startup Tool</span>
                <select name="startupMode">
                  <option value="text">Text</option>
                  <option value="image">Image</option>
                  <option value="camera">Camera</option>
                  <option value="perkins">Perkins Braille to Text</option>
                </select>
              </label>
            </div>
          </section>

          <section class="settings-group">
            <h3>Speech & Audio</h3>
            <div class="settings-grid">
              <label class="field"><span>Voice</span><select name="ttsVoice"></select></label>
              <label class="field"><span>Rate</span><input name="ttsRate" type="number" step="0.05" min="0.6" max="1.6" /></label>
              <label class="field"><span>Pitch</span><input name="ttsPitch" type="number" step="0.1" min="0.5" max="2" /></label>
              <label class="field"><span>Volume</span><input name="ttsVolume" type="number" step="0.1" min="0" max="1" /></label>
            </div>
            <div class="settings-switches">
              <label class="toggle"><input type="checkbox" name="ttsEnabled" /><span>Text to Speech Enabled</span></label>
              <label class="toggle"><input type="checkbox" name="speechWords" /><span>Speak Words</span></label>
            </div>
          </section>

          <section class="settings-group">
            <h3>Perkins Input</h3>
            <div class="settings-grid">
              <label class="field"><span>Chord Timeout (ms)</span><input name="perkinsChordTimeoutMs" type="number" min="120" max="1200" /></label>
              <label class="field"><span>Hold Sensitivity</span>
                <select name="perkinsHoldSensitivity">
                  <option value="low">Low</option>
                  <option value="normal">Normal</option>
                  <option value="high">High</option>
                </select>
              </label>
            </div>
          </section>

          <section class="settings-group" data-admin-only="true" hidden>
            <h3>Advanced Controls (Admin)</h3>
            <p class="hint">Manual table overrides, key remapping, and advanced speech/audio controls.</p>
            <div class="settings-grid">
              <label class="field"><span>UEB Table</span><input name="uebTable" type="text" placeholder="Auto from API" /></label>
              <label class="field"><span>SEB Table</span><input name="sebTable" type="text" placeholder="Auto from API" /></label>
              <label class="field"><span>Repeat Hotkey</span><input name="repeatHotkey" type="text" /></label>
              <label class="field"><span>Dot 1 Key</span><input name="dot1Key" type="text" maxlength="10" /></label>
              <label class="field"><span>Dot 2 Key</span><input name="dot2Key" type="text" maxlength="10" /></label>
              <label class="field"><span>Dot 3 Key</span><input name="dot3Key" type="text" maxlength="10" /></label>
              <label class="field"><span>Dot 4 Key</span><input name="dot4Key" type="text" maxlength="10" /></label>
              <label class="field"><span>Dot 5 Key</span><input name="dot5Key" type="text" maxlength="10" /></label>
              <label class="field"><span>Dot 6 Key</span><input name="dot6Key" type="text" maxlength="10" /></label>
              <label class="field"><span>Backspace Key</span><input name="backspaceKey" type="text" maxlength="10" /></label>
              <label class="field"><span>Enter Key</span><input name="enterKey" type="text" maxlength="10" /></label>
              <label class="field"><span>Space Key</span><input name="spaceKey" type="text" maxlength="10" /></label>
            </div>
            <div class="settings-switches">
              <label class="toggle"><input type="checkbox" name="speechLetters" /><span>Speak Letters</span></label>
              <label class="toggle"><input type="checkbox" name="speechLines" /><span>Speak Lines</span></label>
              <label class="toggle"><input type="checkbox" name="soundKeyTone" /><span>Key Tone</span></label>
              <label class="toggle"><input type="checkbox" name="soundChordTone" /><span>Chord Tone</span></label>
              <label class="toggle"><input type="checkbox" name="soundSpaceTone" /><span>Space Tone</span></label>
              <label class="toggle"><input type="checkbox" name="soundErrorTone" /><span>Error Tone</span></label>
            </div>
          </section>
        </form>
        <footer class="settings-foot">
          <p class="settings-message" aria-live="polite"></p>
          <div class="settings-actions">
            <button type="button" class="btn secondary settings-import" data-admin-only="true" hidden>Import</button>
            <button type="button" class="btn secondary settings-export" data-admin-only="true" hidden>Export</button>
            <button type="button" class="btn ghost settings-reset" data-admin-only="true" hidden>Reset</button>
            <button type="button" class="btn primary settings-save">Save Changes</button>
          </div>
        </footer>
      </div>
    </aside>
  `);
  const fileInput = createEl("<input class=\"settings-import-input\" type=\"file\" accept=\"application/json,.json\" hidden />");

  document.body.appendChild(overlay);
  document.body.appendChild(modal);
  document.body.appendChild(fileInput);

  const closeButton = modal.querySelector(".settings-close");
  const saveButton = modal.querySelector(".settings-save");
  const resetButton = modal.querySelector(".settings-reset");
  const exportButton = modal.querySelector(".settings-export");
  const importButton = modal.querySelector(".settings-import");
  const form = modal.querySelector(".settings-form");
  const message = modal.querySelector(".settings-message");
  const adminOnlyElements = Array.from(modal.querySelectorAll("[data-admin-only=\"true\"]"));
  const supportsSessionRecovery = Boolean(
    sessionManager &&
    typeof sessionManager.listSessions === "function" &&
    typeof sessionManager.saveCurrent === "function" &&
    typeof sessionManager.restoreSession === "function" &&
    typeof sessionManager.deleteSession === "function"
  );
  const sessionSection = supportsSessionRecovery
    ? createEl(`
      <section class="settings-group settings-session-group">
        <h3 class="settings-session-title"></h3>
        <p class="hint settings-session-hint"></p>
        <div class="settings-session-toolbar">
          <button type="button" class="btn secondary settings-session-save">Save Session</button>
          <button type="button" class="btn secondary settings-session-restore">Restore Autosave</button>
          <button type="button" class="btn ghost settings-session-clear">Clear Autosave</button>
        </div>
        <p class="settings-session-status" aria-live="polite"></p>
        <ul class="settings-session-list" aria-live="polite"></ul>
      </section>
    `)
    : null;
  let sessionTitleEl = null;
  let sessionHintEl = null;
  let sessionSaveButton = null;
  let sessionRestoreButton = null;
  let sessionClearButton = null;
  let sessionStatus = null;
  let sessionList = null;

  let isOpen = false;
  let lastFocus = null;
  let sessionBusy = false;
  let sessionHasAutosave = false;

  if (sessionSection) {
    const sessionTitle = typeof sessionManager.title === "string" && sessionManager.title.trim()
      ? sessionManager.title.trim()
      : "Session Recovery";
    const sessionHint = typeof sessionManager.description === "string" && sessionManager.description.trim()
      ? sessionManager.description.trim()
      : "Recover work after restart or power outage.";
    sessionTitleEl = sessionSection.querySelector(".settings-session-title");
    sessionHintEl = sessionSection.querySelector(".settings-session-hint");
    sessionSaveButton = sessionSection.querySelector(".settings-session-save");
    sessionRestoreButton = sessionSection.querySelector(".settings-session-restore");
    sessionClearButton = sessionSection.querySelector(".settings-session-clear");
    sessionStatus = sessionSection.querySelector(".settings-session-status");
    sessionList = sessionSection.querySelector(".settings-session-list");
    sessionTitleEl.textContent = sessionTitle;
    sessionHintEl.textContent = sessionHint;

    const adminSection = form.querySelector("[data-admin-only=\"true\"]");
    if (adminSection) {
      form.insertBefore(sessionSection, adminSection);
    } else {
      form.appendChild(sessionSection);
    }
  }

  const applyOperatorModeVisibility = (modeInput) => {
    const isAdmin = modeInput === "admin";
    adminOnlyElements.forEach((element) => {
      element.hidden = !isAdmin;
      if (element.classList.contains("settings-import") || element.classList.contains("settings-export") || element.classList.contains("settings-reset")) {
        element.disabled = !isAdmin;
      }
    });
  };

  const setSessionMessage = (text, isError = false) => {
    if (!sessionStatus) return;
    sessionStatus.textContent = text || "";
    sessionStatus.classList.toggle("error", isError);
  };

  const applySessionToolbarState = () => {
    if (!sessionSection) return;
    sessionSection.classList.toggle("is-busy", sessionBusy);
    if (sessionSaveButton) {
      sessionSaveButton.disabled = sessionBusy;
    }
    if (sessionRestoreButton) {
      sessionRestoreButton.disabled = sessionBusy || !sessionHasAutosave;
    }
    if (sessionClearButton) {
      const canClearAutosave = typeof sessionManager.clearAutosave === "function";
      sessionClearButton.disabled = sessionBusy || !sessionHasAutosave || !canClearAutosave;
    }
    Array.from(sessionList.querySelectorAll("button")).forEach((button) => {
      button.disabled = sessionBusy;
    });
  };

  const normalizeSessionPayload = (payload) => {
    const incoming = payload && typeof payload === "object" ? payload : {};
    const autosave = incoming.autosave && typeof incoming.autosave === "object"
      ? incoming.autosave
      : null;
    const saved = Array.isArray(incoming.saved)
      ? incoming.saved.filter((item) => item && typeof item === "object")
      : [];
    return { autosave, saved };
  };

  const createSessionListItem = (entry, { autosave = false } = {}) => {
    const item = document.createElement("li");
    item.className = "settings-session-item";

    const details = document.createElement("div");
    const title = document.createElement("p");
    title.className = "settings-session-item-title";
    title.textContent = autosave ? "Autosave Draft" : (entry.label || "Saved Session");
    details.appendChild(title);

    const meta = document.createElement("p");
    meta.className = "settings-session-item-meta";
    const metaParts = [formatSessionTime(entry.savedAt)];
    if (typeof entry.meta === "string" && entry.meta.trim()) {
      metaParts.push(entry.meta.trim());
    }
    meta.textContent = metaParts.join(" | ");
    details.appendChild(meta);

    const actions = document.createElement("div");
    actions.className = "settings-session-item-actions";

    const restoreButton = document.createElement("button");
    restoreButton.type = "button";
    restoreButton.className = "btn secondary";
    restoreButton.textContent = "Restore";
    restoreButton.addEventListener("click", () => {
      if (autosave && typeof sessionManager.restoreAutosave === "function") {
        runSessionAction(
          () => sessionManager.restoreAutosave(),
          "Autosave restored.",
          "No autosave available."
        );
        return;
      }
      runSessionAction(
        () => sessionManager.restoreSession(entry.id),
        "Session restored.",
        "Session restore failed."
      );
    });
    actions.appendChild(restoreButton);

    const deleteButton = document.createElement("button");
    deleteButton.type = "button";
    deleteButton.className = "btn ghost";
    deleteButton.textContent = autosave ? "Clear" : "Delete";
    deleteButton.addEventListener("click", () => {
      if (autosave) {
        if (typeof sessionManager.clearAutosave !== "function") {
          setSessionMessage("Autosave clear is unavailable.", true);
          return;
        }
        runSessionAction(
          () => sessionManager.clearAutosave(),
          "Autosave cleared.",
          "Autosave clear failed."
        );
        return;
      }
      runSessionAction(
        () => sessionManager.deleteSession(entry.id),
        "Saved session deleted.",
        "Session delete failed."
      );
    });
    actions.appendChild(deleteButton);

    item.appendChild(details);
    item.appendChild(actions);
    return item;
  };

  const renderSessionList = (payload) => {
    if (!sessionList) return;
    sessionList.innerHTML = "";
    const { autosave, saved } = normalizeSessionPayload(payload);
    sessionHasAutosave = Boolean(autosave);

    if (autosave) {
      sessionList.appendChild(createSessionListItem(autosave, { autosave: true }));
    }
    saved.forEach((entry) => {
      sessionList.appendChild(createSessionListItem(entry));
    });

    if (!autosave && saved.length === 0) {
      const empty = document.createElement("li");
      empty.className = "settings-session-empty";
      empty.textContent = "No saved sessions yet.";
      sessionList.appendChild(empty);
    }

    applySessionToolbarState();
  };

  const refreshSessionList = async ({ setDefaultMessage = false } = {}) => {
    if (!supportsSessionRecovery) return;
    try {
      const payload = await Promise.resolve(sessionManager.listSessions());
      renderSessionList(payload);
      if (setDefaultMessage) {
        setSessionMessage(
          sessionHasAutosave
            ? "Autosave available. Restore it to continue."
            : "Use Save Session to keep recovery points."
        );
      }
    } catch (_) {
      renderSessionList({ autosave: null, saved: [] });
      setSessionMessage("Session list unavailable.", true);
    }
  };

  const runSessionAction = async (action, successFallback, errorFallback) => {
    if (!supportsSessionRecovery || sessionBusy) return;
    sessionBusy = true;
    applySessionToolbarState();
    try {
      const result = await Promise.resolve(action());
      const ok = result?.ok !== false;
      const messageText = typeof result?.message === "string" && result.message.trim()
        ? result.message.trim()
        : (ok ? successFallback : errorFallback);
      setSessionMessage(messageText, !ok);
    } catch (error) {
      const messageText = error instanceof Error && error.message
        ? error.message
        : errorFallback;
      setSessionMessage(messageText, true);
    } finally {
      await refreshSessionList();
      sessionBusy = false;
      applySessionToolbarState();
    }
  };

  const close = () => {
    isOpen = false;
    modal.classList.add("hidden");
    overlay.classList.add("hidden");
    modal.setAttribute("aria-hidden", "true");
    overlay.setAttribute("aria-hidden", "true");
    document.body.classList.remove("modal-open");
    if (openButton) {
      openButton.setAttribute("aria-expanded", "false");
    }
    const target = lastFocus;
    lastFocus = null;
    if (target && typeof target.focus === "function") {
      target.focus();
    }
  };

  const open = () => {
    const current = getSettings();
    fillForm(form, current);
    const ttsDisabled = !form.ttsEnabled.checked;
    form.ttsVoice.disabled = ttsDisabled;
    form.ttsRate.disabled = ttsDisabled;
    form.ttsPitch.disabled = ttsDisabled;
    form.ttsVolume.disabled = ttsDisabled;
    applyOperatorModeVisibility(form.operatorMode.value);
    applyDisplaySettings(current);
    showMessage(message, "");
    if (supportsSessionRecovery) {
      sessionBusy = false;
      applySessionToolbarState();
      void refreshSessionList({ setDefaultMessage: true });
    }
    isOpen = true;
    lastFocus = document.activeElement;
    modal.classList.remove("hidden");
    overlay.classList.remove("hidden");
    modal.setAttribute("aria-hidden", "false");
    overlay.setAttribute("aria-hidden", "false");
    document.body.classList.add("modal-open");
    if (openButton) {
      openButton.setAttribute("aria-expanded", "true");
    }
    requestAnimationFrame(() => closeButton.focus());
  };

  const save = () => {
    const next = readForm(form);
    const saved = updateSettings(next);
    applyDisplaySettings(saved);
    if (typeof onSettingsSaved === "function") {
      onSettingsSaved(saved);
    }
    showMessage(message, "Settings saved.");
  };

  const reset = () => {
    const defaults = getDefaultSettings();
    fillForm(form, defaults);
    applyOperatorModeVisibility(form.operatorMode.value);
    const saved = resetSettings();
    applyDisplaySettings(saved);
    if (typeof onSettingsSaved === "function") {
      onSettingsSaved(saved);
    }
    showMessage(message, "Settings reset to defaults.");
  };

  const exportToFile = () => {
    const json = exportSettingsJson();
    const blob = new Blob([json], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = "brailleai-settings.json";
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
    showMessage(message, "Settings exported.");
  };

  const importFromFile = async (file) => {
    if (!file) return;
    try {
      const text = await file.text();
      const saved = importSettingsJson(text);
      fillForm(form, saved);
      applyOperatorModeVisibility(form.operatorMode.value);
      applyDisplaySettings(saved);
      if (typeof onSettingsSaved === "function") {
        onSettingsSaved(saved);
      }
      showMessage(message, "Settings imported.");
    } catch (_) {
      showMessage(message, "Import failed. Use a valid JSON settings file.", true);
    }
  };

  if (openButton) {
    openButton.addEventListener("click", () => {
      if (isOpen) {
        close();
      } else {
        open();
      }
    });
  }

  closeButton.addEventListener("click", close);
  overlay.addEventListener("click", close);
  saveButton.addEventListener("click", save);
  resetButton.addEventListener("click", reset);
  exportButton.addEventListener("click", exportToFile);
  importButton.addEventListener("click", () => fileInput.click());
  fileInput.addEventListener("change", (event) => importFromFile(event.target.files?.[0]));
  if (supportsSessionRecovery) {
    sessionSaveButton.addEventListener("click", () => {
      runSessionAction(
        () => sessionManager.saveCurrent(),
        "Session saved.",
        "Session save failed."
      );
    });
    sessionRestoreButton.addEventListener("click", () => {
      if (typeof sessionManager.restoreAutosave !== "function") {
        setSessionMessage("Autosave restore is unavailable.", true);
        return;
      }
      runSessionAction(
        () => sessionManager.restoreAutosave(),
        "Autosave restored.",
        "No autosave available."
      );
    });
    sessionClearButton.addEventListener("click", () => {
      if (typeof sessionManager.clearAutosave !== "function") {
        setSessionMessage("Autosave clear is unavailable.", true);
        return;
      }
      runSessionAction(
        () => sessionManager.clearAutosave(),
        "Autosave cleared.",
        "Autosave clear failed."
      );
    });
  }

  form.ttsEnabled.addEventListener("change", () => {
    const disabled = !form.ttsEnabled.checked;
    form.ttsVoice.disabled = disabled;
    form.ttsRate.disabled = disabled;
    form.ttsPitch.disabled = disabled;
    form.ttsVolume.disabled = disabled;
  });

  form.operatorMode.addEventListener("change", () => {
    applyOperatorModeVisibility(form.operatorMode.value);
  });

  if ("speechSynthesis" in window) {
    window.speechSynthesis.onvoiceschanged = () => {
      const current = getSettings();
      populateVoices(form.ttsVoice, current.ttsVoice);
    };
  }

  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && isOpen) {
      close();
    }
  });

  return {
    open,
    close
  };
};

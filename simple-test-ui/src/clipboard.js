const fallbackCopyText = async (text) => {
  if (typeof document === "undefined") {
    throw new Error("Clipboard fallback is unavailable in this environment");
  }

  const input = document.createElement("textarea");
  input.value = text;
  input.setAttribute("readonly", "");
  input.style.position = "fixed";
  input.style.opacity = "0";
  input.style.pointerEvents = "none";
  document.body.appendChild(input);
  input.focus();
  input.select();
  const ok = document.execCommand("copy");
  input.remove();
  if (!ok) {
    throw new Error("Clipboard copy failed");
  }
};

export const copyTextToClipboard = async (text, { fallback = fallbackCopyText } = {}) => {
  if (!text) {
    throw new Error("Nothing to copy");
  }

  const clipboard = globalThis.navigator?.clipboard;
  if (clipboard?.writeText) {
    try {
      await clipboard.writeText(text);
      return;
    } catch (_) {
      // fall through to fallback
    }
  }

  await fallback(text);
};

export const DEFAULT_TABLES = [
  "en-us-g1.ctb",
  "en-us-g2.ctb",
  "en-g3.ctb",
  "no-no-g3.ctb"
];

export const BRAILLE_ONLY_REGEX = /^[\u2800-\u28FF\s]+$/;
export const BRAILLE_HAS_CHAR_REGEX = /[\u2800-\u28FF]/;
const BRAILLE_CHAR_REGEX = /[\u2800-\u28FF]/;
const NON_BRAILLE_NON_SPACE_REGEX = /[^\u2800-\u28FF\s]/;

export const classifyInputType = (input) => {
  const value = input || "";
  let sawBraille = false;
  let sawNonBraille = false;

  for (const ch of value) {
    if (/\s/.test(ch)) continue;
    if (BRAILLE_CHAR_REGEX.test(ch)) {
      sawBraille = true;
    } else {
      sawNonBraille = true;
    }
    if (sawBraille && sawNonBraille) {
      return "mixed";
    }
  }

  if (!sawBraille && !sawNonBraille) return "empty";
  return sawBraille ? "braille" : "text";
};

export const detectTextInputDirection = (input) => {
  const value = input || "";
  const isBrailleOnly = BRAILLE_ONLY_REGEX.test(value) && BRAILLE_HAS_CHAR_REGEX.test(value);
  if (isBrailleOnly) {
    return {
      direction: "BRAILLE_TO_TEXT",
      detectedInputType: "braille"
    };
  }
  return {
    direction: "TEXT_TO_BRAILLE",
    detectedInputType: "text"
  };
};

export const getDirectionLabel = (direction) =>
  direction === "BRAILLE_TO_TEXT" ? "Braille to Text" : "Text to Braille";

export const validateStrictInput = (input, direction) => {
  const inputType = classifyInputType(input);

  if (inputType === "empty") {
    return { ok: false, message: "Input is required.", inputType };
  }
  if (inputType === "mixed") {
    return {
      ok: false,
      message: "Input cannot mix Braille and regular text. Use one format at a time.",
      inputType
    };
  }

  if (direction === "BRAILLE_TO_TEXT" && inputType !== "braille") {
    return {
      ok: false,
      message: "Braille to Text mode requires Braille Unicode input only.",
      inputType
    };
  }

  if (direction === "TEXT_TO_BRAILLE" && inputType !== "text") {
    return {
      ok: false,
      message: "Text to Braille mode requires regular text input only.",
      inputType
    };
  }

  return { ok: true, inputType };
};

export const resolveDownloadFormats = (direction, source = "text") => {
  if (source === "image") {
    return ["PDF", "DOCX"];
  }
  return direction === "TEXT_TO_BRAILLE" ? ["BRF"] : ["PDF", "DOCX"];
};

const extractUnsupportedTextSymbols = (value) => {
  const symbols = [];
  for (const ch of value) {
    if (ch === " " || ch === "\n" || ch === "\t" || ch === "\r") continue;
    if (BRAILLE_CHAR_REGEX.test(ch)) continue;
    if (/[\p{L}\p{N}\p{P}\p{Zs}]/u.test(ch)) continue;
    if (!symbols.includes(ch)) {
      symbols.push(ch);
    }
    if (symbols.length >= 5) break;
  }
  return symbols;
};

export const buildTranslationWarnings = (input, direction) => {
  const value = input || "";
  if (!value.trim()) {
    return [];
  }

  const warnings = [];
  const hasBraille = BRAILLE_CHAR_REGEX.test(value);
  const hasNonBraille = NON_BRAILLE_NON_SPACE_REGEX.test(value);

  if (hasBraille && hasNonBraille) {
    warnings.push("Mixed Braille and non-Braille characters detected.");
  }

  if (direction === "BRAILLE_TO_TEXT" && hasNonBraille) {
    warnings.push("Some non-Braille characters may be ignored in Braille to Text mode.");
  }

  if (direction === "TEXT_TO_BRAILLE") {
    const unsupported = extractUnsupportedTextSymbols(value);
    if (unsupported.length > 0) {
      warnings.push(`Some symbols may not translate reliably: ${unsupported.join(" ")}`);
    }
  }

  return warnings;
};

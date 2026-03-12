import test from "node:test";
import assert from "node:assert/strict";

import {
  detectTextInputDirection,
  buildTranslationWarnings,
  resolveDownloadFormats,
  validateStrictInput
} from "../src/translation-analysis.js";

test("detects Braille-only input as Braille to Text", () => {
  const detected = detectTextInputDirection("⠞⠑⠎⠞ ⠞⠑⠭⠞");
  assert.equal(detected.direction, "BRAILLE_TO_TEXT");
  assert.equal(detected.detectedInputType, "braille");
});

test("detects plain text as Text to Braille", () => {
  const detected = detectTextInputDirection("Hello world.");
  assert.equal(detected.direction, "TEXT_TO_BRAILLE");
  assert.equal(detected.detectedInputType, "text");
});

test("builds warning for mixed Braille and text input", () => {
  const warnings = buildTranslationWarnings("Hello ⠞⠑⠎⠞", "TEXT_TO_BRAILLE");
  assert.ok(warnings.some((w) => w.includes("Mixed Braille")));
});

test("builds warning for unsupported symbol in text", () => {
  const warnings = buildTranslationWarnings("Exam ⚗ output", "TEXT_TO_BRAILLE");
  assert.ok(warnings.some((w) => w.includes("may not translate reliably")));
});

test("returns expected download formats by direction", () => {
  assert.deepEqual(resolveDownloadFormats("TEXT_TO_BRAILLE"), ["BRF"]);
  assert.deepEqual(resolveDownloadFormats("BRAILLE_TO_TEXT"), ["PDF", "DOCX"]);
  assert.deepEqual(resolveDownloadFormats("TEXT_TO_BRAILLE", "image"), ["PDF", "DOCX"]);
});

test("rejects mixed input in strict validation", () => {
  const result = validateStrictInput("hello ⠞", "TEXT_TO_BRAILLE");
  assert.equal(result.ok, false);
  assert.match(result.message, /cannot mix Braille and regular text/i);
});

test("rejects non-braille input when direction is Braille to Text", () => {
  const result = validateStrictInput("hello", "BRAILLE_TO_TEXT");
  assert.equal(result.ok, false);
  assert.match(result.message, /requires Braille Unicode input/i);
});

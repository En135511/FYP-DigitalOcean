import test from "node:test";
import assert from "node:assert/strict";

import { copyTextToClipboard } from "../src/clipboard.js";

test("uses navigator clipboard API when available", async () => {
  let copied = "";
  const originalNavigator = globalThis.navigator;

  Object.defineProperty(globalThis, "navigator", {
    configurable: true,
    value: {
      clipboard: {
        writeText: async (text) => {
          copied = text;
        }
      }
    }
  });

  try {
    await copyTextToClipboard("hello");
    assert.equal(copied, "hello");
  } finally {
    Object.defineProperty(globalThis, "navigator", {
      configurable: true,
      value: originalNavigator
    });
  }
});

test("falls back when clipboard API fails", async () => {
  const originalNavigator = globalThis.navigator;
  let fallbackCopied = "";

  Object.defineProperty(globalThis, "navigator", {
    configurable: true,
    value: {
      clipboard: {
        writeText: async () => {
          throw new Error("blocked");
        }
      }
    }
  });

  try {
    await copyTextToClipboard("fallback", {
      fallback: async (text) => {
        fallbackCopied = text;
      }
    });
    assert.equal(fallbackCopied, "fallback");
  } finally {
    Object.defineProperty(globalThis, "navigator", {
      configurable: true,
      value: originalNavigator
    });
  }
});

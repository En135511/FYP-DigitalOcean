import test from "node:test";
import assert from "node:assert/strict";

import { resolveScrollTop } from "../src/chat-scroll.js";

test("keeps scroll position when render should not force bottom", () => {
  const next = resolveScrollTop({
    previousScrollTop: 420,
    scrollHeight: 1000,
    scrollToBottom: false
  });
  assert.equal(next, 420);
});

test("moves to latest when render requests bottom scroll", () => {
  const next = resolveScrollTop({
    previousScrollTop: 420,
    scrollHeight: 1000,
    scrollToBottom: true
  });
  assert.equal(next, 1000);
});

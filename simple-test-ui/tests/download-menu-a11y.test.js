import test from "node:test";
import assert from "node:assert/strict";

import { isMenuOpenKey, getNextMenuIndex } from "../src/download-menu-a11y.js";

test("menu launcher open keys are recognized", () => {
  assert.equal(isMenuOpenKey("Enter"), true);
  assert.equal(isMenuOpenKey(" "), true);
  assert.equal(isMenuOpenKey("ArrowDown"), true);
  assert.equal(isMenuOpenKey("ArrowUp"), true);
  assert.equal(isMenuOpenKey("Escape"), false);
});

test("arrow navigation wraps in download menu", () => {
  assert.equal(getNextMenuIndex(0, "ArrowUp", 3), 2);
  assert.equal(getNextMenuIndex(2, "ArrowDown", 3), 0);
});

test("home and end keys jump to boundaries", () => {
  assert.equal(getNextMenuIndex(1, "Home", 4), 0);
  assert.equal(getNextMenuIndex(1, "End", 4), 3);
});

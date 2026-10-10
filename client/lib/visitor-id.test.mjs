import assert from "node:assert/strict";
import { test } from "node:test";

import {
  VISITOR_STORAGE_KEY,
  isValidVisitorId,
  isVisitorIdRejection,
  readVisitorId,
  resetVisitorId,
} from "./visitor-id.ts";

function memoryStore(initial) {
  const data = new Map(initial === undefined ? [] : [[VISITOR_STORAGE_KEY, initial]]);
  return {
    getItem: (key) => (data.has(key) ? data.get(key) : null),
    setItem: (key, value) => data.set(key, String(value)),
    value: () => data.get(VISITOR_STORAGE_KEY),
  };
}

test("makes and keeps an id when none is stored", () => {
  const store = memoryStore();
  const id = readVisitorId(store);
  assert.ok(isValidVisitorId(id));
  assert.equal(store.value(), id);
  assert.equal(readVisitorId(store), id);
});

test("keeps a valid stored id", () => {
  const id = "123e4567-e89b-42d3-a456-426614174000";
  assert.equal(readVisitorId(memoryStore(id)), id);
});

for (const bad of ["not-a-uuid", "", "null", "undefined", "1-2-3-4-5", "123e4567e89b42d3a456426614174000"]) {
  test(`replaces a stored id that is not a UUID: ${JSON.stringify(bad)}`, () => {
    const store = memoryStore(bad);
    const id = readVisitorId(store);
    assert.notEqual(id, bad);
    assert.ok(isValidVisitorId(id));
    assert.equal(store.value(), id);
  });
}

test("reset always stores a new valid id", () => {
  const store = memoryStore("123e4567-e89b-42d3-a456-426614174000");
  const id = resetVisitorId(store);
  assert.ok(isValidVisitorId(id));
  assert.notEqual(id, "123e4567-e89b-42d3-a456-426614174000");
  assert.equal(store.value(), id);
});

test("recognises only the server's visitor id rejection", () => {
  assert.ok(isVisitorIdRejection(400, "Missing or invalid visitor id. Reload the page and try again."));
  assert.ok(!isVisitorIdRejection(400, "Type a question before sending."));
  assert.ok(!isVisitorIdRejection(404, "Missing or invalid visitor id."));
  assert.ok(!isVisitorIdRejection(400, undefined));
});

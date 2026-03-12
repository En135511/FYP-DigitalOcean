const parseJsonSafe = (text) => {
  try {
    return JSON.parse(text);
  } catch (_) {
    return null;
  }
};

const requestJson = async (url, { method = "GET", payload = null } = {}) => {
  const options = { method };
  if (payload !== null) {
    options.headers = { "Content-Type": "application/json" };
    options.body = JSON.stringify(payload);
  }

  const response = await fetch(url, options);
  const text = await response.text();
  const data = parseJsonSafe(text);

  return {
    ok: response.ok,
    status: response.status,
    statusText: response.statusText,
    text,
    data
  };
};

export const fetchBrailleTables = (baseUrl) =>
  requestJson(`${baseUrl}/api/braille/tables`);

export const requestBrailleTranslation = (baseUrl, payload) =>
  requestJson(`${baseUrl}/api/braille/translate`, { method: "POST", payload });

export const requestVisionTranslation = async (baseUrl, formData) => {
  const response = await fetch(`${baseUrl}/api/vision/translate`, {
    method: "POST",
    body: formData
  });
  const text = await response.text();
  const data = parseJsonSafe(text);
  return {
    ok: response.ok,
    status: response.status,
    statusText: response.statusText,
    text,
    data
  };
};

export const requestHealthCheck = async (baseUrl) => {
  const response = await fetch(`${baseUrl}/api/braille/health`);
  const text = await response.text();
  return {
    ok: response.ok,
    status: response.status,
    statusText: response.statusText,
    text
  };
};

export const requestBrailleDownload = (baseUrl, payload) =>
  fetch(`${baseUrl}/api/braille/download`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload)
  });

export const requestVisionDownload = (baseUrl, formData) =>
  fetch(`${baseUrl}/api/vision/translate/download`, {
    method: "POST",
    body: formData
  });

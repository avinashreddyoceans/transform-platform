// ── API base client ───────────────────────────────────────────────────────────
//
// Thin wrapper over fetch with:
//   - JSON request/response by default
//   - Centralised error handling (throws on non-2xx)
//   - Base URL from Vite env (VITE_API_BASE defaults to '' for same-origin)
//
// The Vite dev server proxies /api → http://localhost:8080 (see vite.config.js),
// so all requests in dev hit the Spring Boot backend at :8080.
// In production (embedded in the jar), all requests go same-origin to :8080.

const BASE = import.meta.env.VITE_API_BASE ?? ''

export class ApiError extends Error {
  constructor(message, status, body) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.body = body
  }
}

async function request(path, options = {}) {
  const { body, ...rest } = options
  const res = await fetch(`${BASE}${path}`, {
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
      ...options.headers,
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
    ...rest,
  })

  if (!res.ok) {
    let errBody = {}
    try { errBody = await res.json() } catch (_) { /* ignore */ }
    throw new ApiError(
      errBody.error ?? `HTTP ${res.status}`,
      res.status,
      errBody,
    )
  }

  // 204 No Content
  if (res.status === 204) return null
  return res.json()
}

// Multipart form-data upload — does NOT set Content-Type so the browser adds
// the correct boundary automatically.
async function uploadFormData(path, formData) {
  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { Accept: 'application/json' },
    body: formData,
  })

  if (!res.ok) {
    let errBody = {}
    try { errBody = await res.json() } catch (_) { /* ignore */ }
    throw new ApiError(
      errBody.error ?? `HTTP ${res.status}`,
      res.status,
      errBody,
    )
  }

  if (res.status === 204) return null
  return res.json()
}

export const api = {
  get:        (path, opts = {}) => request(path, { method: 'GET',    ...opts }),
  post:       (path, body, opts = {}) => request(path, { method: 'POST', body, ...opts }),
  put:        (path, body, opts = {}) => request(path, { method: 'PUT',  body, ...opts }),
  delete:     (path, opts = {}) => request(path, { method: 'DELETE', ...opts }),
  upload:     (path, formData) => uploadFormData(path, formData),
}

// ── Profiles API ──────────────────────────────────────────────────────────────
//
// All functions return Promises. Errors are ApiError instances (see client.js).
//
// Backend routes:
//   GET    /api/profiles             → ProfileSummaryResponse[]
//   GET    /api/profiles/{id}        → ProfileResponse
//   POST   /api/profiles             → ProfileResponse (201)
//   PUT    /api/profiles/{id}        → ProfileResponse
//   DELETE /api/profiles/{id}        → null (204)
//   POST   /api/profiles/{id}/enable  → ProfileResponse
//   POST   /api/profiles/{id}/disable → ProfileResponse
//   POST   /api/profiles/{id}/trigger → { profileId, windowId, status, triggeredAt }

import { api } from './client'

/** List all profiles, optionally filtered. */
export const listProfiles = (params = {}) => {
  const query = new URLSearchParams()
  if (params.clientId) query.set('clientId', params.clientId)
  if (params.status)   query.set('status',   params.status)
  const qs = query.toString()
  return api.get(`/api/profiles${qs ? `?${qs}` : ''}`)
}

/** Fetch a single profile (full detail). */
export const getProfile = (id) =>
  api.get(`/api/profiles/${id}`)

/** Create a new profile. */
export const createProfile = (data) =>
  api.post('/api/profiles', data)

/** Replace a profile's configuration. */
export const updateProfile = (id, data) =>
  api.put(`/api/profiles/${id}`, data)

/** Soft-delete a profile. */
export const deleteProfile = (id) =>
  api.delete(`/api/profiles/${id}`)

/** Enable a profile (DRAFT|DISABLED → ENABLED). */
export const enableProfile = (id) =>
  api.post(`/api/profiles/${id}/enable`)

/** Disable a profile (ENABLED → DISABLED). */
export const disableProfile = (id) =>
  api.post(`/api/profiles/${id}/disable`)

/** Manually trigger a window open for a profile. */
export const triggerProfile = (id) =>
  api.post(`/api/profiles/${id}/trigger`)

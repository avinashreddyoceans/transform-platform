import { api } from './client'

export const listWindows = (params = {}) => {
  const query = new URLSearchParams()
  if (params.status)    query.set('status', params.status)
  if (params.profileId) query.set('profileId', params.profileId)
  const qs = query.toString()
  return api.get(`/api/windows${qs ? `?${qs}` : ''}`)
}

export const getWindow         = (id)        => api.get(`/api/windows/${id}`)
export const getWindowData     = (id, type)  => api.get(`/api/windows/${id}/data${type ? `?recordType=${type}` : ''}`)
export const getProfileWindows = (profileId) => api.get(`/api/profiles/${profileId}/windows`)
export const getWindowFiles    = (windowId)  => api.get(`/api/windows/${windowId}/files`)

/**
 * Manually transition a PENDING window to OPEN.
 * Calls POST /api/windows/{id}/open
 */
export const openWindow = (id) => api.post(`/api/windows/${id}/open`, undefined)

/**
 * Submit a file to an OPEN window.
 * Calls POST /api/windows/{windowId}/submit-file (multipart/form-data)
 *
 * @param {string} windowId  The window UUID
 * @param {File}   file      File object from an <input type="file">
 * @param {string} [integrationId]  Optional integration ID to tag on the file handle
 */
export const submitFileToWindow = (windowId, file, integrationId) => {
  const formData = new FormData()
  formData.append('file', file)
  if (integrationId) formData.append('integrationId', integrationId)
  return api.upload(`/api/windows/${windowId}/submit-file`, formData)
}

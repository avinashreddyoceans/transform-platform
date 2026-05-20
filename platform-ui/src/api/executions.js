import { api } from './client'

export const listExecutions = (params = {}) => {
  const query = new URLSearchParams()
  if (params.status) query.set('status', params.status)
  if (params.limit)  query.set('limit', params.limit)
  const qs = query.toString()
  return api.get(`/api/executions${qs ? `?${qs}` : ''}`)
}

export const getExecution         = (id)        => api.get(`/api/executions/${id}`)
export const getWindowExecutions  = (windowId)  => api.get(`/api/windows/${windowId}/executions`)
export const getProfileExecutions = (profileId) => api.get(`/api/profiles/${profileId}/executions`)

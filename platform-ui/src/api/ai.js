import { api } from './client'

// ── AI Chatbot API ─────────────────────────────────────────────────────────────
//
// Session-based client for the platform-chatbot LangGraph service (port 8000).
// In dev the Vite proxy forwards /chatbot → http://localhost:8000.
// In production the chatbot service is served separately at port 8000.
//
// Session lifecycle:
//   createSession()           → POST /chatbot/sessions   → { session_id, created_at }
//   chat(sessionId, message)  → POST /chatbot/sessions/{id}/chat
//                             → { response, active_agent, wizard_step, wizard_total,
//                                 tools_used, updated_at }
//   clearSession(sessionId)   → DELETE /chatbot/sessions/{id}  → null (204)

export const aiApi = {
  /** Create a new chat session. Returns { session_id, created_at }. */
  createSession: () =>
    api.post('/chatbot/sessions', undefined),

  /**
   * Send a message in an existing session.
   * @param {string} sessionId - Session ID from createSession().
   * @param {string} message   - User message text.
   * @returns {Promise<ChatResponse>}
   */
  chat: (sessionId, message) =>
    api.post(`/chatbot/sessions/${sessionId}/chat`, { message }),

  /** Delete a session (clears server-side memory). */
  clearSession: (sessionId) =>
    api.delete(`/chatbot/sessions/${sessionId}`),
}

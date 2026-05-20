import { api } from './client'

// ── AI Assistant API ──────────────────────────────────────────────────────────
//
// Wraps POST /api/ai/chat
// Request:  { message: string, history: ConversationTurn[] }
// Response: { response, toolsUsed, iterations, updatedHistory, timestamp, error? }

export const aiApi = {
  /**
   * Send a chat message to the AI assistant.
   * @param {string} message - The user's message.
   * @param {Array}  history - Previous conversation turns (pass updatedHistory from last response).
   * @returns {Promise<AiChatResponse>}
   */
  chat: (message, history = []) =>
    api.post('/api/ai/chat', { message, history }),
}

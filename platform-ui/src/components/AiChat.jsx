import { useState, useRef, useEffect } from 'react'
import {
  Sparkles, X, Send, RotateCcw, ChevronDown,
  Loader2, Zap, AlertCircle,
} from 'lucide-react'
import { aiApi } from '../api/ai'

// ── AiChat ─────────────────────────────────────────────────────────────────────
//
// Floating AI assistant for the Transform Platform.
//
// Architecture:
//   - Floating button (bottom-right) toggles the chat panel
//   - Chat panel slides in as a fixed overlay on the right edge
//   - Conversation history is stored in component state and sent back to the
//     server on each turn (stateless backend, stateful frontend)
//
// Backend: POST /api/ai/chat → { response, toolsUsed, updatedHistory, error }

const STARTER_PROMPTS = [
  'How many profiles are enabled?',
  'Show me recent failed executions',
  'What windows are open right now?',
  'List all available file specs',
  'Create a profile for daily CSV processing',
]

// ── Utility: render assistant text (preserves newlines, code blocks) ─────────

function AssistantText({ text }) {
  // Split on code fences and render inline/block code simply
  const parts = text.split(/(```[\s\S]*?```)/g)
  return (
    <div className="space-y-2 text-sm leading-relaxed">
      {parts.map((part, i) => {
        if (part.startsWith('```')) {
          const inner = part.replace(/^```[^\n]*\n?/, '').replace(/```$/, '')
          return (
            <pre
              key={i}
              className="bg-slate-900 text-green-400 rounded-lg p-3 text-xs overflow-x-auto whitespace-pre-wrap font-mono"
            >
              {inner}
            </pre>
          )
        }
        // Plain text — preserve line breaks
        return (
          <span key={i} className="whitespace-pre-wrap">
            {part}
          </span>
        )
      })}
    </div>
  )
}

// ── Tool use badges ──────────────────────────────────────────────────────────

function ToolBadges({ tools }) {
  if (!tools || tools.length === 0) return null
  return (
    <div className="flex flex-wrap gap-1 mt-1.5">
      {tools.map((t) => (
        <span
          key={t}
          className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[10px] font-mono bg-indigo-50 text-indigo-600 border border-indigo-100"
        >
          <Zap size={8} />
          {t}
        </span>
      ))}
    </div>
  )
}

// ── Message bubble ────────────────────────────────────────────────────────────

function MessageBubble({ msg }) {
  const isUser = msg.role === 'user'

  if (isUser) {
    return (
      <div className="flex justify-end">
        <div className="max-w-[85%] bg-indigo-600 text-white rounded-2xl rounded-tr-sm px-3.5 py-2.5 text-sm leading-relaxed shadow-sm">
          {msg.text}
        </div>
      </div>
    )
  }

  return (
    <div className="flex flex-col items-start gap-0.5">
      <div className="flex items-center gap-1.5 mb-0.5">
        <div className="h-5 w-5 rounded-full bg-indigo-100 flex items-center justify-center flex-shrink-0">
          <Sparkles size={10} className="text-indigo-600" />
        </div>
        <span className="text-[11px] font-medium text-slate-400">AI Assistant</span>
      </div>
      <div className="max-w-[92%] bg-white text-slate-800 rounded-2xl rounded-tl-sm px-3.5 py-2.5 border border-slate-200 shadow-sm">
        {msg.error ? (
          <div className="flex items-start gap-2 text-sm text-red-600">
            <AlertCircle size={14} className="mt-0.5 flex-shrink-0" />
            <span>{msg.text}</span>
          </div>
        ) : (
          <AssistantText text={msg.text} />
        )}
        <ToolBadges tools={msg.toolsUsed} />
      </div>
    </div>
  )
}

// ── Thinking indicator ────────────────────────────────────────────────────────

function ThinkingBubble() {
  return (
    <div className="flex flex-col items-start gap-0.5">
      <div className="flex items-center gap-1.5 mb-0.5">
        <div className="h-5 w-5 rounded-full bg-indigo-100 flex items-center justify-center">
          <Sparkles size={10} className="text-indigo-600" />
        </div>
        <span className="text-[11px] font-medium text-slate-400">AI Assistant</span>
      </div>
      <div className="bg-white rounded-2xl rounded-tl-sm px-3.5 py-3 border border-slate-200 shadow-sm">
        <div className="flex items-center gap-2 text-slate-400">
          <Loader2 size={13} className="animate-spin text-indigo-500" />
          <span className="text-xs">Thinking…</span>
        </div>
      </div>
    </div>
  )
}

// ── Starter prompts ───────────────────────────────────────────────────────────

function StarterPrompts({ onSelect }) {
  return (
    <div className="flex flex-col items-center justify-center h-full px-4 py-8 gap-4">
      <div className="flex flex-col items-center gap-2 text-center">
        <div className="h-12 w-12 rounded-2xl bg-indigo-100 flex items-center justify-center">
          <Sparkles size={22} className="text-indigo-600" />
        </div>
        <p className="text-sm font-semibold text-slate-700">Transform Platform Assistant</p>
        <p className="text-xs text-slate-400 max-w-[220px]">
          Ask me about profiles, windows, executions, or let me create resources for you.
        </p>
      </div>
      <div className="w-full flex flex-col gap-2 mt-2">
        {STARTER_PROMPTS.map((prompt) => (
          <button
            key={prompt}
            onClick={() => onSelect(prompt)}
            className="w-full text-left text-xs px-3 py-2.5 rounded-xl bg-white border border-slate-200 text-slate-600 hover:border-indigo-300 hover:text-indigo-700 hover:bg-indigo-50 transition-all shadow-sm"
          >
            {prompt}
          </button>
        ))}
      </div>
    </div>
  )
}

// ── Main AiChat component ─────────────────────────────────────────────────────

export default function AiChat() {
  const [isOpen, setIsOpen] = useState(false)
  const [messages, setMessages] = useState([]) // { role, text, toolsUsed?, error? }
  const [history, setHistory] = useState([])   // AnthropicMessage[] for the backend
  const [input, setInput] = useState('')
  const [isLoading, setIsLoading] = useState(false)
  const [hasActivity, setHasActivity] = useState(false)
  const bottomRef = useRef(null)
  const inputRef = useRef(null)

  // Auto-scroll to bottom when messages change
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages, isLoading])

  // Focus input when panel opens
  useEffect(() => {
    if (isOpen) {
      setTimeout(() => inputRef.current?.focus(), 150)
    }
  }, [isOpen])

  const sendMessage = async (text) => {
    const userText = text ?? input.trim()
    if (!userText || isLoading) return

    setInput('')
    setMessages((prev) => [...prev, { role: 'user', text: userText }])
    setIsLoading(true)

    try {
      const result = await aiApi.chat(userText, history)

      setHistory(result.updatedHistory ?? [])
      setHasActivity(true)
      setMessages((prev) => [
        ...prev,
        {
          role: 'assistant',
          text: result.response,
          toolsUsed: result.toolsUsed ?? [],
          error: !!result.error,
        },
      ])
    } catch (err) {
      setMessages((prev) => [
        ...prev,
        {
          role: 'assistant',
          text: err.message ?? 'Something went wrong. Please try again.',
          toolsUsed: [],
          error: true,
        },
      ])
    } finally {
      setIsLoading(false)
    }
  }

  const clearConversation = () => {
    setMessages([])
    setHistory([])
    setHasActivity(false)
    setInput('')
  }

  const handleKeyDown = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      sendMessage()
    }
  }

  return (
    <>
      {/* ── Floating toggle button ──────────────────────────────────────────── */}
      <button
        onClick={() => setIsOpen((v) => !v)}
        className={`
          fixed bottom-6 right-6 z-50 flex items-center gap-2
          h-13 px-4 py-3 rounded-2xl shadow-lg transition-all duration-200
          ${isOpen
            ? 'bg-slate-700 hover:bg-slate-800 text-white'
            : 'bg-indigo-600 hover:bg-indigo-700 text-white shadow-indigo-200'}
        `}
        aria-label="Toggle AI Assistant"
      >
        {isOpen ? (
          <>
            <ChevronDown size={16} />
            <span className="text-sm font-medium">Close</span>
          </>
        ) : (
          <>
            <Sparkles size={16} />
            <span className="text-sm font-medium">AI Assistant</span>
            {hasActivity && !isOpen && (
              <span className="h-2 w-2 rounded-full bg-green-400 ml-0.5" />
            )}
          </>
        )}
      </button>

      {/* ── Chat panel ─────────────────────────────────────────────────────── */}
      <div
        className={`
          fixed bottom-0 right-0 z-40 flex flex-col
          w-[390px] h-[calc(100vh-0px)]
          bg-slate-50 border-l border-slate-200 shadow-2xl
          transition-transform duration-300 ease-in-out
          ${isOpen ? 'translate-x-0' : 'translate-x-full'}
        `}
      >
        {/* Header */}
        <div className="flex items-center justify-between px-4 py-3.5 bg-white border-b border-slate-200 flex-shrink-0">
          <div className="flex items-center gap-2.5">
            <div className="h-8 w-8 rounded-lg bg-indigo-600 flex items-center justify-center shadow-sm">
              <Sparkles size={15} className="text-white" />
            </div>
            <div>
              <p className="text-sm font-semibold text-slate-800 leading-none">AI Assistant</p>
              <p className="text-[11px] text-slate-400 mt-0.5 leading-none">Transform Platform</p>
            </div>
          </div>
          <div className="flex items-center gap-1">
            {messages.length > 0 && (
              <button
                onClick={clearConversation}
                className="p-1.5 rounded-lg text-slate-400 hover:text-slate-600 hover:bg-slate-100 transition-colors"
                title="New conversation"
              >
                <RotateCcw size={14} />
              </button>
            )}
            <button
              onClick={() => setIsOpen(false)}
              className="p-1.5 rounded-lg text-slate-400 hover:text-slate-600 hover:bg-slate-100 transition-colors"
              aria-label="Close"
            >
              <X size={15} />
            </button>
          </div>
        </div>

        {/* Messages area */}
        <div className="flex-1 overflow-y-auto">
          {messages.length === 0 ? (
            <StarterPrompts onSelect={(p) => sendMessage(p)} />
          ) : (
            <div className="flex flex-col gap-4 px-4 py-4">
              {messages.map((msg, i) => (
                <MessageBubble key={i} msg={msg} />
              ))}
              {isLoading && <ThinkingBubble />}
              <div ref={bottomRef} />
            </div>
          )}
        </div>

        {/* Input area */}
        <div className="flex-shrink-0 border-t border-slate-200 bg-white px-3 py-3">
          <div className="flex items-end gap-2 bg-slate-50 border border-slate-200 rounded-xl px-3 py-2 focus-within:border-indigo-300 focus-within:ring-2 focus-within:ring-indigo-100 transition-all">
            <textarea
              ref={inputRef}
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={handleKeyDown}
              placeholder="Ask about profiles, windows, executions…"
              rows={1}
              disabled={isLoading}
              className="flex-1 bg-transparent text-sm text-slate-800 placeholder-slate-400 resize-none outline-none leading-relaxed max-h-32 disabled:opacity-50"
              style={{ minHeight: '22px' }}
              onInput={(e) => {
                e.target.style.height = 'auto'
                e.target.style.height = `${Math.min(e.target.scrollHeight, 128)}px`
              }}
            />
            <button
              onClick={() => sendMessage()}
              disabled={!input.trim() || isLoading}
              className="h-7 w-7 flex items-center justify-center rounded-lg bg-indigo-600 text-white disabled:opacity-40 hover:bg-indigo-700 transition-colors flex-shrink-0 mb-0.5"
              aria-label="Send"
            >
              {isLoading ? (
                <Loader2 size={13} className="animate-spin" />
              ) : (
                <Send size={13} />
              )}
            </button>
          </div>
          <p className="text-[10px] text-slate-400 text-center mt-1.5">
            Press Enter to send · Shift+Enter for new line
          </p>
        </div>
      </div>

      {/* Backdrop (mobile / narrow screens) */}
      {isOpen && (
        <div
          className="fixed inset-0 z-30 bg-black/10 lg:hidden"
          onClick={() => setIsOpen(false)}
        />
      )}
    </>
  )
}

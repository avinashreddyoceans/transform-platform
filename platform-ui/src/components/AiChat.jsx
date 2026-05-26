import { useState, useRef, useEffect } from 'react'
import {
  Sparkles, X, Send, RotateCcw, ChevronDown,
  Loader2, Zap, AlertCircle, Bot, BarChart2, Wrench, CheckCircle2,
} from 'lucide-react'
import { aiApi } from '../api/ai'

// ── AiChat ─────────────────────────────────────────────────────────────────────
//
// Floating multi-agent AI assistant for the Transform Platform.
//
// Architecture:
//   - Session is created on first send (POST /chatbot/sessions) and stored in state.
//   - Each turn calls POST /chatbot/sessions/{id}/chat.
//   - Response includes active_agent, wizard_step, wizard_total, tools_used.
//   - An agent badge shows which specialist is active.
//   - A wizard progress bar appears when wizard_step > 0.
//
// Backend: platform-chatbot FastAPI service on :8000 (proxied via /chatbot)

const STARTER_PROMPTS = [
  'Help me set up my first pipeline',
  'How many profiles are enabled?',
  'Show me failed executions this week',
  'What windows are open right now?',
  'Build a CSV file spec for bank transactions',
]

// ── Agent badge config ─────────────────────────────────────────────────────────

const AGENT_CONFIG = {
  onboarding:   { label: 'Onboarding',    color: 'bg-emerald-100 text-emerald-700 border-emerald-200', Icon: Sparkles },
  flow_builder: { label: 'Flow Builder',  color: 'bg-violet-100 text-violet-700 border-violet-200',   Icon: Wrench },
  insights:     { label: 'Insights',      color: 'bg-amber-100 text-amber-700 border-amber-200',       Icon: BarChart2 },
  general:      { label: 'Assistant',     color: 'bg-indigo-100 text-indigo-700 border-indigo-200',    Icon: Bot },
}

function AgentBadge({ agent }) {
  const cfg = AGENT_CONFIG[agent] ?? AGENT_CONFIG.general
  const { label, color, Icon } = cfg
  return (
    <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-semibold border ${color}`}>
      <Icon size={9} />
      {label}
    </span>
  )
}

// ── Wizard progress bar ────────────────────────────────────────────────────────

const WIZARD_STEP_NAMES = ['Use Case', 'File Spec', 'Integration', 'Profile', 'Enable']

function WizardProgress({ step, total }) {
  if (!step || step <= 0) return null
  return (
    <div className="px-4 py-2.5 bg-emerald-50 border-b border-emerald-100 flex-shrink-0">
      <p className="text-[10px] font-medium text-emerald-700 mb-2">Setup wizard</p>
      <div className="flex items-start gap-0">
        {WIZARD_STEP_NAMES.map((name, i) => {
          const idx = i + 1
          const done = idx < step
          const active = idx === step
          const isLast = i === WIZARD_STEP_NAMES.length - 1
          return (
            <div key={name} className="flex items-center flex-1">
              <div className="flex flex-col items-center gap-0.5 flex-shrink-0">
                <div className={`h-5 w-5 rounded-full flex items-center justify-center text-[9px] font-bold transition-all
                  ${done ? 'bg-emerald-500 text-white' : active ? 'bg-emerald-600 text-white ring-2 ring-emerald-200' : 'bg-slate-200 text-slate-400'}`}>
                  {done ? '✓' : idx}
                </div>
                <span className={`text-[9px] font-medium text-center leading-tight w-12 truncate
                  ${active ? 'text-emerald-700' : done ? 'text-emerald-500' : 'text-slate-400'}`}>
                  {name}
                </span>
              </div>
              {!isLast && (
                <div className={`flex-1 h-px mt-[-10px] mx-0.5 ${done ? 'bg-emerald-400' : 'bg-slate-200'}`} />
              )}
            </div>
          )
        })}
      </div>
    </div>
  )
}

// ── Utility: render assistant text ─────────────────────────────────────────────

function AssistantText({ text }) {
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
        return (
          <span key={i} className="whitespace-pre-wrap">
            {part}
          </span>
        )
      })}
    </div>
  )
}

// ── Tool use badges ────────────────────────────────────────────────────────────

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

// ── Structured data cards ──────────────────────────────────────────────────────

const STATUS_BADGE = {
  // Execution statuses
  COMPLETED: 'bg-emerald-100 text-emerald-700',
  FAILED:    'bg-red-100 text-red-700',
  RUNNING:   'bg-blue-100 text-blue-700',
  PENDING:   'bg-slate-100 text-slate-500',
  // Window statuses
  OPEN:      'bg-emerald-100 text-emerald-700',
  CLOSED:    'bg-slate-100 text-slate-500',
  // Profile statuses
  ENABLED:   'bg-emerald-100 text-emerald-700',
  DRAFT:     'bg-amber-100 text-amber-700',
  DISABLED:  'bg-slate-100 text-slate-500',
}

function StatusBadge({ status }) {
  const cls = STATUS_BADGE[status?.toUpperCase()] ?? 'bg-slate-100 text-slate-500'
  return (
    <span className={`inline-block px-1.5 py-0.5 rounded text-[10px] font-semibold ${cls}`}>
      {status ?? '—'}
    </span>
  )
}

function CardTable({ headers, rows }) {
  return (
    <div className="overflow-x-auto rounded-lg border border-slate-200 mt-1">
      <table className="w-full text-[11px]">
        <thead>
          <tr className="bg-slate-50 border-b border-slate-200">
            {headers.map((h) => (
              <th key={h} className="px-2 py-1.5 text-left font-semibold text-slate-500 whitespace-nowrap">{h}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, i) => (
            <tr key={i} className={i % 2 === 0 ? 'bg-white' : 'bg-slate-50/50'}>
              {row.map((cell, j) => (
                <td key={j} className="px-2 py-1.5 text-slate-700 whitespace-nowrap">{cell}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function CardHeader({ title, count }) {
  return (
    <div className="flex items-center justify-between mb-1">
      <span className="text-[11px] font-semibold text-slate-600">{title}</span>
      {count != null && (
        <span className="text-[10px] text-slate-400">{count} total</span>
      )}
    </div>
  )
}

function ProfileListCard({ data }) {
  if (!data.items?.length) return null
  return (
    <div>
      <CardHeader title="Profiles" count={data.total} />
      <CardTable
        headers={['Name', 'Client', 'Status']}
        rows={data.items.map((p) => [
          p.name ?? p.id ?? '—',
          p.clientId ?? '—',
          <StatusBadge key="s" status={p.status} />,
        ])}
      />
    </div>
  )
}

function FileSpecListCard({ data }) {
  if (!data.items?.length) return null
  return (
    <div>
      <CardHeader title="File Specs" count={data.total} />
      <CardTable
        headers={['Name', 'Format', 'Fields']}
        rows={data.items.map((s) => [
          s.name ?? s.id ?? '—',
          <span key="f" className="px-1.5 py-0.5 rounded bg-violet-100 text-violet-700 text-[10px] font-semibold">{s.format ?? '—'}</span>,
          s.fieldCount ?? '—',
        ])}
      />
    </div>
  )
}

function ExecutionListCard({ data }) {
  if (!data.items?.length) return null
  return (
    <div>
      <CardHeader title="Executions" count={data.total} />
      <CardTable
        headers={['Status', 'Profile', 'Records', 'Started']}
        rows={data.items.map((e) => [
          <StatusBadge key="s" status={e.status} />,
          e.profileId ?? '—',
          e.totalRecords ?? '—',
          e.startedAt ? new Date(e.startedAt).toLocaleDateString() : '—',
        ])}
      />
    </div>
  )
}

function WindowListCard({ data }) {
  if (!data.items?.length) return null
  return (
    <div>
      <CardHeader title="Windows" count={data.total} />
      <CardTable
        headers={['Status', 'Profile', 'Opened', 'Closed']}
        rows={data.items.map((w) => [
          <StatusBadge key="s" status={w.status} />,
          w.profileId ?? '—',
          w.openedAt ? new Date(w.openedAt).toLocaleDateString() : '—',
          w.closedAt ? new Date(w.closedAt).toLocaleDateString() : '—',
        ])}
      />
    </div>
  )
}

function MetricsSummaryCard({ data }) {
  const successRate = data.success_rate_pct ?? data.successRatePct
  const rateColor = successRate >= 90
    ? 'text-emerald-700 bg-emerald-50'
    : successRate >= 70
    ? 'text-amber-700 bg-amber-50'
    : 'text-red-700 bg-red-50'

  const kpis = [
    { label: 'Success Rate', value: successRate != null ? `${successRate.toFixed(1)}%` : '—', color: rateColor },
    { label: 'Total Executions', value: data.total_executions ?? data.totalExecutions ?? '—', color: 'text-slate-700 bg-slate-50' },
    { label: 'Avg Duration', value: data.avg_duration_ms != null ? `${(data.avg_duration_ms / 1000).toFixed(1)}s` : (data.avgDurationMs != null ? `${(data.avgDurationMs / 1000).toFixed(1)}s` : '—'), color: 'text-indigo-700 bg-indigo-50' },
    { label: 'Records / Day', value: data.records_per_day ?? data.recordsPerDay ?? '—', color: 'text-slate-700 bg-slate-50' },
  ]

  return (
    <div>
      <CardHeader title="Processing Metrics" />
      <div className="grid grid-cols-2 gap-1.5 mt-1">
        {kpis.map(({ label, value, color }) => (
          <div key={label} className={`rounded-lg p-2 ${color}`}>
            <p className="text-[10px] font-medium opacity-70">{label}</p>
            <p className="text-base font-bold leading-tight mt-0.5">{value}</p>
          </div>
        ))}
      </div>
    </div>
  )
}

function ErrorSummaryCard({ data }) {
  const failedCount = data.failed_executions ?? data.failedExecutions
  const days = data.period_days ?? data.periodDays
  const errors = data.top_errors ?? data.topErrors ?? []

  return (
    <div>
      <CardHeader title="Error Summary" />
      {failedCount != null && (
        <p className="text-[11px] text-red-600 mb-1.5">
          {failedCount} failed execution{failedCount !== 1 ? 's' : ''}{days ? ` in last ${days} days` : ''}
        </p>
      )}
      {errors.slice(0, 5).map((e, i) => {
        const category = e.category ?? e.error_type ?? e.type ?? String(e)
        const count = e.count
        return (
          <div key={i} className="flex items-center justify-between py-1 border-b border-slate-100 last:border-0">
            <span className="text-[11px] text-slate-600 truncate mr-2">{category}</span>
            {count != null && (
              <span className="px-1.5 py-0.5 rounded bg-red-100 text-red-700 text-[10px] font-semibold flex-shrink-0">{count}</span>
            )}
          </div>
        )
      })}
    </div>
  )
}

const RESOURCE_LABELS = { profile: 'Profile', file_spec: 'File Spec', integration: 'Integration' }

function ResourceCreatedCard({ data }) {
  const typeLabel = RESOURCE_LABELS[data.resource_type] ?? data.resource_type
  return (
    <div className="flex items-start gap-2 p-2.5 rounded-lg border border-emerald-200 bg-emerald-50">
      <CheckCircle2 size={16} className="text-emerald-600 flex-shrink-0 mt-0.5" />
      <div>
        <p className="text-[11px] font-semibold text-emerald-800">{typeLabel} created</p>
        {data.name && <p className="text-[11px] text-emerald-700 mt-0.5">{data.name}</p>}
        {data.id && <p className="text-[10px] text-emerald-500 font-mono mt-0.5">ID: {data.id}</p>}
      </div>
    </div>
  )
}

function ResourceUpdatedCard({ data }) {
  const typeLabel = RESOURCE_LABELS[data.resource_type] ?? data.resource_type
  return (
    <div className="flex items-start gap-2 p-2.5 rounded-lg border border-blue-200 bg-blue-50">
      <CheckCircle2 size={16} className="text-blue-600 flex-shrink-0 mt-0.5" />
      <div>
        <p className="text-[11px] font-semibold text-blue-800">{typeLabel} updated</p>
        {data.name && <p className="text-[11px] text-blue-700 mt-0.5">{data.name}</p>}
        {data.id && <p className="text-[10px] text-blue-500 font-mono mt-0.5">ID: {data.id}</p>}
      </div>
    </div>
  )
}

function StructuredDataCard({ data }) {
  if (!data) return null
  switch (data.type) {
    case 'metrics_summary':  return <MetricsSummaryCard data={data} />
    case 'error_summary':    return <ErrorSummaryCard data={data} />
    case 'execution_list':   return <ExecutionListCard data={data} />
    case 'window_list':      return <WindowListCard data={data} />
    case 'profile_list':     return <ProfileListCard data={data} />
    case 'file_spec_list':   return <FileSpecListCard data={data} />
    case 'resource_created': return <ResourceCreatedCard data={data} />
    case 'resource_updated': return <ResourceUpdatedCard data={data} />
    default:                 return null
  }
}

// ── Message bubble ─────────────────────────────────────────────────────────────

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
        {msg.agent && <AgentBadge agent={msg.agent} />}
      </div>
      <div className="max-w-[92%] bg-white text-slate-800 rounded-2xl rounded-tl-sm px-3.5 py-2.5 border border-slate-200 shadow-sm">
        {msg.error ? (
          <div className="flex items-start gap-2 text-sm text-red-600">
            <AlertCircle size={14} className="mt-0.5 flex-shrink-0" />
            <span>{msg.text}</span>
          </div>
        ) : (
          <>
            <AssistantText text={msg.text} />
            {msg.structuredData && (
              <div className="mt-2 border-t border-slate-100 pt-2">
                <StructuredDataCard data={msg.structuredData} />
              </div>
            )}
          </>
        )}
        <ToolBadges tools={msg.toolsUsed} />
      </div>
    </div>
  )
}

// ── Thinking indicator ─────────────────────────────────────────────────────────

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

// ── Starter prompts ────────────────────────────────────────────────────────────

function StarterPrompts({ onSelect }) {
  return (
    <div className="flex flex-col items-center justify-center h-full px-4 py-8 gap-4">
      <div className="flex flex-col items-center gap-2 text-center">
        <div className="h-12 w-12 rounded-2xl bg-indigo-100 flex items-center justify-center">
          <Sparkles size={22} className="text-indigo-600" />
        </div>
        <p className="text-sm font-semibold text-slate-700">Transform Platform Assistant</p>
        <p className="text-xs text-slate-400 max-w-[220px]">
          Ask me about profiles, windows, executions, or let me guide you through setup.
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

// ── Main AiChat component ──────────────────────────────────────────────────────

export default function AiChat() {
  const [isOpen, setIsOpen] = useState(false)
  const [messages, setMessages] = useState([])  // { role, text, agent?, toolsUsed?, structuredData?, error? }
  const [sessionId, setSessionId] = useState(null)
  const [activeAgent, setActiveAgent] = useState('general')
  const [wizardStep, setWizardStep] = useState(0)
  const [wizardTotal, setWizardTotal] = useState(5)
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
      // Create session on first message
      let sid = sessionId
      if (!sid) {
        const session = await aiApi.createSession()
        sid = session.session_id
        setSessionId(sid)
      }

      const result = await aiApi.chat(sid, userText)

      setActiveAgent(result.active_agent ?? 'general')
      setWizardStep(result.wizard_step ?? 0)
      setWizardTotal(result.wizard_total ?? 5)
      setHasActivity(true)

      setMessages((prev) => [
        ...prev,
        {
          role: 'assistant',
          text: result.response,
          agent: result.active_agent,
          toolsUsed: result.tools_used ?? [],
          structuredData: result.structured_data ?? null,
          error: false,
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

  const clearConversation = async () => {
    if (sessionId) {
      try { await aiApi.clearSession(sessionId) } catch (_) { /* ignore */ }
    }
    setMessages([])
    setSessionId(null)
    setActiveAgent('general')
    setWizardStep(0)
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
              <div className="flex items-center gap-2">
                <p className="text-sm font-semibold text-slate-800 leading-none">AI Assistant</p>
                {messages.length > 0 && <AgentBadge agent={activeAgent} />}
              </div>
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

        {/* Wizard progress bar (only shown during onboarding) */}
        <WizardProgress step={wizardStep} total={wizardTotal} />

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

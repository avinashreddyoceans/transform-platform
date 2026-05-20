import { useState, useEffect } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import {
  Layers, ArrowLeft, RefreshCw, AlertCircle, Loader2,
  CheckCircle2, Clock, XCircle, AlertTriangle, ChevronRight,
  GitBranch,
} from 'lucide-react'
import { getExecution } from '../api/executions'
import { ApiError } from '../api/client'

// ─────────────────────────────────────────────────────────────────────────────
// Status helpers (mirrors ExecutionList)
// ─────────────────────────────────────────────────────────────────────────────

const STATUS_STYLES = {
  PENDING:               'bg-indigo-50 text-indigo-700 border border-indigo-200',
  RUNNING:               'bg-teal-50 text-teal-700 border border-teal-200',
  RETRYING:              'bg-amber-50 text-amber-700 border border-amber-200',
  COMPLETED:             'bg-green-50 text-green-700 border border-green-200',
  COMPLETED_WITH_ERRORS: 'bg-amber-50 text-amber-700 border border-amber-200',
  FAILED:                'bg-rose-50 text-rose-700 border border-rose-200',
  CANCELLED:             'bg-slate-100 text-slate-600',
}

const STEP_STATUS_STYLES = {
  PENDING:       'bg-slate-50 text-slate-500 border border-slate-200',
  RUNNING:       'bg-teal-50 text-teal-700 border border-teal-200',
  COMPLETED:     'bg-green-50 text-green-700 border border-green-200',
  FAILED:        'bg-rose-50 text-rose-700 border border-rose-200',
  FAILED_IGNORED:'bg-amber-50 text-amber-700 border border-amber-200',
  RETRYING:      'bg-amber-50 text-amber-700 border border-amber-200',
  SKIPPED:       'bg-slate-50 text-slate-400 border border-slate-100',
}

const STATUS_ICONS = {
  PENDING:               Clock,
  RUNNING:               Loader2,
  RETRYING:              AlertTriangle,
  COMPLETED:             CheckCircle2,
  COMPLETED_WITH_ERRORS: AlertTriangle,
  FAILED:                XCircle,
  CANCELLED:             XCircle,
}

function formatDt(iso) {
  if (!iso) return '—'
  return new Date(iso).toLocaleString(undefined, {
    month: 'short', day: 'numeric',
    hour: '2-digit', minute: '2-digit', second: '2-digit',
  })
}

function StatusBadge({ status, styles = STATUS_STYLES, spin = false }) {
  const Icon = STATUS_ICONS[status] ?? Clock
  return (
    <span className={`inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-medium ${styles[status] ?? ''}`}>
      <Icon size={11} className={spin && status === 'RUNNING' ? 'animate-spin' : ''} />
      {status.replace(/_/g, ' ')}
    </span>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Field card
// ─────────────────────────────────────────────────────────────────────────────

function Field({ label, children }) {
  return (
    <div>
      <p className="text-xs font-medium text-slate-400 uppercase tracking-wide mb-0.5">{label}</p>
      <div className="text-sm text-slate-800">{children}</div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// ExecutionDetail
// ─────────────────────────────────────────────────────────────────────────────

export default function ExecutionDetail() {
  const { id } = useParams()
  const navigate = useNavigate()
  const [exec, setExec] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError]     = useState(null)

  const fetchData = async () => {
    setLoading(true)
    setError(null)
    try {
      const data = await getExecution(id)
      setExec(data)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load execution')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { fetchData() }, [id])

  // ── Auto-refresh while running ──────────────────────────────────────────────
  useEffect(() => {
    if (!exec || exec.status === 'RUNNING' || exec.status === 'RETRYING') {
      const t = setInterval(fetchData, 3000)
      return () => clearInterval(t)
    }
  }, [exec?.status])

  if (loading && !exec) {
    return (
      <div className="flex items-center justify-center py-24 text-slate-400 gap-2">
        <Loader2 size={20} className="animate-spin" />
        <span className="text-sm">Loading…</span>
      </div>
    )
  }

  if (error) {
    return (
      <div className="p-6">
        <div className="flex items-center gap-2 rounded-xl bg-rose-50 border border-rose-200 px-4 py-3 text-sm text-rose-700">
          <AlertCircle size={15} />
          {error}
          <button onClick={fetchData} className="ml-auto text-rose-600 underline text-xs">Retry</button>
        </div>
      </div>
    )
  }

  if (!exec) return null

  const isActive = exec.status === 'RUNNING' || exec.status === 'RETRYING'

  return (
    <div className="p-6 space-y-5 max-w-5xl">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <button
            onClick={() => navigate(-1)}
            className="flex items-center gap-1.5 rounded-xl border border-slate-200 bg-white px-3 py-1.5 text-sm text-slate-600 hover:bg-slate-50 transition"
          >
            <ArrowLeft size={14} />
          </button>
          <div>
            <h1 className="text-xl font-bold text-slate-800">{exec.actionName}</h1>
            <p className="text-xs text-slate-400 font-mono mt-0.5">{exec.id}</p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          {isActive && (
            <span className="flex items-center gap-1.5 rounded-full bg-teal-50 border border-teal-200 px-3 py-1 text-xs font-medium text-teal-700">
              <Loader2 size={11} className="animate-spin" /> Live
            </span>
          )}
          <button
            onClick={fetchData}
            className="flex items-center gap-1.5 rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm text-slate-600 hover:bg-slate-50 transition"
          >
            <RefreshCw size={14} /> Refresh
          </button>
        </div>
      </div>

      {/* Summary card */}
      <div className="rounded-2xl bg-white border border-slate-200 shadow-sm p-5">
        <div className="grid grid-cols-2 gap-x-8 gap-y-4 sm:grid-cols-4">
          <Field label="Status">
            <StatusBadge status={exec.status} spin />
          </Field>
          <Field label="Window">
            <button
              className="font-mono text-xs bg-slate-100 rounded-lg px-2 py-0.5 text-indigo-600 hover:bg-indigo-50 transition"
              onClick={() => navigate(`/windows/${exec.windowInstanceId}`)}
            >
              {exec.windowInstanceId.slice(0, 8)}…
            </button>
          </Field>
          <Field label="Profile">
            <span className="font-mono text-xs bg-slate-100 rounded-lg px-2 py-0.5 text-slate-700">
              {exec.profileId.slice(0, 8)}…
            </span>
          </Field>
          <Field label="Action ID">
            <span className="font-mono text-xs text-slate-600">{exec.actionId.slice(0, 12)}…</span>
          </Field>
          <Field label="Started At">{formatDt(exec.startedAt)}</Field>
          <Field label="Completed At">{formatDt(exec.completedAt)}</Field>
          <Field label="Duration">
            {exec.durationMs != null ? `${(exec.durationMs / 1000).toFixed(2)}s` : '—'}
          </Field>
          <Field label="Records Processed">{exec.totalRecordsProcessed}</Field>
          <Field label="Attempts">{exec.totalAttempts}</Field>
          <Field label="Current Step">{exec.currentStepIndex}</Field>
          {exec.errorMessage && (
            <div className="col-span-2 sm:col-span-4">
              <Field label="Error">
                <span className="text-rose-700">{exec.errorMessage}</span>
              </Field>
            </div>
          )}
        </div>
      </div>

      {/* Step executions */}
      <div>
        <h2 className="text-sm font-semibold text-slate-700 mb-3 flex items-center gap-2">
          <GitBranch size={14} />
          Step Executions
          <span className="rounded-full bg-slate-100 px-2 py-0.5 text-xs font-medium text-slate-600">
            {exec.stepExecutions.length}
          </span>
        </h2>

        {exec.stepExecutions.length === 0 ? (
          <div className="flex flex-col items-center py-12 text-slate-400 gap-2">
            <Layers size={32} strokeWidth={1} />
            <p className="text-sm">No steps recorded yet</p>
          </div>
        ) : (
          <div className="space-y-2">
            {exec.stepExecutions.map((step, i) => (
              <div
                key={step.id}
                className="rounded-xl bg-white border border-slate-200 shadow-sm px-4 py-3"
              >
                <div className="flex items-start justify-between gap-3">
                  <div className="flex items-center gap-2.5 min-w-0">
                    <span className="flex h-5 w-5 items-center justify-center rounded-full bg-slate-100 text-xs font-semibold text-slate-500">
                      {step.stepIndex + 1}
                    </span>
                    <div className="min-w-0">
                      <p className="text-sm font-medium text-slate-800 truncate">{step.stepName}</p>
                      <p className="text-xs text-slate-400 font-mono">{step.stepId}</p>
                    </div>
                  </div>
                  <div className="flex items-center gap-2 shrink-0">
                    <StatusBadge status={step.status} styles={STEP_STATUS_STYLES} />
                    {step.attempt > 1 && (
                      <span className="rounded-full bg-amber-50 border border-amber-200 px-2 py-0.5 text-xs text-amber-700">
                        attempt {step.attempt}
                      </span>
                    )}
                  </div>
                </div>

                {/* Step detail */}
                <div className="mt-2.5 grid grid-cols-2 gap-x-6 gap-y-1.5 sm:grid-cols-4 text-xs">
                  <div>
                    <span className="text-slate-400">Started </span>
                    <span className="text-slate-600">{formatDt(step.startedAt)}</span>
                  </div>
                  <div>
                    <span className="text-slate-400">Completed </span>
                    <span className="text-slate-600">{formatDt(step.completedAt)}</span>
                  </div>
                  <div>
                    <span className="text-slate-400">Duration </span>
                    <span className="text-slate-600">
                      {step.durationMs != null ? `${(step.durationMs / 1000).toFixed(2)}s` : '—'}
                    </span>
                  </div>
                  <div>
                    <span className="text-slate-400">Records </span>
                    <span className="text-slate-600">{step.recordsProcessed}</span>
                  </div>
                </div>

                {step.outputSummary && (
                  <p className="mt-1.5 text-xs text-slate-500 bg-slate-50 rounded-lg px-2.5 py-1.5">
                    {step.outputSummary}
                  </p>
                )}

                {step.errorMessage && (
                  <p className="mt-1.5 text-xs text-rose-600 bg-rose-50 rounded-lg px-2.5 py-1.5">
                    {step.errorType && <strong>{step.errorType}: </strong>}
                    {step.errorMessage}
                  </p>
                )}
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Checkpoint data (collapsible) */}
      {Object.keys(exec.checkpointData ?? {}).length > 0 && (
        <div>
          <h2 className="text-sm font-semibold text-slate-700 mb-2">Checkpoint Data</h2>
          <pre className="rounded-xl bg-slate-50 border border-slate-200 p-4 text-xs text-slate-700 overflow-x-auto">
            {JSON.stringify(exec.checkpointData, null, 2)}
          </pre>
        </div>
      )}
    </div>
  )
}

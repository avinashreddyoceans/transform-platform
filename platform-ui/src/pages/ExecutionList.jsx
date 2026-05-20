import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Layers, RefreshCw, AlertCircle, Loader2,
  CheckCircle2, Clock, XCircle, AlertTriangle,
} from 'lucide-react'
import { listExecutions } from '../api/executions'
import { ApiError } from '../api/client'

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
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

const STATUS_ICONS = {
  PENDING:               Clock,
  RUNNING:               Loader2,
  RETRYING:              AlertTriangle,
  COMPLETED:             CheckCircle2,
  COMPLETED_WITH_ERRORS: AlertTriangle,
  FAILED:                XCircle,
  CANCELLED:             XCircle,
}

const ALL_STATUSES = ['RUNNING', 'RETRYING', 'PENDING', 'COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED', 'CANCELLED']

function formatDt(iso) {
  if (!iso) return '—'
  return new Date(iso).toLocaleString(undefined, {
    month: 'short', day: 'numeric',
    hour: '2-digit', minute: '2-digit',
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// ExecutionList
// ─────────────────────────────────────────────────────────────────────────────

export default function ExecutionList() {
  const navigate = useNavigate()
  const [executions, setExecutions] = useState([])
  const [loading, setLoading]       = useState(true)
  const [error, setError]           = useState(null)
  const [statusFilter, setStatus]   = useState('')

  const fetchData = async () => {
    setLoading(true)
    setError(null)
    try {
      const data = await listExecutions(statusFilter ? { status: statusFilter } : {})
      setExecutions(data)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load executions')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { fetchData() }, [statusFilter])

  return (
    <div className="p-6 space-y-5 max-w-6xl">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-slate-800">Executions</h1>
          <p className="text-sm text-slate-400 mt-0.5">Workflow execution history</p>
        </div>
        <button
          onClick={fetchData}
          className="flex items-center gap-1.5 rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm text-slate-600 hover:bg-slate-50 transition"
        >
          <RefreshCw size={14} /> Refresh
        </button>
      </div>

      {/* Filters */}
      <div className="flex items-center gap-2 flex-wrap">
        <button
          onClick={() => setStatus('')}
          className={`rounded-full px-3 py-1 text-xs font-medium border transition
            ${statusFilter === '' ? 'bg-slate-800 text-white border-slate-800' : 'bg-white text-slate-600 border-slate-200 hover:border-slate-400'}`}
        >
          All
        </button>
        {ALL_STATUSES.map(s => (
          <button
            key={s}
            onClick={() => setStatus(s)}
            className={`rounded-full px-3 py-1 text-xs font-medium border transition
              ${statusFilter === s ? 'bg-slate-800 text-white border-slate-800' : 'bg-white text-slate-600 border-slate-200 hover:border-slate-400'}`}
          >
            {s.replace('_', ' ')}
          </button>
        ))}
      </div>

      {/* Error */}
      {error && (
        <div className="flex items-center gap-2 rounded-xl bg-rose-50 border border-rose-200 px-4 py-3 text-sm text-rose-700">
          <AlertCircle size={15} />
          {error}
          <button onClick={fetchData} className="ml-auto text-rose-600 underline text-xs">Retry</button>
        </div>
      )}

      {/* Table */}
      {loading ? (
        <div className="flex items-center justify-center py-16 text-slate-400 gap-2">
          <Loader2 size={20} className="animate-spin" />
          <span className="text-sm">Loading…</span>
        </div>
      ) : executions.length === 0 ? (
        <div className="flex flex-col items-center py-16 text-slate-400 gap-3">
          <Layers size={36} strokeWidth={1} />
          <p className="text-sm">No executions found</p>
        </div>
      ) : (
        <div className="rounded-2xl bg-white border border-slate-200 shadow-sm overflow-hidden">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-slate-100 bg-slate-50">
                {['Action', 'Status', 'Profile', 'Records', 'Steps', 'Started At', 'Duration'].map(h => (
                  <th key={h} className="px-5 py-2.5 text-left text-xs font-semibold text-slate-500">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {executions.map(e => {
                const StatusIcon = STATUS_ICONS[e.status] ?? Clock
                return (
                  <tr
                    key={e.id}
                    className="border-b border-slate-50 hover:bg-slate-50 cursor-pointer transition"
                    onClick={() => navigate(`/executions/${e.id}`)}
                  >
                    <td className="px-5 py-3 font-medium text-slate-800">{e.actionName}</td>
                    <td className="px-5 py-3">
                      <span className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium ${STATUS_STYLES[e.status] ?? ''}`}>
                        <StatusIcon size={11} className={e.status === 'RUNNING' ? 'animate-spin' : ''} />
                        {e.status.replace('_', ' ')}
                      </span>
                    </td>
                    <td className="px-5 py-3">
                      <span className="font-mono text-xs bg-slate-100 rounded-lg px-2 py-0.5 text-slate-700">
                        {e.profileId.slice(0, 8)}…
                      </span>
                    </td>
                    <td className="px-5 py-3 text-slate-700">{e.totalRecordsProcessed}</td>
                    <td className="px-5 py-3 text-slate-700">{e.stepCount}</td>
                    <td className="px-5 py-3 text-slate-500 text-xs">{formatDt(e.startedAt)}</td>
                    <td className="px-5 py-3 text-slate-500 text-xs">
                      {e.durationMs != null ? `${(e.durationMs / 1000).toFixed(1)}s` : '—'}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

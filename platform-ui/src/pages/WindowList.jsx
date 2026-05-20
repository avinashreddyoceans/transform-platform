import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  CalendarClock, RefreshCw, AlertCircle, Loader2,
  CheckCircle2, Clock, XCircle, AlertTriangle, FileText,
} from 'lucide-react'
import { listWindows } from '../api/windows'
import { ApiError } from '../api/client'

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

const STATUS_STYLES = {
  OPEN:         'bg-teal-50 text-teal-700 border border-teal-200',
  PENDING:      'bg-indigo-50 text-indigo-700 border border-indigo-200',
  CLOSING:      'bg-amber-50 text-amber-700 border border-amber-200',
  CLOSED:       'bg-slate-100 text-slate-600',
  ERROR:        'bg-rose-50 text-rose-700 border border-rose-200',
  FORCE_CLOSED: 'bg-rose-100 text-rose-800 border border-rose-200',
}

const STATUS_ICONS = {
  OPEN:         CheckCircle2,
  PENDING:      Clock,
  CLOSING:      AlertTriangle,
  CLOSED:       CheckCircle2,
  ERROR:        XCircle,
  FORCE_CLOSED: XCircle,
}

const ALL_STATUSES = ['OPEN', 'PENDING', 'CLOSING', 'CLOSED', 'ERROR', 'FORCE_CLOSED']

function formatDt(iso) {
  if (!iso) return '—'
  return new Date(iso).toLocaleString(undefined, {
    month: 'short', day: 'numeric',
    hour: '2-digit', minute: '2-digit',
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// WindowList
// ─────────────────────────────────────────────────────────────────────────────

export default function WindowList() {
  const navigate = useNavigate()
  const [windows, setWindows]     = useState([])
  const [loading, setLoading]     = useState(true)
  const [error, setError]         = useState(null)
  const [statusFilter, setStatus] = useState('')

  const fetchData = async () => {
    setLoading(true)
    setError(null)
    try {
      const data = await listWindows(statusFilter ? { status: statusFilter } : {})
      setWindows(data)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load windows')
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
          <h1 className="text-xl font-bold text-slate-800">Windows</h1>
          <p className="text-sm text-slate-400 mt-0.5">Scheduled execution windows</p>
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
            {s}
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
      ) : windows.length === 0 ? (
        <div className="flex flex-col items-center py-16 text-slate-400 gap-3">
          <CalendarClock size={36} strokeWidth={1} />
          <p className="text-sm">No windows found</p>
        </div>
      ) : (
        <div className="rounded-2xl bg-white border border-slate-200 shadow-sm overflow-hidden">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-slate-100 bg-slate-50">
                {['Profile ID', 'Status', 'Opened At', 'Closed At', 'Events', 'Files'].map(h => (
                  <th key={h} className="px-5 py-2.5 text-left text-xs font-semibold text-slate-500">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {windows.map(w => {
                const StatusIcon = STATUS_ICONS[w.status] ?? Clock
                return (
                  <tr
                    key={w.id}
                    className="border-b border-slate-50 hover:bg-slate-50 cursor-pointer transition"
                    onClick={() => navigate(`/windows/${w.id}`)}
                  >
                    <td className="px-5 py-3">
                      <span className="font-mono text-xs bg-slate-100 rounded-lg px-2 py-0.5 text-slate-700">
                        {w.profileId.slice(0, 8)}…
                      </span>
                    </td>
                    <td className="px-5 py-3">
                      <span className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium ${STATUS_STYLES[w.status] ?? ''}`}>
                        <StatusIcon size={11} />
                        {w.status}
                      </span>
                    </td>
                    <td className="px-5 py-3 text-slate-500 text-xs">{formatDt(w.openedAt)}</td>
                    <td className="px-5 py-3 text-slate-500 text-xs">{formatDt(w.closedAt)}</td>
                    <td className="px-5 py-3 text-slate-700 font-medium">{w.eventCount}</td>
                    <td className="px-5 py-3 text-slate-700 font-medium">{w.fileCount}</td>
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

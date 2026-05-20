import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  ScrollText, CheckCircle2, Clock, AlertCircle,
  TrendingUp, Plus, ArrowRight, Loader2, RefreshCw,
} from 'lucide-react'
import { listProfiles } from '../api/profiles'
import { ApiError } from '../api/client'

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

const ICON_COLORS = {
  indigo: 'bg-indigo-100 text-indigo-600',
  teal:   'bg-teal-100 text-teal-600',
  green:  'bg-green-100 text-green-600',
  rose:   'bg-rose-100 text-rose-600',
}

const STATUS_STYLES = {
  OPEN:    'bg-teal-50 text-teal-700 border border-teal-200',
  CLOSING: 'bg-amber-50 text-amber-700 border border-amber-200',
  CLOSED:  'bg-slate-100 text-slate-600',
  ERROR:   'bg-rose-50 text-rose-700 border border-rose-200',
  PENDING: 'bg-indigo-50 text-indigo-700 border border-indigo-200',
}

// ─────────────────────────────────────────────────────────────────────────────
// Dashboard
// ─────────────────────────────────────────────────────────────────────────────

export default function Dashboard() {
  const navigate = useNavigate()
  const [profiles, setProfiles] = useState([])
  const [loading, setLoading]   = useState(true)
  const [error, setError]       = useState(null)

  const fetchData = async () => {
    setLoading(true)
    setError(null)
    try {
      const data = await listProfiles()
      setProfiles(data)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load dashboard data')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { fetchData() }, [])

  // ── Derived stats from profiles ────────────────────────────────────────────
  const total    = profiles.length
  const enabled  = profiles.filter(p => p.status === 'ENABLED').length
  const disabled = profiles.filter(p => p.status === 'DISABLED').length
  const draft    = profiles.filter(p => p.status === 'DRAFT').length

  const stats = [
    { label: 'Total Profiles',  value: String(total),   sub: `${enabled} active`,   icon: ScrollText,   color: 'indigo' },
    { label: 'Enabled',         value: String(enabled),  sub: 'Scheduling windows',  icon: CheckCircle2, color: 'teal'   },
    { label: 'Disabled',        value: String(disabled), sub: 'Paused',              icon: Clock,        color: 'green'  },
    { label: 'Draft',           value: String(draft),    sub: 'Not yet scheduled',   icon: AlertCircle,  color: 'rose'   },
  ]

  return (
    <div className="p-6 space-y-6 max-w-6xl">
      {/* Page header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-slate-800">Dashboard</h1>
          <p className="text-sm text-slate-400 mt-0.5">Overview of all profiles</p>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={fetchData}
            className="flex items-center gap-1.5 rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm text-slate-600 hover:bg-slate-50 transition"
            title="Refresh"
          >
            <RefreshCw size={14} />
          </button>
          <button
            onClick={() => navigate('/profiles/new')}
            className="flex items-center gap-1.5 rounded-xl bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700 transition shadow-sm"
          >
            <Plus size={15} /> New Profile
          </button>
        </div>
      </div>

      {/* Error banner */}
      {error && (
        <div className="flex items-center gap-2 rounded-xl bg-rose-50 border border-rose-200 px-4 py-3 text-sm text-rose-700">
          <AlertCircle size={15} />
          {error}
          <button onClick={fetchData} className="ml-auto text-rose-600 underline text-xs">Retry</button>
        </div>
      )}

      {/* Stats */}
      {loading ? (
        <div className="flex items-center justify-center py-12 text-slate-400 gap-2">
          <Loader2 size={20} className="animate-spin" />
          <span className="text-sm">Loading…</span>
        </div>
      ) : (
        <>
          <div className="grid grid-cols-4 gap-4">
            {stats.map(s => {
              const Icon = s.icon
              return (
                <div key={s.label} className="rounded-2xl bg-white border border-slate-200 p-4 shadow-sm">
                  <div className="flex items-center justify-between mb-3">
                    <span className="text-xs font-medium text-slate-500">{s.label}</span>
                    <div className={`flex h-7 w-7 items-center justify-center rounded-lg ${ICON_COLORS[s.color]}`}>
                      <Icon size={14} />
                    </div>
                  </div>
                  <p className="text-2xl font-bold text-slate-800">{s.value}</p>
                  <p className="text-xs text-slate-400 mt-0.5">{s.sub}</p>
                </div>
              )
            })}
          </div>

          {/* Recent profiles */}
          <div className="rounded-2xl bg-white border border-slate-200 shadow-sm overflow-hidden">
            <div className="flex items-center justify-between px-5 py-4 border-b border-slate-100">
              <h2 className="text-sm font-semibold text-slate-700">Recent Profiles</h2>
              <button
                onClick={() => navigate('/profiles')}
                className="flex items-center gap-1 text-xs text-indigo-600 hover:text-indigo-700 font-medium"
              >
                View all <ArrowRight size={12} />
              </button>
            </div>

            {profiles.length === 0 ? (
              <div className="flex flex-col items-center py-12 text-slate-400 gap-3">
                <ScrollText size={32} strokeWidth={1} />
                <p className="text-sm">No profiles yet</p>
                <button
                  onClick={() => navigate('/profiles/new')}
                  className="flex items-center gap-1 text-sm text-indigo-600 hover:text-indigo-700"
                >
                  <Plus size={13} /> Create your first profile
                </button>
              </div>
            ) : (
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-slate-100 bg-slate-50">
                    {['Profile', 'Client', 'Status', 'Open → Close triggers', 'Actions'].map(h => (
                      <th key={h} className="px-5 py-2.5 text-left text-xs font-semibold text-slate-500">{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {profiles.slice(0, 8).map(p => (
                    <tr
                      key={p.id}
                      className="border-b border-slate-50 hover:bg-slate-50 transition cursor-pointer"
                      onClick={() => navigate(`/profiles/${p.id}/edit`)}
                    >
                      <td className="px-5 py-3 font-medium text-slate-800">{p.name}</td>
                      <td className="px-5 py-3">
                        <span className="rounded-lg bg-slate-100 px-2 py-0.5 text-xs font-mono font-medium text-slate-600">
                          {p.clientId}
                        </span>
                      </td>
                      <td className="px-5 py-3">
                        <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium
                          ${p.status === 'ENABLED' ? 'bg-teal-50 text-teal-700 border border-teal-200'
                            : p.status === 'DISABLED' ? 'bg-slate-100 text-slate-600'
                            : 'bg-amber-50 text-amber-700 border border-amber-200'}`}>
                          {p.status}
                        </span>
                      </td>
                      <td className="px-5 py-3 text-slate-500 text-xs">
                        {p.openTriggerType} → {p.closeTriggerType}
                      </td>
                      <td className="px-5 py-3 text-slate-600 font-medium text-center">
                        {p.actionCount}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        </>
      )}
    </div>
  )
}

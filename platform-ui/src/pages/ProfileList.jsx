import { useState, useEffect, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Plus, Search, MoreVertical, Play, Pause,
  Pencil, Trash2, Clock, CheckCircle2, XCircle,
  FileText, RefreshCw, AlertCircle, Loader2,
} from 'lucide-react'
import { Breadcrumb } from '../App'
import { listProfiles, enableProfile, disableProfile, triggerProfile, deleteProfile } from '../api/profiles'
import { ApiError } from '../api/client'

// ─────────────────────────────────────────────────────────────────────────────
// Status display helpers
// ─────────────────────────────────────────────────────────────────────────────

const STATUS_CONFIG = {
  ENABLED:  { label: 'Enabled',  dot: 'bg-teal-400',   text: 'text-teal-700',  bg: 'bg-teal-50  border border-teal-200',  icon: CheckCircle2 },
  DISABLED: { label: 'Disabled', dot: 'bg-slate-300',  text: 'text-slate-600', bg: 'bg-slate-100',                         icon: Pause },
  DRAFT:    { label: 'Draft',    dot: 'bg-amber-400',  text: 'text-amber-700', bg: 'bg-amber-50 border border-amber-200',  icon: FileText },
  DELETED:  { label: 'Deleted',  dot: 'bg-rose-400',   text: 'text-rose-700',  bg: 'bg-rose-50  border border-rose-200',   icon: XCircle },
}

const TRIGGER_LABELS = {
  TIME_BASED:   'Time',
  FILE_ARRIVAL: 'File',
  EVENT_COUNT:  'Count',
  SESSION_GAP:  'Session',
  COMPOUND:     'Compound',
  MANUAL:       'Manual',
}

const TRIGGER_COLORS = {
  TIME_BASED:   'bg-indigo-100 text-indigo-700',
  FILE_ARRIVAL: 'bg-teal-100 text-teal-700',
  EVENT_COUNT:  'bg-violet-100 text-violet-700',
  SESSION_GAP:  'bg-amber-100 text-amber-700',
  COMPOUND:     'bg-rose-100 text-rose-700',
  MANUAL:       'bg-slate-100 text-slate-600',
}

const StatusBadge = ({ status }) => {
  const cfg = STATUS_CONFIG[status] ?? STATUS_CONFIG.DRAFT
  return (
    <span className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium ${cfg.bg} ${cfg.text}`}>
      <span className={`h-1.5 w-1.5 rounded-full ${cfg.dot}`} />
      {cfg.label}
    </span>
  )
}

const TriggerBadge = ({ type }) => (
  <span className={`rounded-md px-1.5 py-0.5 text-xs font-medium ${TRIGGER_COLORS[type] ?? 'bg-slate-100 text-slate-600'}`}>
    {TRIGGER_LABELS[type] ?? type}
  </span>
)

// ─────────────────────────────────────────────────────────────────────────────
// Actions dropdown
// ─────────────────────────────────────────────────────────────────────────────

const ProfileActions = ({ profile, onEdit, onToggle, onTrigger, onDelete }) => {
  const [open, setOpen] = useState(false)
  return (
    <div className="relative">
      <button
        onClick={() => setOpen(o => !o)}
        className="rounded-lg p-1.5 text-slate-400 hover:bg-slate-100 hover:text-slate-600"
      >
        <MoreVertical size={15} />
      </button>
      {open && (
        <div
          className="absolute right-0 top-8 z-20 w-44 rounded-xl border border-slate-200 bg-white shadow-lg overflow-hidden"
          onMouseLeave={() => setOpen(false)}
        >
          <button onClick={() => { onEdit(); setOpen(false) }}
            className="flex w-full items-center gap-2 px-3 py-2 text-sm text-slate-700 hover:bg-slate-50">
            <Pencil size={13} /> Edit config
          </button>
          {profile.status === 'ENABLED' && (
            <button onClick={() => { onToggle('disable'); setOpen(false) }}
              className="flex w-full items-center gap-2 px-3 py-2 text-sm text-amber-600 hover:bg-amber-50">
              <Pause size={13} /> Disable
            </button>
          )}
          {(profile.status === 'DISABLED' || profile.status === 'DRAFT') && (
            <button onClick={() => { onToggle('enable'); setOpen(false) }}
              className="flex w-full items-center gap-2 px-3 py-2 text-sm text-teal-600 hover:bg-teal-50">
              <Play size={13} /> Enable
            </button>
          )}
          {profile.status === 'ENABLED' && (
            <button onClick={() => { onTrigger(); setOpen(false) }}
              className="flex w-full items-center gap-2 px-3 py-2 text-sm text-indigo-600 hover:bg-indigo-50">
              <RefreshCw size={13} /> Trigger now
            </button>
          )}
          <div className="border-t border-slate-100" />
          <button onClick={() => { onDelete(); setOpen(false) }}
            className="flex w-full items-center gap-2 px-3 py-2 text-sm text-rose-600 hover:bg-rose-50">
            <Trash2 size={13} /> Delete
          </button>
        </div>
      )}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Profile row
// ─────────────────────────────────────────────────────────────────────────────

const ProfileRow = ({ profile, onEdit, onStatusChange, onTrigger, onDelete }) => (
  <tr className="border-b border-slate-50 hover:bg-slate-50/60 transition group">
    <td className="px-5 py-3.5">
      <div>
        <button
          onClick={onEdit}
          className="font-semibold text-slate-800 hover:text-indigo-600 transition text-left"
        >
          {profile.name}
        </button>
        <p className="text-xs text-slate-400 mt-0.5 max-w-xs truncate">{profile.description}</p>
      </div>
    </td>
    <td className="px-4 py-3.5">
      <span className="rounded-lg bg-slate-100 px-2 py-1 text-xs font-mono font-medium text-slate-600">
        {profile.clientId}
      </span>
    </td>
    <td className="px-4 py-3.5">
      <StatusBadge status={profile.status} />
    </td>
    <td className="px-4 py-3.5">
      <div className="flex items-center gap-1">
        <TriggerBadge type={profile.openTriggerType} />
        <span className="text-slate-300 text-xs">→</span>
        <TriggerBadge type={profile.closeTriggerType} />
      </div>
    </td>
    <td className="px-4 py-3.5 text-center">
      <span className="text-sm font-medium text-slate-600">{profile.actionCount}</span>
    </td>
    <td className="px-4 py-3.5">
      <span className="text-xs text-slate-400">v{profile.version}</span>
    </td>
    <td className="px-4 py-3.5 text-right">
      <ProfileActions
        profile={profile}
        onEdit={onEdit}
        onToggle={onStatusChange}
        onTrigger={onTrigger}
        onDelete={onDelete}
      />
    </td>
  </tr>
)

// ─────────────────────────────────────────────────────────────────────────────
// ProfileList page
// ─────────────────────────────────────────────────────────────────────────────

export default function ProfileList() {
  const navigate = useNavigate()
  const [profiles, setProfiles] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError]   = useState(null)
  const [search, setSearch] = useState('')
  const [filterStatus, setFilterStatus] = useState('ALL')

  // ── Fetch profiles ──────────────────────────────────────────────────────────

  const fetchProfiles = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const data = await listProfiles()
      setProfiles(data)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load profiles')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { fetchProfiles() }, [fetchProfiles])

  // ── Filtering ───────────────────────────────────────────────────────────────

  const filtered = profiles.filter(p => {
    const matchSearch = p.name.toLowerCase().includes(search.toLowerCase())
      || p.clientId.toLowerCase().includes(search.toLowerCase())
    const matchStatus = filterStatus === 'ALL' || p.status === filterStatus
    return matchSearch && matchStatus
  })

  const statusCounts = {
    ALL:      profiles.length,
    ENABLED:  profiles.filter(p => p.status === 'ENABLED').length,
    DISABLED: profiles.filter(p => p.status === 'DISABLED').length,
    DRAFT:    profiles.filter(p => p.status === 'DRAFT').length,
  }

  // ── Actions ─────────────────────────────────────────────────────────────────

  const handleStatusChange = async (id, action) => {
    try {
      const updated = action === 'enable' ? await enableProfile(id) : await disableProfile(id)
      setProfiles(ps => ps.map(p => p.id === updated.id ? { ...p, status: updated.status } : p))
    } catch (err) {
      alert(err instanceof ApiError ? err.message : 'Status change failed')
    }
  }

  const handleTrigger = async (id) => {
    try {
      const result = await triggerProfile(id)
      alert(`Window triggered. ID: ${result.windowId ?? 'n/a'}`)
    } catch (err) {
      alert(err instanceof ApiError ? err.message : 'Trigger failed')
    }
  }

  const handleDelete = async (id) => {
    if (!window.confirm('Delete this profile? This cannot be undone.')) return
    try {
      await deleteProfile(id)
      setProfiles(ps => ps.filter(p => p.id !== id))
    } catch (err) {
      alert(err instanceof ApiError ? err.message : 'Delete failed')
    }
  }

  // ── Render ──────────────────────────────────────────────────────────────────

  return (
    <div className="p-6 space-y-5 max-w-7xl">
      {/* Header */}
      <div className="space-y-1">
        <Breadcrumb crumbs={['Profiles']} />
        <div className="flex items-center justify-between">
          <h1 className="text-xl font-bold text-slate-800">Profiles</h1>
          <div className="flex items-center gap-2">
            <button
              onClick={fetchProfiles}
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
      </div>

      {/* Error banner */}
      {error && (
        <div className="flex items-center gap-2 rounded-xl bg-rose-50 border border-rose-200 px-4 py-3 text-sm text-rose-700">
          <AlertCircle size={15} />
          {error}
          <button onClick={fetchProfiles} className="ml-auto text-rose-600 underline text-xs">Retry</button>
        </div>
      )}

      {/* Filters bar */}
      <div className="flex items-center gap-3">
        <div className="relative flex-1 max-w-sm">
          <Search size={15} className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
          <input
            value={search}
            onChange={e => setSearch(e.target.value)}
            placeholder="Search by name or client…"
            className="w-full rounded-xl border border-slate-200 bg-white py-2 pl-9 pr-3 text-sm
              text-slate-700 placeholder:text-slate-400 focus:border-indigo-400 focus:outline-none
              focus:ring-2 focus:ring-indigo-100 transition"
          />
        </div>
        <div className="flex rounded-xl border border-slate-200 bg-white overflow-hidden">
          {Object.entries(statusCounts).map(([status, count]) => (
            <button
              key={status}
              onClick={() => setFilterStatus(status)}
              className={`px-3 py-1.5 text-xs font-medium transition
                ${filterStatus === status
                  ? 'bg-indigo-600 text-white'
                  : 'text-slate-500 hover:bg-slate-50'}`}
            >
              {status === 'ALL' ? 'All' : status[0] + status.slice(1).toLowerCase()}
              <span className={`ml-1.5 rounded-full px-1.5 py-0.5 text-xs
                ${filterStatus === status ? 'bg-white/20' : 'bg-slate-100'}`}>
                {count}
              </span>
            </button>
          ))}
        </div>
      </div>

      {/* Table */}
      <div className="rounded-2xl bg-white border border-slate-200 shadow-sm overflow-hidden">
        {loading ? (
          <div className="flex items-center justify-center py-16 text-slate-400 gap-2">
            <Loader2 size={20} className="animate-spin" />
            <span className="text-sm">Loading profiles…</span>
          </div>
        ) : filtered.length === 0 ? (
          <div className="flex flex-col items-center py-16 text-slate-400 gap-3">
            <AlertCircle size={36} strokeWidth={1} />
            <p className="font-medium">{profiles.length === 0 ? 'No profiles yet' : 'No profiles match your filter'}</p>
            {profiles.length === 0 && (
              <button onClick={() => navigate('/profiles/new')}
                className="flex items-center gap-1 text-sm text-indigo-600 hover:text-indigo-700">
                <Plus size={13} /> Create your first profile
              </button>
            )}
          </div>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-slate-100 bg-slate-50">
                {[
                  { label: 'Profile',   wide: true },
                  { label: 'Client'              },
                  { label: 'Status'              },
                  { label: 'Triggers'            },
                  { label: 'Actions', center: true },
                  { label: 'Ver'                 },
                  { label: ''                    },
                ].map((h, i) => (
                  <th key={i}
                    className={`px-4 py-2.5 text-left text-xs font-semibold text-slate-500
                      ${h.wide ? 'px-5 min-w-64' : ''} ${h.center ? 'text-center' : ''}`}>
                    {h.label}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {filtered.map(profile => (
                <ProfileRow
                  key={profile.id}
                  profile={profile}
                  onEdit={() => navigate(`/profiles/${profile.id}/edit`)}
                  onStatusChange={(action) => handleStatusChange(profile.id, action)}
                  onTrigger={() => handleTrigger(profile.id)}
                  onDelete={() => handleDelete(profile.id)}
                />
              ))}
            </tbody>
          </table>
        )}
      </div>

      {/* Footer */}
      {!loading && (
        <p className="text-xs text-slate-400 text-right">
          {filtered.length} of {profiles.length} profiles
        </p>
      )}
    </div>
  )
}

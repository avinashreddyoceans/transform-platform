import { useState, useEffect, useRef } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import {
  ArrowLeft, RefreshCw, AlertCircle, Loader2,
  Database, FileText, Layers, PlayCircle, Upload, CheckCircle2, XCircle,
} from 'lucide-react'
import { getWindow, getWindowData, getWindowFiles, openWindow, submitFileToWindow } from '../api/windows'
import { getWindowExecutions } from '../api/executions'
import { ApiError } from '../api/client'

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

const STATUS_BADGE = {
  OPEN:         'bg-teal-50 text-teal-700 border border-teal-200',
  PENDING:      'bg-indigo-50 text-indigo-700 border border-indigo-200',
  CLOSING:      'bg-amber-50 text-amber-700 border border-amber-200',
  CLOSED:       'bg-slate-100 text-slate-600',
  ERROR:        'bg-rose-50 text-rose-700 border border-rose-200',
  FORCE_CLOSED: 'bg-rose-100 text-rose-800 border border-rose-200',
}

const EXEC_STATUS_BADGE = {
  PENDING:               'bg-indigo-50 text-indigo-700 border border-indigo-200',
  RUNNING:               'bg-teal-50 text-teal-700 border border-teal-200',
  RETRYING:              'bg-amber-50 text-amber-700 border border-amber-200',
  COMPLETED:             'bg-green-50 text-green-700 border border-green-200',
  COMPLETED_WITH_ERRORS: 'bg-amber-50 text-amber-700 border border-amber-200',
  FAILED:                'bg-rose-50 text-rose-700 border border-rose-200',
  CANCELLED:             'bg-slate-100 text-slate-600',
}

function formatDt(iso) {
  if (!iso) return '—'
  return new Date(iso).toLocaleString()
}

function InfoRow({ label, value }) {
  return (
    <div className="flex justify-between py-2 border-b border-slate-50 last:border-0">
      <span className="text-xs text-slate-500">{label}</span>
      <span className="text-xs font-medium text-slate-800">{value ?? '—'}</span>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// WindowDetail
// ─────────────────────────────────────────────────────────────────────────────

export default function WindowDetail() {
  const { id } = useParams()
  const navigate = useNavigate()

  const [winData, setWinData]         = useState(null)
  const [dataRecords, setData]        = useState([])
  const [executions, setExecutions]   = useState([])
  const [fileLogs, setFileLogs]       = useState([])
  const [loading, setLoading]         = useState(true)
  const [error, setError]             = useState(null)
  const [activeTab, setTab]           = useState('data')

  // ── Open Window action state ───────────────────────────────────────────────
  const [opening, setOpening]         = useState(false)
  const [openResult, setOpenResult]   = useState(null)  // { ok, message }

  // ── Submit File action state ───────────────────────────────────────────────
  const [showUpload, setShowUpload]   = useState(false)
  const [submitting, setSubmitting]   = useState(false)
  const [submitResult, setSubmitResult] = useState(null)  // { ok, message, records }
  const fileInputRef                  = useRef(null)

  const fetchAll = async () => {
    setLoading(true)
    setError(null)
    try {
      const [win, data, execs, logs] = await Promise.all([
        getWindow(id),
        getWindowData(id),
        getWindowExecutions(id),
        getWindowFiles(id).catch(() => []),
      ])
      setWinData(win)
      setData(data)
      setExecutions(execs)
      setFileLogs(logs)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load window')
    } finally {
      setLoading(false)
    }
  }

  const handleOpenWindow = async () => {
    setOpening(true)
    setOpenResult(null)
    try {
      await openWindow(id)
      setOpenResult({ ok: true, message: 'Window opened successfully' })
      await fetchAll()
    } catch (err) {
      setOpenResult({ ok: false, message: err instanceof ApiError ? err.message : 'Failed to open window' })
    } finally {
      setOpening(false)
    }
  }

  const handleSubmitFile = async (e) => {
    e.preventDefault()
    const file = fileInputRef.current?.files?.[0]
    if (!file) return

    setSubmitting(true)
    setSubmitResult(null)
    try {
      const result = await submitFileToWindow(id, file)
      setSubmitResult({
        ok: true,
        message: result.message,
        records: result.recordsProcessed,
      })
      setShowUpload(false)
      await fetchAll()
    } catch (err) {
      setSubmitResult({
        ok: false,
        message: err instanceof ApiError ? err.message : 'Failed to submit file',
      })
    } finally {
      setSubmitting(false)
    }
  }

  useEffect(() => { fetchAll() }, [id])

  if (loading) return (
    <div className="flex items-center justify-center py-24 text-slate-400 gap-2">
      <Loader2 size={20} className="animate-spin" />
      <span className="text-sm">Loading…</span>
    </div>
  )

  if (error) return (
    <div className="p-6">
      <div className="flex items-center gap-2 rounded-xl bg-rose-50 border border-rose-200 px-4 py-3 text-sm text-rose-700">
        <AlertCircle size={15} /> {error}
        <button onClick={fetchAll} className="ml-auto text-rose-600 underline text-xs">Retry</button>
      </div>
    </div>
  )

  if (!winData) return null

  return (
    <div className="p-6 space-y-5 max-w-6xl">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <button
            onClick={() => navigate('/windows')}
            className="flex items-center gap-1.5 rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-600 hover:bg-slate-50 transition"
          >
            <ArrowLeft size={14} /> Back
          </button>
          <div>
            <p className="text-xs text-slate-400 font-mono">{winData.id}</p>
            <div className="flex items-center gap-2 mt-0.5">
              <h1 className="text-lg font-bold text-slate-800">Window</h1>
              <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ${STATUS_BADGE[winData.status] ?? ''}`}>
                {winData.status}
              </span>
            </div>
          </div>
        </div>
        <div className="flex items-center gap-2">
          {/* Open Window button — only for PENDING windows */}
          {winData?.status === 'PENDING' && (
            <button
              onClick={handleOpenWindow}
              disabled={opening}
              className="flex items-center gap-1.5 rounded-xl bg-teal-600 text-white px-3 py-2 text-sm font-medium hover:bg-teal-700 transition disabled:opacity-60"
            >
              {opening ? <Loader2 size={14} className="animate-spin" /> : <PlayCircle size={14} />}
              Open Window
            </button>
          )}

          {/* Submit File button — only for OPEN windows */}
          {winData?.status === 'OPEN' && (
            <button
              onClick={() => { setShowUpload(true); setSubmitResult(null) }}
              className="flex items-center gap-1.5 rounded-xl bg-indigo-600 text-white px-3 py-2 text-sm font-medium hover:bg-indigo-700 transition"
            >
              <Upload size={14} /> Submit File
            </button>
          )}

          <button onClick={fetchAll} className="flex items-center gap-1.5 rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm text-slate-600 hover:bg-slate-50 transition">
            <RefreshCw size={14} />
          </button>
        </div>
      </div>

      {/* Open Window result banner */}
      {openResult && (
        <div className={`flex items-center gap-2 rounded-xl px-4 py-3 text-sm ${
          openResult.ok
            ? 'bg-teal-50 border border-teal-200 text-teal-700'
            : 'bg-rose-50 border border-rose-200 text-rose-700'
        }`}>
          {openResult.ok ? <CheckCircle2 size={15} /> : <XCircle size={15} />}
          {openResult.message}
          <button onClick={() => setOpenResult(null)} className="ml-auto text-xs opacity-60 hover:opacity-100">✕</button>
        </div>
      )}

      {/* Submit File result banner */}
      {submitResult && (
        <div className={`flex items-center gap-2 rounded-xl px-4 py-3 text-sm ${
          submitResult.ok
            ? 'bg-green-50 border border-green-200 text-green-700'
            : 'bg-rose-50 border border-rose-200 text-rose-700'
        }`}>
          {submitResult.ok ? <CheckCircle2 size={15} /> : <XCircle size={15} />}
          <span>{submitResult.message}</span>
          {submitResult.records != null && (
            <span className="ml-1 font-semibold">({submitResult.records} records processed)</span>
          )}
          <button onClick={() => setSubmitResult(null)} className="ml-auto text-xs opacity-60 hover:opacity-100">✕</button>
        </div>
      )}

      {/* Submit File modal */}
      {showUpload && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 backdrop-blur-sm">
          <div className="w-full max-w-md rounded-2xl bg-white shadow-xl p-6">
            <div className="flex items-center justify-between mb-4">
              <h2 className="text-base font-bold text-slate-800">Submit File to Window</h2>
              <button onClick={() => setShowUpload(false)} className="text-slate-400 hover:text-slate-600 text-lg leading-none">✕</button>
            </div>

            <p className="text-sm text-slate-500 mb-4">
              The file will be uploaded to MinIO, and the window's close trigger will be evaluated.
              If the trigger fires, the configured <code className="text-xs bg-slate-100 px-1 rounded">ON_FILE_ARRIVED</code> workflow runs immediately.
            </p>

            <form onSubmit={handleSubmitFile} className="space-y-4">
              <div>
                <label className="block text-xs font-medium text-slate-600 mb-1">File</label>
                <input
                  ref={fileInputRef}
                  type="file"
                  required
                  className="block w-full text-sm text-slate-600 file:mr-3 file:py-1.5 file:px-3 file:rounded-lg file:border-0 file:text-sm file:font-medium file:bg-indigo-50 file:text-indigo-700 hover:file:bg-indigo-100 cursor-pointer"
                />
              </div>

              <div className="flex gap-2 pt-1">
                <button
                  type="submit"
                  disabled={submitting}
                  className="flex-1 flex items-center justify-center gap-1.5 rounded-xl bg-indigo-600 text-white py-2 text-sm font-medium hover:bg-indigo-700 transition disabled:opacity-60"
                >
                  {submitting ? <Loader2 size={14} className="animate-spin" /> : <Upload size={14} />}
                  {submitting ? 'Uploading…' : 'Submit'}
                </button>
                <button
                  type="button"
                  onClick={() => setShowUpload(false)}
                  className="flex-1 rounded-xl border border-slate-200 bg-white text-slate-600 py-2 text-sm font-medium hover:bg-slate-50 transition"
                >
                  Cancel
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Info card */}
      <div className="rounded-2xl bg-white border border-slate-200 shadow-sm p-4 grid grid-cols-2 gap-4">
        <div>
          <p className="text-xs font-semibold text-slate-500 mb-2 uppercase tracking-wide">Lifecycle</p>
          <InfoRow label="Profile ID"    value={winData.profileId} />
          <InfoRow label="Version"       value={`v${winData.profileVersion}`} />
          <InfoRow label="Opened At"     value={formatDt(winData.openedAt)} />
          <InfoRow label="Closing At"    value={formatDt(winData.closingStartedAt)} />
          <InfoRow label="Closed At"     value={formatDt(winData.closedAt)} />
          {winData.statusReason && <InfoRow label="Reason" value={winData.statusReason} />}
        </div>
        <div>
          <p className="text-xs font-semibold text-slate-500 mb-2 uppercase tracking-wide">Summary</p>
          <InfoRow label="Event count"         value={winData.eventCount} />
          <InfoRow label="Files submitted"       value={fileLogs.length} />
          <InfoRow label="Data records"        value={dataRecords.length} />
          <InfoRow label="Workflow executions" value={executions.length} />
          <InfoRow label="Scheduled open"      value={formatDt(winData.scheduledOpenAt)} />
          <InfoRow label="Scheduled close"     value={formatDt(winData.scheduledCloseAt)} />
        </div>
      </div>

      {/* Tabs */}
      <div className="flex gap-1 border-b border-slate-200">
        {[
          { id: 'data',       label: 'Data Records',  icon: Database },
          { id: 'files',      label: 'Files',         icon: FileText },
          { id: 'executions', label: 'Executions',    icon: Layers },
        ].map(tab => (
          <button
            key={tab.id}
            onClick={() => setTab(tab.id)}
            className={`flex items-center gap-1.5 px-4 py-2.5 text-sm font-medium border-b-2 transition
              ${activeTab === tab.id
                ? 'border-indigo-600 text-indigo-600'
                : 'border-transparent text-slate-500 hover:text-slate-700'}`}
          >
            <tab.icon size={14} /> {tab.label}
          </button>
        ))}
      </div>

      {/* Tab: Data Records */}
      {activeTab === 'data' && (
        dataRecords.length === 0 ? (
          <div className="flex flex-col items-center py-12 text-slate-400 gap-2">
            <Database size={32} strokeWidth={1} />
            <p className="text-sm">No data records collected</p>
          </div>
        ) : (
          <div className="rounded-2xl bg-white border border-slate-200 shadow-sm overflow-hidden">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-slate-100 bg-slate-50">
                  {['Type', 'Source', 'Dedup Key', 'Arrived At'].map(h => (
                    <th key={h} className="px-5 py-2.5 text-left text-xs font-semibold text-slate-500">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {dataRecords.map(r => (
                  <tr key={r.id} className="border-b border-slate-50 hover:bg-slate-50">
                    <td className="px-5 py-2.5">
                      <span className="inline-flex rounded-full bg-indigo-50 text-indigo-700 border border-indigo-200 px-2 py-0.5 text-xs font-medium">
                        {r.recordType}
                      </span>
                    </td>
                    <td className="px-5 py-2.5 text-slate-500 text-xs font-mono">{r.sourceIntegrationId ?? '—'}</td>
                    <td className="px-5 py-2.5 text-slate-500 text-xs font-mono truncate max-w-[180px]">{r.deduplicationKey ?? '—'}</td>
                    <td className="px-5 py-2.5 text-slate-500 text-xs">{formatDt(r.arrivedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )
      )}

      {/* Tab: Files */}
      {activeTab === 'files' && (
        fileLogs.length === 0 ? (
          <div className="flex flex-col items-center py-12 text-slate-400 gap-2">
            <FileText size={32} strokeWidth={1} />
            <p className="text-sm">No files submitted to this window yet</p>
            {winData.status === 'OPEN' && (
              <button
                onClick={() => { setShowUpload(true); setSubmitResult(null) }}
                className="mt-2 flex items-center gap-1.5 rounded-xl bg-indigo-600 text-white px-4 py-2 text-sm font-medium hover:bg-indigo-700 transition"
              >
                <Upload size={14} /> Submit a File
              </button>
            )}
          </div>
        ) : (
          <div className="rounded-2xl bg-white border border-slate-200 shadow-sm overflow-hidden">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-slate-100 bg-slate-50">
                  {['File Name', 'Status', 'Direction', 'Size', 'Arrived At'].map(h => (
                    <th key={h} className="px-5 py-2.5 text-left text-xs font-semibold text-slate-500">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {fileLogs.map((f) => (
                  <tr key={f.id} className="border-b border-slate-50 hover:bg-slate-50">
                    <td className="px-5 py-2.5 font-medium text-slate-800">{f.fileName}</td>
                    <td className="px-5 py-2.5">
                      <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ${
                        f.status === 'PROCESSED'
                          ? 'bg-green-50 text-green-700 border border-green-200'
                          : f.status === 'FAILED'
                          ? 'bg-rose-50 text-rose-700 border border-rose-200'
                          : 'bg-slate-100 text-slate-600'
                      }`}>
                        {f.status}
                      </span>
                    </td>
                    <td className="px-5 py-2.5 text-slate-500 text-xs">{f.direction}</td>
                    <td className="px-5 py-2.5 text-slate-500 text-xs">
                      {f.fileSizeBytes != null ? `${(f.fileSizeBytes / 1024).toFixed(1)} KB` : '—'}
                    </td>
                    <td className="px-5 py-2.5 text-slate-500 text-xs">{formatDt(f.arrivedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )
      )}

      {/* Tab: Executions */}
      {activeTab === 'executions' && (
        executions.length === 0 ? (
          <div className="flex flex-col items-center py-12 text-slate-400 gap-2">
            <Layers size={32} strokeWidth={1} />
            <p className="text-sm">No workflow executions yet</p>
          </div>
        ) : (
          <div className="rounded-2xl bg-white border border-slate-200 shadow-sm overflow-hidden">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-slate-100 bg-slate-50">
                  {['Action', 'Status', 'Records', 'Started At', 'Duration'].map(h => (
                    <th key={h} className="px-5 py-2.5 text-left text-xs font-semibold text-slate-500">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {executions.map(e => (
                  <tr
                    key={e.id}
                    className="border-b border-slate-50 hover:bg-slate-50 cursor-pointer"
                    onClick={() => navigate(`/executions/${e.id}`)}
                  >
                    <td className="px-5 py-2.5 font-medium text-slate-800">{e.actionName}</td>
                    <td className="px-5 py-2.5">
                      <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ${EXEC_STATUS_BADGE[e.status] ?? ''}`}>
                        {e.status}
                      </span>
                    </td>
                    <td className="px-5 py-2.5 text-slate-700">{e.totalRecordsProcessed}</td>
                    <td className="px-5 py-2.5 text-slate-500 text-xs">{formatDt(e.startedAt)}</td>
                    <td className="px-5 py-2.5 text-slate-500 text-xs">
                      {e.durationMs != null ? `${(e.durationMs / 1000).toFixed(1)}s` : '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )
      )}
    </div>
  )
}

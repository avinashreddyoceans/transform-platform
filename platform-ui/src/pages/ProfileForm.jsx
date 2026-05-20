import { useState, useEffect } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { ArrowLeft, Loader2 } from 'lucide-react'
import { Breadcrumb } from '../App'
import ProfileConfigForm from '../ProfileConfigForm'
import { createProfile, updateProfile, getProfile } from '../api/profiles'
import { ApiError } from '../api/client'

// ─────────────────────────────────────────────────────────────────────────────
// ProfileForm page — wraps ProfileConfigForm inside the app layout
// ─────────────────────────────────────────────────────────────────────────────

export default function ProfileForm() {
  const navigate = useNavigate()
  const { id } = useParams()
  const isEditing = Boolean(id)

  const [initialData, setInitialData] = useState(null)
  const [loadError, setLoadError]     = useState(null)
  const [loading, setLoading]         = useState(isEditing)
  const [saving, setSaving]           = useState(false)
  const [saveError, setSaveError]     = useState(null)

  // ── Load existing profile for edit ─────────────────────────────────────────

  useEffect(() => {
    if (!isEditing) return
    setLoading(true)
    getProfile(id)
      .then(data => { setInitialData(data); setLoading(false) })
      .catch(err => {
        setLoadError(err instanceof ApiError ? err.message : 'Failed to load profile')
        setLoading(false)
      })
  }, [id, isEditing])

  // ── Save handler ────────────────────────────────────────────────────────────

  const handleSave = async (profileData) => {
    setSaving(true)
    setSaveError(null)
    try {
      if (isEditing) {
        await updateProfile(id, profileData)
      } else {
        await createProfile(profileData)
      }
      navigate('/profiles')
    } catch (err) {
      setSaveError(err instanceof ApiError ? err.message : 'Save failed. Please try again.')
      setSaving(false)
    }
  }

  // ── Render ──────────────────────────────────────────────────────────────────

  return (
    <div className="p-6 space-y-4 max-w-5xl">
      {/* Header */}
      <div className="space-y-1">
        <Breadcrumb crumbs={['Profiles', isEditing ? 'Edit Profile' : 'New Profile']} />
        <div className="flex items-center gap-3">
          <button
            onClick={() => navigate('/profiles')}
            className="flex items-center gap-1.5 rounded-lg border border-slate-200 bg-white px-3 py-2
              text-sm font-medium text-slate-600 hover:bg-slate-50 transition"
          >
            <ArrowLeft size={14} /> Back
          </button>
          <h1 className="text-xl font-bold text-slate-800">
            {isEditing ? 'Edit Profile' : 'New Profile'}
          </h1>
        </div>
      </div>

      {/* Loading state */}
      {loading && (
        <div className="flex items-center justify-center py-20 text-slate-400 gap-2">
          <Loader2 size={20} className="animate-spin" />
          <span className="text-sm">Loading profile…</span>
        </div>
      )}

      {/* Load error */}
      {loadError && (
        <div className="rounded-xl bg-rose-50 border border-rose-200 px-4 py-3 text-sm text-rose-700">
          {loadError}
        </div>
      )}

      {/* Save error */}
      {saveError && (
        <div className="rounded-xl bg-rose-50 border border-rose-200 px-4 py-3 text-sm text-rose-700">
          {saveError}
        </div>
      )}

      {/* The form */}
      {!loading && !loadError && (
        <ProfileConfigForm
          profileId={id}
          initialData={initialData}
          saving={saving}
          onSave={handleSave}
          onCancel={() => navigate('/profiles')}
        />
      )}
    </div>
  )
}

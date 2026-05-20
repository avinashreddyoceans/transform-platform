import { Routes, Route, NavLink, Navigate } from 'react-router-dom'
import {
  LayoutDashboard, ScrollText, Settings,
  CalendarClock, Layers, ChevronRight, Zap,
} from 'lucide-react'
import ProfileList    from './pages/ProfileList'
import ProfileForm    from './pages/ProfileForm'
import Dashboard      from './pages/Dashboard'
import WindowList     from './pages/WindowList'
import WindowDetail   from './pages/WindowDetail'
import ExecutionList  from './pages/ExecutionList'
import ExecutionDetail from './pages/ExecutionDetail'
import AiChat         from './components/AiChat'

// ─────────────────────────────────────────────────────────────────────────────
// Sidebar nav link
// ─────────────────────────────────────────────────────────────────────────────

const NavItem = ({ to, icon: Icon, label, end = false }) => (
  <NavLink
    to={to}
    end={end}
    className={({ isActive }) =>
      `flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium transition-all
       ${isActive
         ? 'bg-indigo-600 text-white shadow-sm shadow-indigo-200'
         : 'text-slate-500 hover:bg-slate-100 hover:text-slate-800'}`
    }
  >
    <Icon size={17} />
    {label}
  </NavLink>
)

// ─────────────────────────────────────────────────────────────────────────────
// Sidebar
// ─────────────────────────────────────────────────────────────────────────────

const Sidebar = () => (
  <aside className="flex h-screen w-56 flex-col border-r border-slate-200 bg-white px-3 py-5 fixed left-0 top-0 z-10">
    {/* Logo */}
    <div className="mb-6 flex items-center gap-2.5 px-2">
      <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-indigo-600 shadow">
        <Zap size={16} className="text-white" />
      </div>
      <div>
        <p className="text-sm font-bold text-slate-800 leading-none">Transform</p>
        <p className="text-xs text-slate-400 leading-none mt-0.5">Platform</p>
      </div>
    </div>

    {/* Navigation */}
    <nav className="flex flex-col gap-1">
      <NavItem to="/"           icon={LayoutDashboard} label="Dashboard"  end />
      <NavItem to="/profiles"   icon={ScrollText}       label="Profiles" />
      <NavItem to="/windows"    icon={CalendarClock}    label="Windows" />
      <NavItem to="/executions" icon={Layers}           label="Executions" />
      <NavItem to="/settings"   icon={Settings}         label="Settings" />
    </nav>

    {/* Footer */}
    <div className="mt-auto pt-4 border-t border-slate-100">
      <div className="flex items-center gap-2 px-2">
        <div className="h-7 w-7 rounded-full bg-indigo-100 flex items-center justify-center">
          <span className="text-xs font-bold text-indigo-600">A</span>
        </div>
        <div className="min-w-0">
          <p className="text-xs font-medium text-slate-700 truncate">Admin</p>
          <p className="text-xs text-slate-400 truncate">v1.0.0-SNAPSHOT</p>
        </div>
      </div>
    </div>
  </aside>
)

// ─────────────────────────────────────────────────────────────────────────────
// Breadcrumb
// ─────────────────────────────────────────────────────────────────────────────

const Breadcrumb = ({ crumbs }) => (
  <nav className="flex items-center gap-1.5 text-sm text-slate-400">
    {crumbs.map((c, i) => (
      <span key={i} className="flex items-center gap-1.5">
        {i > 0 && <ChevronRight size={13} />}
        <span className={i === crumbs.length - 1 ? 'font-medium text-slate-700' : ''}>
          {c}
        </span>
      </span>
    ))}
  </nav>
)

// ─────────────────────────────────────────────────────────────────────────────
// Placeholder page
// ─────────────────────────────────────────────────────────────────────────────

const PlaceholderPage = ({ title, icon: Icon }) => (
  <div className="flex flex-col items-center justify-center h-64 text-slate-400 gap-3">
    <Icon size={40} strokeWidth={1} />
    <p className="text-lg font-medium">{title}</p>
    <p className="text-sm">Coming soon</p>
  </div>
)

// ─────────────────────────────────────────────────────────────────────────────
// Main layout + routing
// ─────────────────────────────────────────────────────────────────────────────

export default function App() {
  return (
    <div className="flex min-h-screen bg-slate-50">
      <Sidebar />

      {/* Main content — offset for fixed sidebar */}
      <main className="ml-56 flex-1 overflow-y-auto">
        <Routes>
          <Route path="/"                      element={<Dashboard />} />
          <Route path="/profiles"              element={<ProfileList />} />
          <Route path="/profiles/new"          element={<ProfileForm />} />
          <Route path="/profiles/:id/edit"     element={<ProfileForm />} />
          <Route path="/windows"               element={<WindowList />} />
          <Route path="/windows/:id"           element={<WindowDetail />} />
          <Route path="/executions"            element={<ExecutionList />} />
          <Route path="/executions/:id"        element={<ExecutionDetail />} />
          <Route path="/settings"              element={<PlaceholderPage title="Settings" icon={Settings} />} />
          <Route path="*"                      element={<Navigate to="/" replace />} />
        </Routes>
      </main>

      {/* AI Assistant — floating overlay, rendered outside main layout */}
      <AiChat />
    </div>
  )
}

export { Breadcrumb }

import { useState, useEffect } from "react";
import {
  Clock, FileText, Hash, Timer, Layers, Hand,
  Plus, Trash2, ChevronDown, ChevronUp, Check,
  AlertCircle, Eye, Settings, Zap, Copy, GripVertical,
  ArrowRight, Info, RefreshCw, X
} from "lucide-react";

// ─────────────────────────────────────────────────────────────────────────────
// Constants
// ─────────────────────────────────────────────────────────────────────────────

const TRIGGER_TYPES = [
  { id: "TIME_BASED",   label: "Time Based",   icon: Clock,   color: "indigo", desc: "Opens/closes on a cron schedule" },
  { id: "FILE_ARRIVAL", label: "File Arrival", icon: FileText, color: "teal",  desc: "Triggers when a file arrives on an integration" },
  { id: "EVENT_COUNT",  label: "Event Count",  icon: Hash,    color: "violet", desc: "Fires after N events are received" },
  { id: "SESSION_GAP",  label: "Session Gap",  icon: Timer,   color: "amber",  desc: "Closes after a period of inactivity" },
  { id: "COMPOUND",     label: "Compound",     icon: Layers,  color: "rose",   desc: "Multiple conditions combined with AND / OR" },
  { id: "MANUAL",       label: "Manual",       icon: Hand,    color: "slate",  desc: "Opened explicitly via API or admin UI" },
];

const ACTION_CONDITIONS = [
  { id: "ON_OPEN",               label: "On Window Open",       desc: "Runs when the window transitions to OPEN" },
  { id: "ON_CLOSING",            label: "On Window Closing",    desc: "Main action chain — runs when close trigger fires" },
  { id: "ON_EMPTY_CLOSE",        label: "On Empty Close",       desc: "Runs when the window closes with no data collected" },
  { id: "RECURRING_WHILE_OPEN",  label: "Recurring While Open", desc: "Runs on a schedule while the window is open" },
  { id: "ON_THRESHOLD_REACHED",  label: "On Threshold Reached", desc: "Runs when the event count threshold is hit" },
  { id: "ON_ERROR",              label: "On Error",             desc: "Error handler — runs when another action fails" },
  { id: "ON_FILE_ARRIVED",       label: "On File Arrived",      desc: "Runs each time a new file lands during the window" },
  { id: "ON_LATE_EVENT",         label: "On Late Event",        desc: "Runs when an event arrives after the window closed" },
];

const STEP_TYPES = [
  { id: "PARSE_FILE",            label: "Parse File",           group: "Input" },
  { id: "VALIDATE",              label: "Validate",             group: "Quality" },
  { id: "CORRECT",               label: "Correct",              group: "Quality" },
  { id: "DEDUPLICATE",           label: "Deduplicate",          group: "Quality" },
  { id: "VALIDATE_COMPLETENESS", label: "Validate Completeness",group: "Quality" },
  { id: "MERGE_SOURCES",         label: "Merge Sources",        group: "Transform" },
  { id: "TRANSFORM_RECORD",      label: "Transform Record",     group: "Transform" },
  { id: "MAP_TO_NOTIFICATION",   label: "Map to Notification",  group: "Transform" },
  { id: "GENERATE_FILE",         label: "Generate File",        group: "Output" },
  { id: "DELIVER_FILE",          label: "Deliver File",         group: "Output" },
  { id: "ARCHIVE",               label: "Archive",              group: "Output" },
  { id: "NOTIFY",                label: "Notify",               group: "Output" },
  { id: "INVOKE_EXTERNAL",       label: "Invoke External",      group: "Output" },
];

const LATE_EVENT_OPTS = [
  { id: "ACCEPT_INTO_NEXT_WINDOW", label: "Accept into next window" },
  { id: "REFIRE_ACTION_CHAIN",     label: "Re-fire action chain" },
  { id: "ROUTE_TO_DEAD_LETTER",    label: "Route to dead-letter" },
  { id: "REJECT",                  label: "Reject" },
];

const TIMEZONES = ["UTC", "America/New_York", "America/Chicago", "America/Los_Angeles",
  "Europe/London", "Europe/Berlin", "Asia/Tokyo", "Asia/Singapore", "Australia/Sydney"];

const COLOR_MAP = {
  indigo: "bg-indigo-50 border-indigo-300 text-indigo-700 hover:bg-indigo-100",
  teal:   "bg-teal-50 border-teal-300 text-teal-700 hover:bg-teal-100",
  violet: "bg-violet-50 border-violet-300 text-violet-700 hover:bg-violet-100",
  amber:  "bg-amber-50 border-amber-300 text-amber-700 hover:bg-amber-100",
  rose:   "bg-rose-50 border-rose-300 text-rose-700 hover:bg-rose-100",
  slate:  "bg-slate-50 border-slate-300 text-slate-600 hover:bg-slate-100",
};

const SELECTED_COLOR_MAP = {
  indigo: "bg-indigo-600 border-indigo-600 text-white",
  teal:   "bg-teal-600 border-teal-600 text-white",
  violet: "bg-violet-600 border-violet-600 text-white",
  amber:  "bg-amber-600 border-amber-600 text-white",
  rose:   "bg-rose-600 border-rose-600 text-white",
  slate:  "bg-slate-600 border-slate-600 text-white",
};

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

const uid = () => Math.random().toString(36).slice(2, 9);

const defaultTrigger = (type) => {
  switch (type) {
    case "TIME_BASED":   return { type, openCron: "0 8 * * 1-5", closeCron: "", windowDuration: "", timeZone: "UTC" };
    case "FILE_ARRIVAL": return { type, integrationId: "", filePattern: "*.csv", pollInterval: "" };
    case "EVENT_COUNT":  return { type, threshold: 100, rearmable: false };
    case "SESSION_GAP":  return { type, inactivityGap: "PT30M", maxWindowDuration: "" };
    case "COMPOUND":     return { type, operator: "AND", subTriggers: [] };
    case "MANUAL":       return { type };
    default:             return { type };
  }
};

const defaultProfile = () => ({
  name: "",
  clientId: "",
  description: "",
  tags: [],
  windowConfig: {
    openTrigger:        defaultTrigger("TIME_BASED"),
    closeTrigger:       defaultTrigger("TIME_BASED"),
    timeSemantics:      "PROCESSING_TIME",
    eventTimestampField:"",
    allowedLateness:    "",
    lateEventBehaviour: "ACCEPT_INTO_NEXT_WINDOW",
    overlapPolicy:      "SKIP_NEW",
    allowEmptyClose:    false,
    deduplicationEnabled: true,
    maxOpenDuration:    "",
  },
  actions: [],
});

const defaultAction = () => ({
  id: uid(),
  name: "Process Data",
  condition: "ON_CLOSING",
  executionOrder: 10,
  continueOnFailure: false,
  enabled: true,
  steps: [],
});

const defaultStep = (order) => ({
  id: uid(),
  name: "",
  stepType: "PARSE_FILE",
  executionOrder: order,
  enabled: true,
  config: {},
  retryPolicy: { maxAttempts: 3, initialDelayMs: 1000, backoffMultiplier: 2.0, maxDelayMs: 30000 },
});

// ─────────────────────────────────────────────────────────────────────────────
// Shared UI atoms
// ─────────────────────────────────────────────────────────────────────────────

const Label = ({ children, hint }) => (
  <div className="mb-1">
    <span className="text-sm font-medium text-slate-700">{children}</span>
    {hint && <span className="ml-2 text-xs text-slate-400">{hint}</span>}
  </div>
);

const Input = ({ value, onChange, placeholder, className = "", type = "text" }) => (
  <input
    type={type}
    value={value}
    onChange={e => onChange(e.target.value)}
    placeholder={placeholder}
    className={`w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-800
      placeholder:text-slate-400 focus:border-indigo-400 focus:outline-none focus:ring-2
      focus:ring-indigo-100 transition ${className}`}
  />
);

const Select = ({ value, onChange, options, className = "" }) => (
  <select
    value={value}
    onChange={e => onChange(e.target.value)}
    className={`w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-800
      focus:border-indigo-400 focus:outline-none focus:ring-2 focus:ring-indigo-100
      transition ${className}`}
  >
    {options.map(o => (
      <option key={o.id ?? o} value={o.id ?? o}>{o.label ?? o}</option>
    ))}
  </select>
);

const Toggle = ({ checked, onChange, label }) => (
  <label className="flex items-center gap-2 cursor-pointer select-none">
    <div
      onClick={() => onChange(!checked)}
      className={`relative w-10 h-5 rounded-full transition-colors ${checked ? "bg-indigo-500" : "bg-slate-200"}`}
    >
      <span className={`absolute top-0.5 h-4 w-4 rounded-full bg-white shadow transition-transform
        ${checked ? "translate-x-5" : "translate-x-0.5"}`} />
    </div>
    <span className="text-sm text-slate-700">{label}</span>
  </label>
);

const Badge = ({ children, color = "indigo" }) => {
  const colors = {
    indigo: "bg-indigo-100 text-indigo-700",
    teal:   "bg-teal-100 text-teal-700",
    amber:  "bg-amber-100 text-amber-700",
    slate:  "bg-slate-100 text-slate-600",
    green:  "bg-green-100 text-green-700",
    rose:   "bg-rose-100 text-rose-700",
  };
  return <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ${colors[color]}`}>{children}</span>;
};

const SectionCard = ({ title, icon: Icon, children, accent = "indigo" }) => {
  const accents = { indigo: "border-l-indigo-500", teal: "border-l-teal-500", amber: "border-l-amber-500" };
  return (
    <div className={`rounded-xl border border-slate-200 bg-white shadow-sm border-l-4 ${accents[accent]}`}>
      {title && (
        <div className="flex items-center gap-2 border-b border-slate-100 px-4 py-3">
          {Icon && <Icon size={16} className="text-slate-500" />}
          <span className="text-sm font-semibold text-slate-700">{title}</span>
        </div>
      )}
      <div className="p-4">{children}</div>
    </div>
  );
};

// ─────────────────────────────────────────────────────────────────────────────
// Trigger type picker
// ─────────────────────────────────────────────────────────────────────────────

const TriggerTypePicker = ({ selected, onChange }) => (
  <div className="grid grid-cols-3 gap-2">
    {TRIGGER_TYPES.map(t => {
      const Icon = t.icon;
      const isSelected = selected === t.id;
      const cls = isSelected ? SELECTED_COLOR_MAP[t.color] : COLOR_MAP[t.color];
      return (
        <button
          key={t.id}
          onClick={() => onChange(t.id)}
          className={`flex flex-col items-center gap-1 rounded-xl border-2 p-3 text-center transition cursor-pointer ${cls}`}
        >
          <Icon size={18} />
          <span className="text-xs font-semibold leading-tight">{t.label}</span>
        </button>
      );
    })}
  </div>
);

// ─────────────────────────────────────────────────────────────────────────────
// Trigger config panels
// ─────────────────────────────────────────────────────────────────────────────

const TimeBasedConfig = ({ trigger, onChange, isCloseTrigger }) => {
  const upd = (k, v) => onChange({ ...trigger, [k]: v });
  const hasClose = trigger.closeCron || trigger.windowDuration;
  return (
    <div className="space-y-3">
      {!isCloseTrigger && (
        <div>
          <Label hint="Spring cron: s m h d M DOW">Open Cron</Label>
          <Input value={trigger.openCron ?? ""} onChange={v => upd("openCron", v)} placeholder="0 8 * * 1-5" />
          <p className="mt-1 text-xs text-slate-400">e.g. <code className="bg-slate-100 px-1 rounded">0 8 * * 1-5</code> = weekdays at 08:00</p>
        </div>
      )}
      {isCloseTrigger && (
        <>
          <div>
            <Label hint="optional — takes priority over duration">Close Cron</Label>
            <Input value={trigger.closeCron ?? ""} onChange={v => upd("closeCron", v)} placeholder="0 17 * * 1-5" />
          </div>
          <div>
            <Label hint="ISO-8601, e.g. PT8H — used if no close cron">Window Duration</Label>
            <Input value={trigger.windowDuration ?? ""} onChange={v => upd("windowDuration", v)} placeholder="PT8H" />
          </div>
          {!hasClose && (
            <div className="flex items-start gap-2 rounded-lg bg-indigo-50 p-3 text-xs text-indigo-700">
              <Info size={13} className="mt-0.5 shrink-0" />
              <span>Neither set — window duration will be derived from the open cron interval (full-size window).</span>
            </div>
          )}
        </>
      )}
      <div>
        <Label>Time Zone</Label>
        <Select value={trigger.timeZone ?? "UTC"} onChange={v => upd("timeZone", v)}
          options={TIMEZONES.map(tz => ({ id: tz, label: tz }))} />
      </div>
    </div>
  );
};

const FileArrivalConfig = ({ trigger, onChange }) => {
  const upd = (k, v) => onChange({ ...trigger, [k]: v });
  return (
    <div className="space-y-3">
      <div>
        <Label>Integration ID</Label>
        <Input value={trigger.integrationId ?? ""} onChange={v => upd("integrationId", v)} placeholder="SFTP_BANK_PARTNER" />
      </div>
      <div>
        <Label hint="glob pattern">File Pattern</Label>
        <Input value={trigger.filePattern ?? ""} onChange={v => upd("filePattern", v)} placeholder="*.csv" />
      </div>
      <div>
        <Label hint="e.g. PT5M — how often to poll">Poll Interval</Label>
        <Input value={trigger.pollInterval ?? ""} onChange={v => upd("pollInterval", v)} placeholder="PT5M" />
      </div>
    </div>
  );
};

const EventCountConfig = ({ trigger, onChange }) => {
  const upd = (k, v) => onChange({ ...trigger, [k]: v });
  return (
    <div className="space-y-3">
      <div>
        <Label>Event Threshold</Label>
        <Input type="number" value={trigger.threshold ?? 100} onChange={v => upd("threshold", parseInt(v) || 1)} />
      </div>
      <Toggle
        checked={trigger.rearmable ?? false}
        onChange={v => upd("rearmable", v)}
        label="Rearmable (fires every N events — micro-batching)"
      />
    </div>
  );
};

const SessionGapConfig = ({ trigger, onChange }) => {
  const upd = (k, v) => onChange({ ...trigger, [k]: v });
  return (
    <div className="space-y-3">
      <div>
        <Label hint="ISO-8601, e.g. PT30M">Inactivity Gap</Label>
        <Input value={trigger.inactivityGap ?? ""} onChange={v => upd("inactivityGap", v)} placeholder="PT30M" />
      </div>
      <div>
        <Label hint="optional — hard cap on window size">Max Window Duration</Label>
        <Input value={trigger.maxWindowDuration ?? ""} onChange={v => upd("maxWindowDuration", v)} placeholder="PT4H" />
      </div>
    </div>
  );
};

const CompoundConfig = ({ trigger, onChange }) => {
  const upd = (k, v) => onChange({ ...trigger, [k]: v });
  const addSub = () => upd("subTriggers", [...(trigger.subTriggers ?? []), defaultTrigger("TIME_BASED")]);
  const removeSub = (i) => upd("subTriggers", trigger.subTriggers.filter((_, idx) => idx !== i));
  const updateSub = (i, newSub) => {
    const subs = [...(trigger.subTriggers ?? [])];
    subs[i] = newSub;
    upd("subTriggers", subs);
  };
  return (
    <div className="space-y-3">
      <div>
        <Label>Operator</Label>
        <Select value={trigger.operator ?? "AND"} onChange={v => upd("operator", v)}
          options={[{ id: "AND", label: "AND — all conditions must fire" }, { id: "OR", label: "OR — any condition fires" }]} />
      </div>
      <div className="space-y-2">
        <div className="flex items-center justify-between">
          <span className="text-sm font-medium text-slate-700">Sub-triggers ({(trigger.subTriggers ?? []).length})</span>
          <button onClick={addSub}
            className="flex items-center gap-1 rounded-lg bg-slate-100 px-2 py-1 text-xs font-medium text-slate-600 hover:bg-slate-200">
            <Plus size={12} /> Add sub-trigger
          </button>
        </div>
        {(trigger.subTriggers ?? []).map((sub, i) => (
          <div key={i} className="rounded-lg border border-slate-200 p-3 space-y-2">
            <div className="flex items-center justify-between">
              <TriggerTypePicker selected={sub.type} onChange={type => updateSub(i, defaultTrigger(type))} />
              <button onClick={() => removeSub(i)} className="ml-2 text-rose-400 hover:text-rose-600">
                <X size={14} />
              </button>
            </div>
            <TriggerConfigPanel trigger={sub} onChange={sub => updateSub(i, sub)} isCloseTrigger={false} />
          </div>
        ))}
      </div>
    </div>
  );
};

const TriggerConfigPanel = ({ trigger, onChange, isCloseTrigger }) => {
  switch (trigger.type) {
    case "TIME_BASED":   return <TimeBasedConfig trigger={trigger} onChange={onChange} isCloseTrigger={isCloseTrigger} />;
    case "FILE_ARRIVAL": return <FileArrivalConfig trigger={trigger} onChange={onChange} />;
    case "EVENT_COUNT":  return <EventCountConfig trigger={trigger} onChange={onChange} />;
    case "SESSION_GAP":  return <SessionGapConfig trigger={trigger} onChange={onChange} />;
    case "COMPOUND":     return <CompoundConfig trigger={trigger} onChange={onChange} />;
    case "MANUAL":       return <div className="rounded-lg bg-slate-50 p-3 text-xs text-slate-500">No configuration — this window opens only via explicit API call or admin trigger.</div>;
    default:             return null;
  }
};

// ─────────────────────────────────────────────────────────────────────────────
// Trigger section (type picker + config)
// ─────────────────────────────────────────────────────────────────────────────

const TriggerSection = ({ title, trigger, onChange, isCloseTrigger = false }) => {
  const meta = TRIGGER_TYPES.find(t => t.id === trigger.type);
  return (
    <div className="space-y-3">
      <div className="flex items-center gap-2">
        <span className="text-xs font-semibold uppercase tracking-wide text-slate-500">{title}</span>
        {meta && <Badge color={meta.color === "indigo" ? "indigo" : meta.color === "teal" ? "teal" : "slate"}>{meta.label}</Badge>}
      </div>
      <TriggerTypePicker
        selected={trigger.type}
        onChange={type => onChange(defaultTrigger(type))}
      />
      {meta && <p className="text-xs text-slate-400">{meta.desc}</p>}
      <TriggerConfigPanel trigger={trigger} onChange={onChange} isCloseTrigger={isCloseTrigger} />
    </div>
  );
};

// ─────────────────────────────────────────────────────────────────────────────
// Step card
// ─────────────────────────────────────────────────────────────────────────────

const StepCard = ({ step, index, onChange, onDelete }) => {
  const [expanded, setExpanded] = useState(false);
  const upd = (k, v) => onChange({ ...step, [k]: v });
  const updRetry = (k, v) => onChange({ ...step, retryPolicy: { ...step.retryPolicy, [k]: v } });

  const grouped = STEP_TYPES.reduce((acc, s) => {
    (acc[s.group] = acc[s.group] || []).push(s);
    return acc;
  }, {});

  return (
    <div className={`rounded-xl border transition-all ${step.enabled ? "border-slate-200 bg-white" : "border-slate-100 bg-slate-50 opacity-60"}`}>
      <div className="flex items-center gap-2 px-3 py-2">
        <GripVertical size={14} className="text-slate-300 cursor-grab shrink-0" />
        <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-indigo-100 text-xs font-bold text-indigo-600">{index + 1}</span>
        <div className="flex-1 min-w-0">
          <input
            className="w-full text-sm font-medium text-slate-800 bg-transparent border-none outline-none placeholder:text-slate-300"
            value={step.name}
            onChange={e => upd("name", e.target.value)}
            placeholder="Step name…"
          />
        </div>
        <Badge color="slate">{STEP_TYPES.find(s => s.id === step.stepType)?.label ?? step.stepType}</Badge>
        <Toggle checked={step.enabled} onChange={v => upd("enabled", v)} label="" />
        <button onClick={() => setExpanded(e => !e)} className="text-slate-400 hover:text-slate-600">
          {expanded ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
        </button>
        <button onClick={onDelete} className="text-rose-300 hover:text-rose-500">
          <Trash2 size={14} />
        </button>
      </div>

      {expanded && (
        <div className="border-t border-slate-100 px-3 pb-3 pt-3 space-y-3">
          <div className="grid grid-cols-2 gap-3">
            <div>
              <Label>Step Type</Label>
              <select
                value={step.stepType}
                onChange={e => upd("stepType", e.target.value)}
                className="w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm focus:border-indigo-400 focus:outline-none"
              >
                {Object.entries(grouped).map(([group, steps]) => (
                  <optgroup key={group} label={group}>
                    {steps.map(s => <option key={s.id} value={s.id}>{s.label}</option>)}
                  </optgroup>
                ))}
              </select>
            </div>
            <div>
              <Label>Execution Order</Label>
              <Input type="number" value={step.executionOrder} onChange={v => upd("executionOrder", parseInt(v) || 0)} />
            </div>
          </div>

          <div className="rounded-lg bg-slate-50 p-3 space-y-2">
            <span className="text-xs font-semibold text-slate-500">Retry Policy</span>
            <div className="grid grid-cols-2 gap-2">
              <div>
                <Label hint="attempts">Max Attempts</Label>
                <Input type="number" value={step.retryPolicy.maxAttempts} onChange={v => updRetry("maxAttempts", parseInt(v) || 1)} />
              </div>
              <div>
                <Label hint="ms">Initial Delay</Label>
                <Input type="number" value={step.retryPolicy.initialDelayMs} onChange={v => updRetry("initialDelayMs", parseInt(v) || 0)} />
              </div>
              <div>
                <Label>Backoff Multiplier</Label>
                <Input type="number" value={step.retryPolicy.backoffMultiplier} onChange={v => updRetry("backoffMultiplier", parseFloat(v) || 1)} />
              </div>
              <div>
                <Label hint="ms">Max Delay</Label>
                <Input type="number" value={step.retryPolicy.maxDelayMs} onChange={v => updRetry("maxDelayMs", parseInt(v) || 0)} />
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

// ─────────────────────────────────────────────────────────────────────────────
// Action card
// ─────────────────────────────────────────────────────────────────────────────

const ActionCard = ({ action, index, onChange, onDelete }) => {
  const [expanded, setExpanded] = useState(true);
  const upd = (k, v) => onChange({ ...action, [k]: v });

  const addStep = () => upd("steps", [...action.steps, defaultStep((action.steps.length + 1) * 10)]);
  const updateStep = (i, s) => upd("steps", action.steps.map((st, idx) => idx === i ? s : st));
  const deleteStep = (i) => upd("steps", action.steps.filter((_, idx) => idx !== i));

  const condMeta = ACTION_CONDITIONS.find(c => c.id === action.condition);
  const condColors = {
    ON_CLOSING: "teal", ON_OPEN: "indigo", ON_ERROR: "rose",
    ON_EMPTY_CLOSE: "amber", RECURRING_WHILE_OPEN: "violet",
    ON_THRESHOLD_REACHED: "violet", ON_FILE_ARRIVED: "teal", ON_LATE_EVENT: "amber"
  };
  const badgeColor = condColors[action.condition] ?? "slate";

  return (
    <div className={`rounded-xl border-2 transition-all ${action.enabled ? "border-slate-200" : "border-dashed border-slate-200 opacity-60"}`}>
      {/* Action header */}
      <div className="flex items-center gap-2 bg-slate-50 rounded-t-xl px-4 py-3">
        <span className="flex h-6 w-6 items-center justify-center rounded-full bg-indigo-600 text-xs font-bold text-white shrink-0">
          {index + 1}
        </span>
        <input
          className="flex-1 min-w-0 text-sm font-semibold text-slate-800 bg-transparent border-none outline-none"
          value={action.name}
          onChange={e => upd("name", e.target.value)}
        />
        <Badge color={badgeColor}>{condMeta?.label ?? action.condition}</Badge>
        <Toggle checked={action.enabled} onChange={v => upd("enabled", v)} label="" />
        <button onClick={() => setExpanded(e => !e)} className="text-slate-400 hover:text-slate-600">
          {expanded ? <ChevronUp size={16} /> : <ChevronDown size={16} />}
        </button>
        <button onClick={onDelete} className="text-rose-300 hover:text-rose-500 ml-1">
          <Trash2 size={15} />
        </button>
      </div>

      {expanded && (
        <div className="p-4 space-y-4">
          <div className="grid grid-cols-3 gap-3">
            <div className="col-span-2">
              <Label>Trigger Condition</Label>
              <Select value={action.condition} onChange={v => upd("condition", v)}
                options={ACTION_CONDITIONS} />
              {condMeta && <p className="mt-1 text-xs text-slate-400">{condMeta.desc}</p>}
            </div>
            <div>
              <Label hint="lower runs first">Execution Order</Label>
              <Input type="number" value={action.executionOrder} onChange={v => upd("executionOrder", parseInt(v) || 0)} />
            </div>
          </div>
          <Toggle checked={action.continueOnFailure} onChange={v => upd("continueOnFailure", v)}
            label="Continue on step failure (non-fatal errors)" />

          {/* Steps */}
          <div className="space-y-2">
            <div className="flex items-center justify-between">
              <span className="text-sm font-semibold text-slate-700">
                Workflow Steps <span className="ml-1 text-xs font-normal text-slate-400">({action.steps.length})</span>
              </span>
              <button onClick={addStep}
                className="flex items-center gap-1 rounded-lg bg-indigo-50 px-2.5 py-1 text-xs font-medium text-indigo-600 hover:bg-indigo-100 transition">
                <Plus size={12} /> Add Step
              </button>
            </div>
            {action.steps.length === 0 && (
              <div className="rounded-xl border-2 border-dashed border-slate-200 py-6 text-center">
                <Zap size={20} className="mx-auto mb-1 text-slate-300" />
                <p className="text-xs text-slate-400">No steps yet — click Add Step</p>
              </div>
            )}
            <div className="space-y-2">
              {action.steps.map((step, i) => (
                <StepCard key={step.id} step={step} index={i}
                  onChange={s => updateStep(i, s)}
                  onDelete={() => deleteStep(i)} />
              ))}
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

// ─────────────────────────────────────────────────────────────────────────────
// Tab: Basic Info
// ─────────────────────────────────────────────────────────────────────────────

const BasicInfoTab = ({ profile, onChange }) => {
  const upd = (k, v) => onChange({ ...profile, [k]: v });

  const addTag = () => upd("tags", [...profile.tags, { key: "", value: "" }]);
  const updateTag = (i, k, v) => upd("tags", profile.tags.map((t, idx) => idx === i ? { ...t, [k]: v } : t));
  const removeTag = (i) => upd("tags", profile.tags.filter((_, idx) => idx !== i));

  return (
    <div className="space-y-4">
      <SectionCard title="Profile Identity" icon={Settings}>
        <div className="space-y-3">
          <div className="grid grid-cols-2 gap-4">
            <div>
              <Label>Profile Name <span className="text-rose-400">*</span></Label>
              <Input value={profile.name} onChange={v => upd("name", v)} placeholder="Daily ACH Processing" />
            </div>
            <div>
              <Label>Client ID <span className="text-rose-400">*</span></Label>
              <Input value={profile.clientId} onChange={v => upd("clientId", v)} placeholder="CLIENT_BANK_A" />
            </div>
          </div>
          <div>
            <Label hint="optional">Description</Label>
            <textarea
              value={profile.description}
              onChange={e => upd("description", e.target.value)}
              placeholder="Processes daily ACH file from Bank A — validates, deduplicates, and delivers to core banking…"
              rows={3}
              className="w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-800
                placeholder:text-slate-400 focus:border-indigo-400 focus:outline-none focus:ring-2
                focus:ring-indigo-100 transition resize-none"
            />
          </div>
        </div>
      </SectionCard>

      <SectionCard title="Tags" icon={null} accent="teal">
        <div className="space-y-2">
          {profile.tags.map((tag, i) => (
            <div key={i} className="flex items-center gap-2">
              <Input value={tag.key} onChange={v => updateTag(i, "key", v)} placeholder="Key" className="flex-1" />
              <span className="text-slate-300">=</span>
              <Input value={tag.value} onChange={v => updateTag(i, "value", v)} placeholder="Value" className="flex-1" />
              <button onClick={() => removeTag(i)} className="text-rose-300 hover:text-rose-500 shrink-0">
                <X size={14} />
              </button>
            </div>
          ))}
          <button onClick={addTag}
            className="flex items-center gap-1 rounded-lg border border-dashed border-slate-300 px-3 py-1.5 text-xs font-medium text-slate-500 hover:bg-slate-50 w-full justify-center transition">
            <Plus size={12} /> Add Tag
          </button>
        </div>
      </SectionCard>
    </div>
  );
};

// ─────────────────────────────────────────────────────────────────────────────
// Tab: Window Config
// ─────────────────────────────────────────────────────────────────────────────

const WindowConfigTab = ({ windowConfig, onChange }) => {
  const upd = (k, v) => onChange({ ...windowConfig, [k]: v });

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-2 gap-4">
        <SectionCard title="Open Trigger" icon={Zap}>
          <TriggerSection
            title="When to open"
            trigger={windowConfig.openTrigger}
            onChange={t => upd("openTrigger", t)}
            isCloseTrigger={false}
          />
        </SectionCard>
        <SectionCard title="Close Trigger" icon={Timer}>
          <TriggerSection
            title="When to close"
            trigger={windowConfig.closeTrigger}
            onChange={t => upd("closeTrigger", t)}
            isCloseTrigger={true}
          />
        </SectionCard>
      </div>

      <SectionCard title="Advanced Settings" icon={Settings} accent="amber">
        <div className="grid grid-cols-2 gap-4">
          <div>
            <Label>Time Semantics</Label>
            <Select value={windowConfig.timeSemantics} onChange={v => upd("timeSemantics", v)}
              options={[
                { id: "PROCESSING_TIME", label: "Processing Time — use arrival time" },
                { id: "EVENT_TIME",      label: "Event Time — use embedded timestamp" },
              ]} />
          </div>
          {windowConfig.timeSemantics === "EVENT_TIME" && (
            <div>
              <Label hint="JSON field name">Event Timestamp Field</Label>
              <Input value={windowConfig.eventTimestampField ?? ""} onChange={v => upd("eventTimestampField", v)} placeholder="eventTime" />
            </div>
          )}
          <div>
            <Label hint="ISO-8601 e.g. PT10M">Allowed Lateness</Label>
            <Input value={windowConfig.allowedLateness ?? ""} onChange={v => upd("allowedLateness", v)} placeholder="PT10M" />
          </div>
          <div>
            <Label>Late Event Behaviour</Label>
            <Select value={windowConfig.lateEventBehaviour} onChange={v => upd("lateEventBehaviour", v)}
              options={LATE_EVENT_OPTS} />
          </div>
          <div>
            <Label>Overlap Policy</Label>
            <Select value={windowConfig.overlapPolicy} onChange={v => upd("overlapPolicy", v)}
              options={[
                { id: "SKIP_NEW",             label: "Skip New — keep existing window" },
                { id: "FORCE_CLOSE_EXISTING", label: "Force Close Existing — open new" },
              ]} />
          </div>
          <div>
            <Label hint="hard cap e.g. PT12H">Max Open Duration</Label>
            <Input value={windowConfig.maxOpenDuration ?? ""} onChange={v => upd("maxOpenDuration", v)} placeholder="PT12H" />
          </div>
        </div>
        <div className="mt-4 flex gap-6">
          <Toggle checked={windowConfig.deduplicationEnabled} onChange={v => upd("deduplicationEnabled", v)} label="Enable deduplication" />
          <Toggle checked={windowConfig.allowEmptyClose} onChange={v => upd("allowEmptyClose", v)} label="Allow empty window close" />
        </div>
      </SectionCard>
    </div>
  );
};

// ─────────────────────────────────────────────────────────────────────────────
// Tab: Actions
// ─────────────────────────────────────────────────────────────────────────────

const ActionsTab = ({ actions, onChange }) => {
  const addAction = () => onChange([...actions, defaultAction()]);
  const updateAction = (i, a) => onChange(actions.map((ac, idx) => idx === i ? a : ac));
  const deleteAction = (i) => onChange(actions.filter((_, idx) => idx !== i));

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h3 className="text-sm font-semibold text-slate-800">Action Chains</h3>
          <p className="text-xs text-slate-400">Each action runs when its trigger condition is met. Steps execute in order.</p>
        </div>
        <button onClick={addAction}
          className="flex items-center gap-1.5 rounded-xl bg-indigo-600 px-3 py-2 text-sm font-medium text-white hover:bg-indigo-700 transition shadow-sm">
          <Plus size={14} /> Add Action
        </button>
      </div>

      {actions.length === 0 && (
        <div className="rounded-2xl border-2 border-dashed border-slate-200 py-12 text-center">
          <Layers size={32} className="mx-auto mb-3 text-slate-200" />
          <p className="text-sm font-medium text-slate-400">No actions configured</p>
          <p className="text-xs text-slate-300 mt-1">Add at least one ON_CLOSING action to process data when the window closes</p>
          <button onClick={addAction}
            className="mt-4 flex items-center gap-1.5 rounded-xl bg-indigo-50 px-4 py-2 text-sm font-medium text-indigo-600 hover:bg-indigo-100 transition mx-auto">
            <Plus size={14} /> Add first action
          </button>
        </div>
      )}

      <div className="space-y-3">
        {actions.map((action, i) => (
          <ActionCard key={action.id} action={action} index={i}
            onChange={a => updateAction(i, a)}
            onDelete={() => deleteAction(i)} />
        ))}
      </div>
    </div>
  );
};

// ─────────────────────────────────────────────────────────────────────────────
// Tab: Review
// ─────────────────────────────────────────────────────────────────────────────

const ReviewTab = ({ profile }) => {
  const [copied, setCopied] = useState(false);

  const clean = (obj) => {
    if (!obj || typeof obj !== "object") return obj;
    if (Array.isArray(obj)) return obj.map(clean).filter(v => v !== null && v !== undefined);
    return Object.fromEntries(
      Object.entries(obj)
        .filter(([, v]) => v !== "" && v !== null && v !== undefined)
        .map(([k, v]) => [k, clean(v)])
    );
  };

  const json = JSON.stringify(clean(profile), null, 2);

  const errors = [];
  if (!profile.name)     errors.push("Profile name is required");
  if (!profile.clientId) errors.push("Client ID is required");
  if (!profile.windowConfig?.openTrigger) errors.push("Open trigger is required");
  if (profile.windowConfig?.openTrigger?.type === "TIME_BASED" && !profile.windowConfig?.openTrigger?.openCron)
    errors.push("Open cron expression is required for TIME_BASED trigger");

  const warnings = [];
  if (profile.actions.length === 0)
    warnings.push("No actions configured — window will open and close without processing data");
  if (!profile.actions.some(a => a.condition === "ON_CLOSING"))
    warnings.push("No ON_CLOSING action — data collected during the window won't be processed at close");

  const copy = () => {
    navigator.clipboard.writeText(json).then(() => { setCopied(true); setTimeout(() => setCopied(false), 2000); });
  };

  return (
    <div className="space-y-4">
      {errors.length > 0 && (
        <div className="rounded-xl bg-rose-50 border border-rose-200 p-4 space-y-1">
          <div className="flex items-center gap-2 text-rose-700 font-semibold text-sm mb-2">
            <AlertCircle size={15} /> Validation Errors
          </div>
          {errors.map((e, i) => <p key={i} className="text-xs text-rose-600 ml-5">• {e}</p>)}
        </div>
      )}
      {warnings.length > 0 && (
        <div className="rounded-xl bg-amber-50 border border-amber-200 p-4 space-y-1">
          <div className="flex items-center gap-2 text-amber-700 font-semibold text-sm mb-2">
            <Info size={15} /> Warnings
          </div>
          {warnings.map((w, i) => <p key={i} className="text-xs text-amber-600 ml-5">• {w}</p>)}
        </div>
      )}

      <div className="rounded-xl border border-slate-200 bg-slate-900 overflow-hidden">
        <div className="flex items-center justify-between border-b border-slate-700 px-4 py-2.5">
          <span className="text-xs font-medium text-slate-400">Profile Config — JSON Preview</span>
          <button onClick={copy}
            className="flex items-center gap-1 rounded-lg bg-slate-700 px-2.5 py-1 text-xs font-medium text-slate-300 hover:bg-slate-600 transition">
            {copied ? <><Check size={11} /> Copied</> : <><Copy size={11} /> Copy</>}
          </button>
        </div>
        <pre className="overflow-auto p-4 text-xs text-slate-300 leading-relaxed max-h-96">
          {json}
        </pre>
      </div>

      <div className="flex gap-3">
        {onCancel && (
          <button
            onClick={onCancel}
            className="flex-1 rounded-xl border border-slate-200 bg-white py-3 text-sm font-semibold text-slate-600 hover:bg-slate-50 transition">
            Cancel
          </button>
        )}
        {errors.length === 0 && (
          <button
            onClick={() => onSave?.(profile)}
            className="flex-1 rounded-xl bg-indigo-600 py-3 text-sm font-semibold text-white hover:bg-indigo-700 transition shadow-md">
            Save Profile
          </button>
        )}
      </div>
    </div>
  );
};

// ─────────────────────────────────────────────────────────────────────────────
// Stepper header
// ─────────────────────────────────────────────────────────────────────────────

const TABS = [
  { id: "basic",   label: "Basic Info",    icon: Settings },
  { id: "window",  label: "Window Config", icon: Clock },
  { id: "actions", label: "Actions",       icon: Layers },
  { id: "review",  label: "Review",        icon: Eye },
];

const StepperHeader = ({ active, onChange, profile }) => {
  const getStatus = (id) => {
    const idx = TABS.findIndex(t => t.id === id);
    const activeIdx = TABS.findIndex(t => t.id === active);
    if (idx < activeIdx) return "done";
    if (idx === activeIdx) return "active";
    return "pending";
  };

  return (
    <div className="flex items-center gap-0 border-b border-slate-100 bg-white px-6 pt-4 pb-0">
      {TABS.map((tab, i) => {
        const Icon = tab.icon;
        const status = getStatus(tab.id);
        return (
          <div key={tab.id} className="flex items-center">
            <button
              onClick={() => onChange(tab.id)}
              className={`flex items-center gap-2 px-4 pb-3 text-sm font-medium border-b-2 transition-colors
                ${status === "active"  ? "border-indigo-500 text-indigo-600"
                : status === "done"   ? "border-transparent text-slate-500 hover:text-slate-700"
                :                       "border-transparent text-slate-400 hover:text-slate-500"}`}
            >
              <span className={`flex h-5 w-5 items-center justify-center rounded-full text-xs font-bold
                ${status === "active" ? "bg-indigo-600 text-white"
                : status === "done"  ? "bg-green-500 text-white"
                :                      "bg-slate-200 text-slate-500"}`}
              >
                {status === "done" ? <Check size={10} /> : i + 1}
              </span>
              {tab.label}
            </button>
            {i < TABS.length - 1 && <ArrowRight size={14} className="text-slate-200 mx-1" />}
          </div>
        );
      })}
    </div>
  );
};

// ─────────────────────────────────────────────────────────────────────────────
// Main component
// ─────────────────────────────────────────────────────────────────────────────

export default function ProfileConfigForm({ profileId, initialData, saving, onSave, onCancel }) {
  const [activeTab, setActiveTab] = useState("basic");
  const [profile, setProfile] = useState(defaultProfile());

  // Pre-populate form when editing an existing profile
  useEffect(() => {
    if (!initialData) return;
    setProfile({
      name:        initialData.name        ?? "",
      clientId:    initialData.clientId    ?? "",
      description: initialData.description ?? "",
      tags: Object.entries(initialData.tags ?? {}).map(([k, v]) => ({ key: k, value: v })),
      windowConfig: {
        openTrigger:          initialData.windowConfig?.openTrigger        ?? defaultTrigger("TIME_BASED"),
        closeTrigger:         initialData.windowConfig?.closeTrigger       ?? defaultTrigger("TIME_BASED"),
        timeSemantics:        initialData.windowConfig?.timeSemantics      ?? "PROCESSING_TIME",
        eventTimestampField:  initialData.windowConfig?.eventTimestampField ?? "",
        allowedLateness:      initialData.windowConfig?.allowedLateness    ?? "",
        lateEventBehaviour:   initialData.windowConfig?.lateEventBehaviour ?? "ACCEPT_INTO_NEXT_WINDOW",
        overlapPolicy:        initialData.windowConfig?.overlapPolicy      ?? "SKIP_NEW",
        allowEmptyClose:      initialData.windowConfig?.allowEmptyClose    ?? false,
        deduplicationEnabled: initialData.windowConfig?.deduplicationEnabled ?? true,
        maxOpenDuration:      initialData.windowConfig?.maxOpenDuration    ?? "",
      },
      actions: (initialData.actions ?? []).map(a => ({
        id: a.id ?? uid(),
        name: a.name ?? "",
        condition: a.condition ?? "ON_CLOSING",
        executionOrder: a.executionOrder ?? 10,
        continueOnFailure: a.continueOnFailure ?? false,
        enabled: a.enabled ?? true,
        steps: (a.steps ?? []).map(s => ({
          id: s.id ?? uid(),
          name: s.name ?? "",
          stepType: s.stepType ?? "PARSE_FILE",
          executionOrder: s.executionOrder ?? 10,
          enabled: s.enabled ?? true,
          config: s.config ?? {},
          retryPolicy: s.retryPolicy ?? { maxAttempts: 3, initialDelayMs: 1000, backoffMultiplier: 2.0, maxDelayMs: 30000 },
        })),
      })),
    });
  }, [initialData]);

  const updProfile = (k, v) => setProfile(p => ({ ...p, [k]: v }));
  const updWindowConfig = (wc) => setProfile(p => ({ ...p, windowConfig: wc }));

  const goNext = () => {
    const idx = TABS.findIndex(t => t.id === activeTab);
    if (idx < TABS.length - 1) setActiveTab(TABS[idx + 1].id);
  };
  const goPrev = () => {
    const idx = TABS.findIndex(t => t.id === activeTab);
    if (idx > 0) setActiveTab(TABS[idx - 1].id);
  };

  return (
    <div className="min-h-screen bg-slate-50 p-6">
      <div className="mx-auto max-w-4xl space-y-0">
        {/* Header */}
        <div className="rounded-t-2xl bg-gradient-to-r from-indigo-600 to-indigo-500 px-6 py-5 shadow-lg">
          <div className="flex items-center justify-between">
            <div>
              <h1 className="text-lg font-bold text-white">Profile Configuration</h1>
              <p className="text-xs text-indigo-200 mt-0.5">
                {profile.name ? `Editing: ${profile.name}` : "Create a new transform profile"}
              </p>
            </div>
            <div className="flex items-center gap-2">
              {profile.clientId && <Badge color="indigo">{profile.clientId}</Badge>}
              <button
                onClick={() => setProfile(defaultProfile())}
                className="flex items-center gap-1 rounded-lg bg-white/10 px-2.5 py-1.5 text-xs font-medium text-white hover:bg-white/20 transition">
                <RefreshCw size={11} /> Reset
              </button>
            </div>
          </div>
        </div>

        {/* Stepper */}
        <div className="bg-white shadow">
          <StepperHeader active={activeTab} onChange={setActiveTab} profile={profile} />
        </div>

        {/* Tab content */}
        <div className="rounded-b-2xl bg-white shadow-lg border border-t-0 border-slate-100 px-6 py-6 min-h-96">
          {activeTab === "basic" && (
            <BasicInfoTab profile={profile}
              onChange={p => setProfile(p)} />
          )}
          {activeTab === "window" && (
            <WindowConfigTab windowConfig={profile.windowConfig}
              onChange={updWindowConfig} />
          )}
          {activeTab === "actions" && (
            <ActionsTab actions={profile.actions}
              onChange={v => updProfile("actions", v)} />
          )}
          {activeTab === "review" && (
            <ReviewTab profile={profile} />
          )}
        </div>

        {/* Footer nav */}
        <div className="flex items-center justify-between pt-4">
          <button
            onClick={goPrev}
            disabled={activeTab === "basic" || saving}
            className="rounded-xl border border-slate-200 bg-white px-4 py-2 text-sm font-medium text-slate-600
              hover:bg-slate-50 disabled:opacity-40 disabled:cursor-not-allowed transition shadow-sm">
            ← Previous
          </button>
          <span className="text-xs text-slate-400">
            Step {TABS.findIndex(t => t.id === activeTab) + 1} of {TABS.length}
          </span>
          {activeTab === "review" ? (
            <div className="flex items-center gap-2">
              {onCancel && (
                <button
                  onClick={onCancel}
                  disabled={saving}
                  className="rounded-xl border border-slate-200 bg-white px-4 py-2 text-sm font-medium text-slate-600
                    hover:bg-slate-50 disabled:opacity-40 disabled:cursor-not-allowed transition shadow-sm">
                  Cancel
                </button>
              )}
              <button
                onClick={() => onSave && onSave({
                  name: profile.name,
                  clientId: profile.clientId,
                  description: profile.description,
                  windowConfig: profile.windowConfig,
                  actions: profile.actions,
                  tags: Object.fromEntries((profile.tags ?? []).map(t => [t.key, t.value])),
                  createdBy: "system",
                  updatedBy: "system",
                })}
                disabled={saving || !profile.name || !profile.clientId}
                className="flex items-center gap-1.5 rounded-xl bg-indigo-600 px-5 py-2 text-sm font-medium text-white
                  hover:bg-indigo-700 disabled:opacity-40 disabled:cursor-not-allowed transition shadow-sm">
                {saving ? "Saving…" : (profileId ? "Save changes" : "Create profile")}
              </button>
            </div>
          ) : (
            <button
              onClick={goNext}
              className="rounded-xl bg-indigo-600 px-4 py-2 text-sm font-medium text-white
                hover:bg-indigo-700 transition shadow-sm">
              Next →
            </button>
          )}
        </div>
      </div>
    </div>
  );
}

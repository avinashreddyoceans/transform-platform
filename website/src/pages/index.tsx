import React from 'react';
import clsx from 'clsx';
import Link from '@docusaurus/Link';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import Layout from '@theme/Layout';
import styles from './index.module.css';

// ─── Data ──────────────────────────────────────────────────────────────────

const PIPELINE_STEPS = [
  { icon: '📥', label: 'Ingest',   color: '#6366f1', desc: 'SFTP · Kafka · REST · S3' },
  { icon: '📄', label: 'Parse',    color: '#3b82f6', desc: 'Any file format' },
  { icon: '✏️', label: 'Correct',  color: '#0ea5e9', desc: 'Normalize & enrich' },
  { icon: '✅', label: 'Validate', color: '#10b981', desc: 'Rule-based checks' },
  { icon: '📤', label: 'Deliver',  color: '#f59e0b', desc: 'Any destination' },
];

const FEATURES = [
  {
    icon: '⚡',
    title: 'Stream-First Engine',
    description:
      'Records flow as a Kotlin coroutine Flow — no full load into memory. Process gigabyte files on a laptop without breaking a sweat.',
    link: '/intro',
  },
  {
    icon: '🔌',
    title: 'Plug-in Parsers',
    description:
      'Add a new file format by implementing one interface and adding @Component. Spring auto-discovers it — zero changes to core.',
    link: '/extending/adding-a-parser',
  },
  {
    icon: '🛡️',
    title: 'Errors Stay Local',
    description:
      'Validation errors attach to individual records, not the pipeline. The stream never stops — bad records are quarantined, good ones ship.',
    link: '/architecture',
  },
  {
    icon: '🔗',
    title: 'Dynamic Integrations',
    description:
      'Add or rotate SFTP, Kafka, REST, and S3 connections at runtime via API. Hot-reload with AES-256 credential encryption — no restarts.',
    link: '/integration/overview',
  },
  {
    icon: '📐',
    title: 'Spec-Driven',
    description:
      'FileSpec owns the schema. Parsers, correction rules, and validators all derive from it — a single source of truth per file format.',
    link: '/architecture',
  },
  {
    icon: '🧪',
    title: 'Test-Friendly by Design',
    description:
      'Open/Closed architecture: every parser, writer, and rule is independently testable. Kotest BDD specs ship with every module.',
    link: '/contributing/testing',
  },
];

const INTEGRATIONS = [
  { icon: '🖥', label: 'SFTP',   color: '#3b82f6', href: '/integration/sftp'     },
  { icon: '📨', label: 'Kafka',  color: '#10b981', href: '/integration/overview' },
  { icon: '🌐', label: 'REST',   color: '#f59e0b', href: '/integration/overview' },
  { icon: '☁️', label: 'S3',     color: '#6366f1', href: '/integration/overview' },
  { icon: '📦', label: 'AS2',    color: '#ec4899', href: '/integration/domain-model' },
  { icon: '📧', label: 'SMTP',   color: '#8b5cf6', href: '/integration/domain-model' },
];

const MODULES = [
  { name: 'platform-common',    desc: 'Shared models, interfaces & utilities' },
  { name: 'platform-core',      desc: 'Pipeline engine · parsers · writers · rules' },
  { name: 'platform-api',       desc: 'REST API · FileSpec management' },
  { name: 'platform-pipeline',  desc: 'Orchestration · scheduling' },
  { name: 'platform-scheduler', desc: 'Quartz-backed job management' },
];

// ─── Components ────────────────────────────────────────────────────────────

function HeroPipeline() {
  return (
    <div className={styles.pipeline}>
      {PIPELINE_STEPS.map((step, i) => (
        <React.Fragment key={step.label}>
          <div className={styles.pipelineStep}>
            <div className={styles.pipelineIcon} style={{ background: step.color }}>
              {step.icon}
            </div>
            <div className={styles.pipelineLabel}>{step.label}</div>
            <div className={styles.pipelineDesc}>{step.desc}</div>
          </div>
          {i < PIPELINE_STEPS.length - 1 && (
            <div className={styles.pipelineArrow}>→</div>
          )}
        </React.Fragment>
      ))}
    </div>
  );
}

function FeatureCard({ icon, title, description, link }) {
  return (
    <Link className={styles.featureCard} to={link}>
      <div className={styles.featureIcon}>{icon}</div>
      <h3 className={styles.featureTitle}>{title}</h3>
      <p className={styles.featureDesc}>{description}</p>
      <span className={styles.featureLearnMore}>Learn more →</span>
    </Link>
  );
}

function IntegrationBadge({ icon, label, color, href }) {
  return (
    <Link className={styles.integrationBadge} to={href} style={{ '--badge-color': color } as React.CSSProperties}>
      <span className={styles.integrationIcon}>{icon}</span>
      <span className={styles.integrationLabel}>{label}</span>
    </Link>
  );
}

function ModuleChip({ name, desc }) {
  return (
    <div className={styles.moduleChip}>
      <code className={styles.moduleName}>{name}</code>
      <span className={styles.moduleDesc}>{desc}</span>
    </div>
  );
}

// ─── Page ──────────────────────────────────────────────────────────────────

export default function Home() {
  const { siteConfig } = useDocusaurusContext();

  return (
    <Layout
      title="Transform Platform"
      description="Enterprise-grade, spec-driven file ↔ event transformation engine for Kotlin and Spring Boot"
    >
      {/* ── Hero ── */}
      <section className={styles.hero}>
        <div className={styles.heroGlow} />
        <div className={styles.heroContent}>
          <div className={styles.heroBadge}>⚡ Kotlin · Spring Boot · Coroutines</div>
          <h1 className={styles.heroTitle}>
            File ↔ Event<br />
            <span className={styles.heroGradientText}>Transformation Engine</span>
          </h1>
          <p className={styles.heroSubtitle}>
            Spec-driven, stream-first, and built to extend. Parse any file format,
            correct and validate records, then deliver to any destination — all
            without touching core code.
          </p>
          <div className={styles.heroCtas}>
            <Link className={styles.ctaPrimary} to="/getting-started">
              Get Started →
            </Link>
            <Link className={styles.ctaSecondary} to="/architecture">
              Architecture
            </Link>
            <Link
              className={styles.ctaGhost}
              href="https://github.com/avinashreddyoceans/transform-platform"
            >
              <GitHubIcon /> GitHub
            </Link>
          </div>
          <HeroPipeline />
        </div>
      </section>

      {/* ── Stats bar ── */}
      <section className={styles.statsBar}>
        <div className={styles.statItem}><strong>5</strong> modules</div>
        <div className={styles.statDivider} />
        <div className={styles.statItem}><strong>6</strong> integration types</div>
        <div className={styles.statDivider} />
        <div className={styles.statItem}><strong>∞</strong> file formats</div>
        <div className={styles.statDivider} />
        <div className={styles.statItem}><strong>0</strong> restarts to add connectors</div>
      </section>

      <main className={styles.main}>

        {/* ── Features ── */}
        <section className={styles.section}>
          <div className={styles.sectionLabel}>What makes it different</div>
          <h2 className={styles.sectionTitle}>Built to grow without breaking</h2>
          <p className={styles.sectionSubtitle}>
            Every piece of Transform Platform follows the Open/Closed principle — add
            capabilities without touching what already works.
          </p>
          <div className={styles.featuresGrid}>
            {FEATURES.map((f) => (
              <FeatureCard key={f.title} {...f} />
            ))}
          </div>
        </section>

        {/* ── Integrations ── */}
        <section className={clsx(styles.section, styles.sectionAlt)}>
          <div className={styles.sectionLabel}>Integration Domain</div>
          <h2 className={styles.sectionTitle}>Connect to anything, dynamically</h2>
          <p className={styles.sectionSubtitle}>
            Add or update client connections via API at runtime. Credentials are
            AES-256 encrypted, connectors hot-reload without service restarts.
          </p>
          <div className={styles.integrationsGrid}>
            {INTEGRATIONS.map((i) => (
              <IntegrationBadge key={i.label} {...i} />
            ))}
          </div>
          <div className={styles.integrationCta}>
            <Link to="/integration/overview">Explore the Integration Domain →</Link>
          </div>
        </section>

        {/* ── Modules ── */}
        <section className={styles.section}>
          <div className={styles.sectionLabel}>Project structure</div>
          <h2 className={styles.sectionTitle}>Modular by design</h2>
          <p className={styles.sectionSubtitle}>
            Each module has a single responsibility. Deploy only what you need.
          </p>
          <div className={styles.modulesGrid}>
            {MODULES.map((m) => (
              <ModuleChip key={m.name} {...m} />
            ))}
          </div>
          <div className={styles.integrationCta}>
            <Link to="/modules/overview">View module breakdown →</Link>
          </div>
        </section>

        {/* ── Extend ── */}
        <section className={clsx(styles.section, styles.sectionAlt)}>
          <div className={styles.extendGrid}>
            <div className={styles.extendText}>
              <div className={styles.sectionLabel}>Extensibility</div>
              <h2 className={styles.sectionTitle}>New format in minutes</h2>
              <p className={styles.extendDescription}>
                Implement one interface, drop one annotation. Spring discovers your
                parser or writer automatically — no changes to the registry, pipeline,
                or any existing code.
              </p>
              <div className={styles.extendLinks}>
                <Link className={styles.extendLink} to="/extending/adding-a-parser">
                  Add a parser →
                </Link>
                <Link className={styles.extendLink} to="/extending/adding-a-writer">
                  Add a writer →
                </Link>
                <Link className={styles.extendLink} to="/extending/adding-correction-rules">
                  Add correction rules →
                </Link>
              </div>
            </div>
            <div className={styles.extendCode}>
              <div className={styles.codeCard}>
                <div className={styles.codeCardHeader}>
                  <div className={styles.codeCardDot} style={{ background: '#ef4444' }} />
                  <div className={styles.codeCardDot} style={{ background: '#f59e0b' }} />
                  <div className={styles.codeCardDot} style={{ background: '#22c55e' }} />
                  <span className={styles.codeCardTitle}>NachaFileParser.kt</span>
                </div>
                <pre className={styles.codeBlock}>{`@Component
class NachaFileParser : FileParser {

  override fun supports(format: FileFormat) =
    format == FileFormat.NACHA

  override fun parse(
    stream: InputStream,
    spec: FileSpec
  ): Flow<ParsedRecord> = flow {
    // your parsing logic here
    emit(ParsedRecord(...))
  }
}`}</pre>
              </div>
            </div>
          </div>
        </section>

        {/* ── CTA ── */}
        <section className={styles.ctaSection}>
          <h2 className={styles.ctaTitle}>Ready to transform your data pipeline?</h2>
          <p className={styles.ctaSubtitle}>
            Explore the docs, clone the repo, and have a working pipeline in under an hour.
          </p>
          <div className={styles.heroCtas}>
            <Link className={styles.ctaPrimary} to="/getting-started">
              Get Started →
            </Link>
            <Link className={styles.ctaSecondary} to="/intro">
              Read the Docs
            </Link>
          </div>
        </section>

      </main>
    </Layout>
  );
}

function GitHubIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor" style={{ marginRight: 6 }}>
      <path d="M12 0C5.37 0 0 5.37 0 12c0 5.31 3.435 9.795 8.205 11.385.6.105.825-.255.825-.57 0-.285-.015-1.23-.015-2.235-3.015.555-3.795-.735-4.035-1.41-.135-.345-.72-1.41-1.23-1.695-.42-.225-1.02-.78-.015-.795.945-.015 1.62.87 1.845 1.23 1.08 1.815 2.805 1.305 3.495.99.105-.78.42-1.305.765-1.605-2.67-.3-5.46-1.335-5.46-5.925 0-1.305.465-2.385 1.23-3.225-.12-.3-.54-1.53.12-3.18 0 0 1.005-.315 3.3 1.23.96-.27 1.98-.405 3-.405s2.04.135 3 .405c2.295-1.56 3.3-1.23 3.3-1.23.66 1.65.24 2.88.12 3.18.765.84 1.23 1.905 1.23 3.225 0 4.605-2.805 5.625-5.475 5.925.435.375.81 1.095.81 2.22 0 1.605-.015 2.895-.015 3.3 0 .315.225.69.825.57A12.02 12.02 0 0 0 24 12c0-6.63-5.37-12-12-12z" />
    </svg>
  );
}

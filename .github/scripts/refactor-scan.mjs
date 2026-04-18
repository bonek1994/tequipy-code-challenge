/**
 * Refactor Scan Orchestrator
 *
 * Calls GitHub Models (Claude Sonnet 4.6) three times for specialized agents
 * (architecture, readability, performance) and once to merge results.
 *
 * Outputs:
 *   evidence/repo-evidence.json
 *   reports/architecture.json
 *   reports/readability.json
 *   reports/performance.json
 *   reports/merged.json
 *   reports/refactor-report.md
 *
 * Then creates or updates a single GitHub issue with the Markdown summary.
 */

import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'fs';
import { join, dirname } from 'path';
import { fileURLToPath } from 'url';

// ── Configuration ──────────────────────────────────────────────────────────

/** Maximum characters to include per file in the evidence pack sent to the LLM. */
const MAX_FILE_CONTENT_LENGTH = 6000;

const GITHUB_TOKEN = process.env.GITHUB_TOKEN;
const MODEL = process.env.MODEL || 'claude-sonnet-4.6';
const ISSUE_TITLE = process.env.ISSUE_TITLE || 'Automated Refactor Report (daily)';
const REPO = process.env.GITHUB_REPOSITORY || '';
const COMMIT_SHA = process.env.GITHUB_SHA || 'unknown';

if (!GITHUB_TOKEN) {
  console.error('ERROR: GITHUB_TOKEN environment variable is required.');
  process.exit(1);
}

if (!REPO) {
  console.error('ERROR: GITHUB_REPOSITORY environment variable is required.');
  process.exit(1);
}

const [REPO_OWNER, REPO_NAME] = REPO.split('/');
const WORKSPACE = process.cwd();

// GitHub Models chat completions endpoint
const MODELS_ENDPOINT = 'https://models.inference.ai.azure.com/chat/completions';

// ── Helpers ────────────────────────────────────────────────────────────────

function ensureDir(dir) {
  if (!existsSync(dir)) {
    mkdirSync(dir, { recursive: true });
  }
}

function readFileSafe(filePath) {
  try {
    return readFileSync(filePath, 'utf8');
  } catch {
    return null;
  }
}

function truncate(content, maxLength = 8000) {
  if (!content) return '';
  if (content.length <= maxLength) return content;
  return content.slice(0, maxLength) + '\n... [truncated]';
}

async function callGitHubModels(messages) {
  const body = JSON.stringify({
    model: MODEL,
    messages,
    temperature: 0.2,
    max_tokens: 2048,
  });

  const response = await fetch(MODELS_ENDPOINT, {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${GITHUB_TOKEN}`,
      'Content-Type': 'application/json',
    },
    body,
  });

  if (!response.ok) {
    const errorText = await response.text();
    throw new Error(`GitHub Models API error ${response.status}: ${errorText}`);
  }

  const data = await response.json();
  const content = data?.choices?.[0]?.message?.content;
  if (!content) {
    throw new Error('GitHub Models API returned an empty response.');
  }
  return content;
}

function parseJsonFromLlm(raw) {
  // Strip markdown code fences if present
  const stripped = raw
    .replace(/^```(?:json)?\s*/i, '')
    .replace(/\s*```\s*$/, '')
    .trim();
  return JSON.parse(stripped);
}

// ── Step 1: Collect evidence ────────────────────────────────────────────────

console.log('=== Step 1: Collecting evidence ===');

const HIGH_SIGNAL_FILES = [
  'README.md',
  'build.gradle.kts',
  'settings.gradle.kts',
  'src/main/kotlin/com/tequipy/challenge/domain/service/AllocationAlgorithm.kt',
  'src/main/kotlin/com/tequipy/challenge/domain/service/CreateAllocationService.kt',
  'src/main/kotlin/com/tequipy/challenge/domain/service/BatchAllocationService.kt',
  'src/main/kotlin/com/tequipy/challenge/adapter/api/web/AllocationController.kt',
  'src/main/kotlin/com/tequipy/challenge/adapter/api/web/EquipmentController.kt',
  'src/main/kotlin/com/tequipy/challenge/domain/service/RegisterEquipmentService.kt',
];

const evidence = {
  repo: REPO,
  commit: COMMIT_SHA,
  generatedAt: new Date().toISOString(),
  files: [],
};

for (const relativePath of HIGH_SIGNAL_FILES) {
  const absPath = join(WORKSPACE, relativePath);
  const content = readFileSafe(absPath);
  if (content !== null) {
    evidence.files.push({
      path: relativePath,
      content: truncate(content, MAX_FILE_CONTENT_LENGTH),
      lines: content.split('\n').length,
    });
    console.log(`  Loaded: ${relativePath} (${content.split('\n').length} lines)`);
  } else {
    console.log(`  Skipped (not found): ${relativePath}`);
  }
}

ensureDir(join(WORKSPACE, 'evidence'));
writeFileSync(
  join(WORKSPACE, 'evidence/repo-evidence.json'),
  JSON.stringify(evidence, null, 2),
  'utf8'
);
console.log('  Written: evidence/repo-evidence.json');

// ── Build a compact code summary for prompts ────────────────────────────────

const codeSummary = evidence.files
  .map(f => `### File: ${f.path}\n\`\`\`\n${f.content}\n\`\`\``)
  .join('\n\n');

// ── Finding schema description (injected into every agent prompt) ───────────

const SCHEMA_DESC = `
Return ONLY a valid JSON object (no markdown, no explanation) with this exact structure:
{
  "agent": "<agent-name>",
  "findings": [
    {
      "title": "<short descriptive title>",
      "severity": "<low|med|high>",
      "effort": "<xs|s|m|l>",
      "files": ["<relative file path>"],
      "recommendation": "<actionable recommendation>",
      "evidence": ["<short snippet or description>"]
    }
  ]
}
Produce between 3 and 8 findings. Do not include any text outside the JSON object.
`.trim();

// ── Step 2: Architecture Agent ──────────────────────────────────────────────

console.log('\n=== Step 2: Architecture Agent ===');

const archMessages = [
  {
    role: 'system',
    content: `You are a senior software architect reviewing a Kotlin Spring Boot backend for architecture quality.
Focus on: module boundaries, layering (domain/service/adapter/port), dependency direction, package structure,
DI strategy, error handling, port/adapter compliance, and missing abstractions.
${SCHEMA_DESC}`,
  },
  {
    role: 'user',
    content: `Review the following Kotlin JVM backend code for architecture issues and improvement opportunities.
Repo: ${REPO}, Commit: ${COMMIT_SHA}

${codeSummary}`,
  },
];

let archRaw;
try {
  archRaw = await callGitHubModels(archMessages);
} catch (err) {
  console.error('Architecture agent failed:', err.message);
  process.exit(1);
}

let archReport;
try {
  archReport = parseJsonFromLlm(archRaw);
  archReport.agent = archReport.agent || 'architecture';
} catch (err) {
  console.error('Failed to parse architecture agent JSON:', err.message);
  console.error('Raw response:', archRaw);
  process.exit(1);
}

ensureDir(join(WORKSPACE, 'reports'));
writeFileSync(
  join(WORKSPACE, 'reports/architecture.json'),
  JSON.stringify(archReport, null, 2),
  'utf8'
);
console.log(`  Architecture findings: ${archReport.findings?.length ?? 0}`);

// ── Step 3: Readability Agent ───────────────────────────────────────────────

console.log('\n=== Step 3: Readability Agent ===');

const readMessages = [
  {
    role: 'system',
    content: `You are a Kotlin code quality expert reviewing code for readability and maintainability.
Focus on: Kotlin idioms (sealed classes, data classes, extension functions, null-safety, sequences),
function length, nesting depth, naming clarity, code duplication, dead code, and consistency.
${SCHEMA_DESC}`,
  },
  {
    role: 'user',
    content: `Review the following Kotlin JVM backend code for readability and maintainability issues.
Repo: ${REPO}, Commit: ${COMMIT_SHA}

${codeSummary}`,
  },
];

let readRaw;
try {
  readRaw = await callGitHubModels(readMessages);
} catch (err) {
  console.error('Readability agent failed:', err.message);
  process.exit(1);
}

let readReport;
try {
  readReport = parseJsonFromLlm(readRaw);
  readReport.agent = readReport.agent || 'readability';
} catch (err) {
  console.error('Failed to parse readability agent JSON:', err.message);
  console.error('Raw response:', readRaw);
  process.exit(1);
}

writeFileSync(
  join(WORKSPACE, 'reports/readability.json'),
  JSON.stringify(readReport, null, 2),
  'utf8'
);
console.log(`  Readability findings: ${readReport.findings?.length ?? 0}`);

// ── Step 4: Performance Agent ───────────────────────────────────────────────

console.log('\n=== Step 4: Performance Agent ===');

const perfMessages = [
  {
    role: 'system',
    content: `You are a performance engineering expert reviewing a Kotlin Spring Boot backend.
Focus on: algorithmic complexity (especially combinatorial search), unnecessary allocations,
Kotlin collection misuse (repeated map/filter chains), I/O patterns, caching opportunities,
concurrency issues, and behavioral improvements to algorithms (pruning, bounding, scoring).
${SCHEMA_DESC}`,
  },
  {
    role: 'user',
    content: `Review the following Kotlin JVM backend code for performance issues and behavioral improvements.
Pay special attention to AllocationAlgorithm.kt which contains a combinatorial search.
Repo: ${REPO}, Commit: ${COMMIT_SHA}

${codeSummary}`,
  },
];

let perfRaw;
try {
  perfRaw = await callGitHubModels(perfMessages);
} catch (err) {
  console.error('Performance agent failed:', err.message);
  process.exit(1);
}

let perfReport;
try {
  perfReport = parseJsonFromLlm(perfRaw);
  perfReport.agent = perfReport.agent || 'performance';
} catch (err) {
  console.error('Failed to parse performance agent JSON:', err.message);
  console.error('Raw response:', perfRaw);
  process.exit(1);
}

writeFileSync(
  join(WORKSPACE, 'reports/performance.json'),
  JSON.stringify(perfReport, null, 2),
  'utf8'
);
console.log(`  Performance findings: ${perfReport.findings?.length ?? 0}`);

// ── Step 5: Merge results ───────────────────────────────────────────────────

console.log('\n=== Step 5: Merging results ===');

// Deterministic merge: combine all findings, de-duplicate by title similarity,
// then sort by severity and effort.

const SEVERITY_ORDER = { high: 0, med: 1, low: 2 };
const EFFORT_ORDER = { xs: 0, s: 1, m: 2, l: 3 };

const allFindings = [
  ...(archReport.findings || []).map(f => ({ ...f, agent: 'architecture' })),
  ...(readReport.findings || []).map(f => ({ ...f, agent: 'readability' })),
  ...(perfReport.findings || []).map(f => ({ ...f, agent: 'performance' })),
];

// Simple de-duplication: skip a finding if another with the same normalized
// title (lowercased, spaces collapsed) already exists.
const seen = new Set();
const deduped = allFindings.filter(f => {
  const key = f.title.toLowerCase().replace(/\s+/g, ' ').trim();
  if (seen.has(key)) return false;
  seen.add(key);
  return true;
});

deduped.sort((a, b) => {
  const sevDiff = (SEVERITY_ORDER[a.severity] ?? 99) - (SEVERITY_ORDER[b.severity] ?? 99);
  if (sevDiff !== 0) return sevDiff;
  return (EFFORT_ORDER[a.effort] ?? 99) - (EFFORT_ORDER[b.effort] ?? 99);
});

const merged = {
  repo: REPO,
  commit: COMMIT_SHA,
  generatedAt: evidence.generatedAt,
  totalFindings: deduped.length,
  findings: deduped,
};

writeFileSync(
  join(WORKSPACE, 'reports/merged.json'),
  JSON.stringify(merged, null, 2),
  'utf8'
);
console.log(`  Total merged findings: ${deduped.length}`);

// ── Step 6: Generate Markdown report ───────────────────────────────────────

console.log('\n=== Step 6: Generating Markdown report ===');

function severityBadge(s) {
  if (s === 'high') return '🔴 High';
  if (s === 'med') return '🟡 Med';
  return '🟢 Low';
}

function effortBadge(e) {
  const map = { xs: 'XS', s: 'S', m: 'M', l: 'L' };
  return map[e] || e.toUpperCase();
}

const highCount = deduped.filter(f => f.severity === 'high').length;
const medCount = deduped.filter(f => f.severity === 'med').length;
const lowCount = deduped.filter(f => f.severity === 'low').length;

const sections = ['architecture', 'readability', 'performance'].map(agent => {
  const agentFindings = deduped.filter(f => f.agent === agent);
  if (agentFindings.length === 0) return '';
  const rows = agentFindings
    .map(f => `| ${f.title} | ${severityBadge(f.severity)} | ${effortBadge(f.effort)} | \`${(f.files || []).join(', ')}\` |`)
    .join('\n');
  return `### ${agent.charAt(0).toUpperCase() + agent.slice(1)} (${agentFindings.length} findings)\n\n| Finding | Severity | Effort | Files |\n|---------|----------|--------|-------|\n${rows}`;
});

const detailsSection = deduped
  .map((f, i) => {
    const filesStr = (f.files || []).map(fp => `\`${fp}\``).join(', ');
    const evidenceStr = (f.evidence || []).map(e => `  - ${e}`).join('\n');
    return `#### ${i + 1}. ${f.title}\n- **Agent**: ${f.agent}\n- **Severity**: ${severityBadge(f.severity)}\n- **Effort**: ${effortBadge(f.effort)}\n- **Files**: ${filesStr || '_n/a_'}\n- **Recommendation**: ${f.recommendation}\n- **Evidence**:\n${evidenceStr || '  - _none_'}`;
  })
  .join('\n\n');

const markdown = `# Automated Refactor Report

> **Repo**: \`${REPO}\`
> **Commit**: \`${COMMIT_SHA}\`
> **Generated**: ${evidence.generatedAt}
> **Model**: ${MODEL}

## Summary

| Severity | Count |
|----------|-------|
| 🔴 High  | ${highCount} |
| 🟡 Med   | ${medCount} |
| 🟢 Low   | ${lowCount} |
| **Total** | **${deduped.length}** |

${sections.filter(Boolean).join('\n\n')}

---

## Detailed Findings

${detailsSection}

---
_Generated by the [Refactor Scan workflow](.github/workflows/refactor-scan.yml)_
`;

writeFileSync(join(WORKSPACE, 'reports/refactor-report.md'), markdown, 'utf8');
console.log('  Written: reports/refactor-report.md');

// ── Step 7: Create or update GitHub issue ──────────────────────────────────

console.log('\n=== Step 7: Creating / updating GitHub issue ===');

const GITHUB_API = 'https://api.github.com';

async function githubApi(path, method = 'GET', body = null) {
  const opts = {
    method,
    headers: {
      'Authorization': `Bearer ${GITHUB_TOKEN}`,
      'Accept': 'application/vnd.github+json',
      'X-GitHub-Api-Version': '2022-11-28',
      'Content-Type': 'application/json',
    },
  };
  if (body) opts.body = JSON.stringify(body);

  const response = await fetch(`${GITHUB_API}${path}`, opts);
  if (!response.ok) {
    const errorText = await response.text();
    throw new Error(`GitHub API ${method} ${path} → ${response.status}: ${errorText}`);
  }
  return response.json();
}

// Search for existing open issue with this exact title (fetch a small page
// since only one pinned issue is expected; scanning a few extras is safe).
const searchResult = await githubApi(
  `/search/issues?q=${encodeURIComponent(`repo:${REPO} is:issue in:title "${ISSUE_TITLE}"`)}&per_page=5`
);

const existingIssue = (searchResult.items || []).find(
  i => i.title === ISSUE_TITLE && i.state === 'open'
);

const issueBody = `${markdown}\n\n<!-- refactor-scan-marker -->`;

if (existingIssue) {
  console.log(`  Updating existing issue #${existingIssue.number}: ${ISSUE_TITLE}`);
  await githubApi(
    `/repos/${REPO_OWNER}/${REPO_NAME}/issues/${existingIssue.number}`,
    'PATCH',
    { body: issueBody }
  );
  console.log(`  Issue #${existingIssue.number} updated successfully.`);
} else {
  console.log(`  Creating new issue: ${ISSUE_TITLE}`);
  const created = await githubApi(
    `/repos/${REPO_OWNER}/${REPO_NAME}/issues`,
    'POST',
    {
      title: ISSUE_TITLE,
      body: issueBody,
      labels: [],
    }
  );
  console.log(`  Issue #${created.number} created successfully.`);
}

console.log('\n=== Refactor scan complete ===');

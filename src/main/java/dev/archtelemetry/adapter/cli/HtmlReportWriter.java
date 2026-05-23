package dev.archtelemetry.adapter.cli;

import dev.archtelemetry.application.HealthReport;
import dev.archtelemetry.domain.Snapshot;
import dev.archtelemetry.domain.StaleModuleWarning;
import dev.archtelemetry.domain.Trend;

import java.util.List;

public final class HtmlReportWriter {

    public static String generate(Trend trend, HealthReport report, List<Snapshot> snapshots) {
        return generate(trend, report, snapshots, List.of());
    }

    public static String generate(Trend trend, HealthReport report, List<Snapshot> snapshots,
                                  List<StaleModuleWarning> staleWarnings) {
        String dataJson = JsonReportWriter.generate(trend, report, snapshots, staleWarnings).stripTrailing();
        return HTML_HEAD + dataJson + HTML_TAIL;
    }

    // Split around the data injection point to avoid placeholder collision
    private static final String HTML_HEAD = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Arx Report</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:system-ui,-apple-system,BlinkMacSystemFont,sans-serif;background:#f1f5f9;color:#1e293b;font-size:15px}
.container{max-width:1200px;margin:0 auto;padding:24px 20px}
header{margin-bottom:28px}
h1{font-size:1.6rem;font-weight:700;margin-bottom:16px;color:#0f172a}
h2{font-size:1rem;font-weight:600;color:#334155;margin-bottom:14px}
.cards{display:grid;grid-template-columns:repeat(3,1fr);gap:14px;margin-bottom:0}
@media(max-width:640px){.cards{grid-template-columns:1fr}}
.card{background:#fff;border-radius:10px;padding:18px 20px;box-shadow:0 1px 3px rgba(0,0,0,.08);border-top:3px solid #e2e8f0}
.card.improving{border-top-color:#16a34a}.card.degrading{border-top-color:#dc2626}.card.stable{border-top-color:#d97706}
.card-value{font-size:2rem;font-weight:700;line-height:1;color:#0f172a}
.card.improving .card-value{color:#16a34a}.card.degrading .card-value{color:#dc2626}.card.stable .card-value{color:#d97706}
.card-label{color:#64748b;font-size:.8rem;margin-top:5px;letter-spacing:.02em;text-transform:uppercase}
section{background:#fff;border-radius:10px;padding:22px 24px;margin-bottom:20px;box-shadow:0 1px 3px rgba(0,0,0,.08)}
.table-wrap{overflow-x:auto}
table{width:100%;border-collapse:collapse;font-size:.82rem;min-width:700px}
th{text-align:left;padding:8px 10px;background:#f8fafc;border-bottom:2px solid #e2e8f0;cursor:pointer;user-select:none;white-space:nowrap;color:#475569;font-weight:600}
th:hover{background:#f1f5f9;color:#0f172a}
th.sort-asc::after{content:" ↑";color:#2563eb}
th.sort-desc::after{content:" ↓";color:#2563eb}
td{padding:7px 10px;border-bottom:1px solid #f1f5f9;white-space:nowrap}
tr:last-child td{border-bottom:none}
tr:hover td{background:#fafafa}
.badge{display:inline-block;padding:2px 8px;border-radius:9999px;font-size:.72rem;font-weight:600;letter-spacing:.01em}
.badge-warn{background:#fef3c7;color:#92400e}.badge-danger{background:#fee2e2;color:#991b1b}
.badge-good{background:#dcfce7;color:#166534}.badge-neutral{background:#f1f5f9;color:#64748b}
.violation-row{display:flex;align-items:center;padding:7px 12px;margin-bottom:4px;border-radius:7px;font-family:monospace;font-size:.83rem;gap:6px}
.violation-row.current,.violation-row.new-v{background:#fee2e2;color:#7f1d1d}
.violation-row.resolved{background:#dcfce7;color:#14532d}
.violation-row.chronic{background:#fef3c7;color:#78350f}
.violation-row .age{margin-left:auto;font-size:.75rem;opacity:.7;font-family:system-ui}
.arrow{color:#94a3b8;flex-shrink:0}
.tabs{display:flex;gap:4px;margin-bottom:16px;flex-wrap:wrap}
.tab{padding:5px 14px;border-radius:6px;cursor:pointer;font-size:.82rem;border:1px solid #e2e8f0;background:#fff;color:#475569;transition:all .15s}
.tab:hover{background:#f8fafc}.tab.active{background:#2563eb;color:#fff;border-color:#2563eb}
.tab-pane{display:none}.tab-pane.active{display:block}
.empty{color:#94a3b8;font-style:italic;padding:8px 0;font-size:.85rem}
.warn-row{display:flex;align-items:baseline;padding:7px 12px;margin-bottom:4px;border-radius:7px;background:#fef3c7;gap:8px;font-size:.85rem}
.warn-row strong{color:#92400e}
canvas{display:block;border-radius:8px;border:1px solid #e2e8f0}
#trend-svg{display:block;width:100%;overflow:visible}
.legend{display:flex;gap:16px;margin-top:10px;font-size:.78rem;color:#64748b;flex-wrap:wrap}
.legend-item{display:flex;align-items:center;gap:5px}
.legend-dot{width:10px;height:10px;border-radius:50%;flex-shrink:0}
.legend-line{width:18px;height:2px;flex-shrink:0}
</style>
</head>
<body>
<div class="container">
<header>
  <h1>Arx Health Report</h1>
  <div class="cards" id="summary-cards"></div>
</header>

<section>
  <h2>Violation Trend</h2>
  <svg id="trend-svg" height="170"></svg>
  <div class="legend">
    <div class="legend-item"><div class="legend-dot" style="background:#2563eb"></div>Violations per snapshot</div>
  </div>
</section>

<section>
  <h2>Module Metrics</h2>
  <div class="table-wrap">
  <table id="module-table">
    <thead><tr>
      <th onclick="sortTable(0)">Module</th>
      <th onclick="sortTable(1)">Layer</th>
      <th onclick="sortTable(2)">Fan-In</th>
      <th onclick="sortTable(3)">Fan-Out</th>
      <th onclick="sortTable(4)">Instability</th>
      <th onclick="sortTable(5)">WMC</th>
      <th onclick="sortTable(6)">Hotspot</th>
      <th onclick="sortTable(7)">ChurnAcc</th>
      <th onclick="sortTable(8)">BusFactor</th>
      <th>Flags</th>
    </tr></thead>
    <tbody id="module-tbody"></tbody>
  </table>
  </div>
</section>

<section>
  <h2>Dependency Graph</h2>
  <canvas id="dep-canvas" width="1100" height="520"></canvas>
  <div class="legend">
    <div class="legend-item"><div class="legend-dot" style="background:#3b82f6"></div>Layer 2+</div>
    <div class="legend-item"><div class="legend-dot" style="background:#1e40af"></div>Layer 0 (core)</div>
    <div class="legend-item"><div class="legend-line" style="background:#cbd5e1"></div>Dependency</div>
    <div class="legend-item"><div class="legend-line" style="background:#ef4444"></div>Violation</div>
  </div>
</section>

<section>
  <h2>Violations</h2>
  <div class="tabs" id="violation-tabs"></div>
  <div id="tab-current" class="tab-pane active"></div>
  <div id="tab-new" class="tab-content tab-pane"></div>
  <div id="tab-resolved" class="tab-content tab-pane"></div>
  <div id="tab-chronic" class="tab-content tab-pane"></div>
</section>

<section id="section-warnings">
  <h2>Instability Warnings</h2>
  <div id="warnings-content"></div>
</section>

<section id="section-stale" style="display:none">
  <h2>Blueprint Warnings — Stale Modules</h2>
  <div id="stale-content"></div>
</section>
</div>

<script>
const DATA =\s""";

    private static final String HTML_TAIL = """
;

let sortCol = -1, sortAsc = true;
const SORT_KEYS = ['name','layer','fanIn','fanOut','instability','wmc','hotspot','churnAcceleration','busFactorRisk'];

function init() {
  renderSummary();
  renderTrend();
  renderModuleTable(DATA.moduleMetrics);
  renderGraph();
  renderViolations();
  renderWarnings();
  renderStaleModules();
}

function renderSummary() {
  const d = DATA.summary;
  const cls = d.trend.toLowerCase();
  const label = d.trend === 'IMPROVING' ? '↓ IMPROVING' : d.trend === 'DEGRADING' ? '↑ DEGRADING' : '→ STABLE';
  document.getElementById('summary-cards').innerHTML =
    card('', String(d.snapshotsAnalyzed), 'Snapshots Analyzed') +
    card(cls, label, 'Architecture Trend') +
    card(d.totalViolations > 0 ? 'degrading' : 'improving', String(d.totalViolations), 'Current Violations');
}

function card(cls, val, label) {
  return '<div class="card ' + cls + '"><div class="card-value">' + esc(val) + '</div><div class="card-label">' + esc(label) + '</div></div>';
}

function renderTrend() {
  const svg = document.getElementById('trend-svg');
  const history = DATA.history;
  if (!history.length) { svg.innerHTML = '<text x="20" y="30" fill="#94a3b8" font-size="13">No history</text>'; return; }
  const W = 900, H = 150, PL = 42, PR = 16, PT = 14, PB = 28;
  const IW = W - PL - PR, IH = H - PT - PB;
  svg.setAttribute('viewBox', '0 0 ' + W + ' ' + H);
  const maxV = Math.max.apply(null, history.map(function(h){ return h.violationCount; }).concat([1]));
  const xs = history.map(function(_, i){ return PL + (i / Math.max(history.length - 1, 1)) * IW; });
  const ys = history.map(function(h){ return PT + (1 - h.violationCount / maxV) * IH; });
  let html = '';
  // gridlines
  for (let g = 0; g <= 4; g++) {
    const gy = PT + g * IH / 4;
    html += '<line x1="' + PL + '" y1="' + gy + '" x2="' + (PL + IW) + '" y2="' + gy + '" stroke="#f1f5f9" stroke-width="1"/>';
  }
  html += '<line x1="' + PL + '" y1="' + PT + '" x2="' + PL + '" y2="' + (PT+IH) + '" stroke="#e2e8f0" stroke-width="1"/>';
  html += '<line x1="' + PL + '" y1="' + (PT+IH) + '" x2="' + (PL+IW) + '" y2="' + (PT+IH) + '" stroke="#e2e8f0" stroke-width="1"/>';
  html += '<text x="' + (PL-5) + '" y="' + (PT+4) + '" font-size="10" fill="#94a3b8" text-anchor="end">' + maxV + '</text>';
  html += '<text x="' + (PL-5) + '" y="' + (PT+IH+4) + '" font-size="10" fill="#94a3b8" text-anchor="end">0</text>';
  // area fill
  if (history.length > 1) {
    let area = xs[0] + ',' + (PT+IH);
    xs.forEach(function(x, i){ area += ' ' + x + ',' + ys[i]; });
    area += ' ' + xs[xs.length-1] + ',' + (PT+IH);
    html += '<polygon points="' + area + '" fill="#dbeafe" opacity="0.5"/>';
    const line = xs.map(function(x, i){ return x + ',' + ys[i]; }).join(' ');
    html += '<polyline points="' + line + '" fill="none" stroke="#2563eb" stroke-width="2" stroke-linejoin="round"/>';
  }
  history.forEach(function(h, i) {
    html += '<circle cx="' + xs[i] + '" cy="' + ys[i] + '" r="4" fill="#2563eb" stroke="#fff" stroke-width="1.5"/>';
    const commit = h.commitId.slice(0, 6);
    html += '<text x="' + xs[i] + '" y="' + (PT+IH+18) + '" font-size="9" fill="#94a3b8" text-anchor="middle">' + esc(commit) + '</text>';
  });
  svg.innerHTML = html;
}

function renderModuleTable(metrics) {
  document.getElementById('module-tbody').innerHTML = metrics.map(function(m) {
    return '<tr><td><strong>' + esc(m.name) + '</strong></td>'
      + '<td>' + (m.layer < 0 ? '-' : m.layer) + '</td>'
      + '<td>' + m.fanIn + '</td>'
      + '<td>' + m.fanOut + '</td>'
      + '<td>' + m.instability.toFixed(2) + '</td>'
      + '<td>' + m.wmc + '</td>'
      + '<td>' + m.hotspot.toFixed(1) + '</td>'
      + '<td>' + m.churnAcceleration.toFixed(2) + '</td>'
      + '<td>' + m.busFactorRisk.toFixed(2) + '</td>'
      + '<td>' + getFlags(m) + '</td></tr>';
  }).join('');
}

function sortTable(col) {
  const ths = document.querySelectorAll('#module-table th');
  if (sortCol === col) sortAsc = !sortAsc; else { sortCol = col; sortAsc = true; }
  ths.forEach(function(th, i) {
    th.className = i === col ? (sortAsc ? 'sort-asc' : 'sort-desc') : '';
  });
  const sorted = DATA.moduleMetrics.slice().sort(function(a, b) {
    const key = SORT_KEYS[col];
    if (!key) return 0;
    const av = a[key], bv = b[key];
    const cmp = typeof av === 'string' ? av.localeCompare(bv) : av - bv;
    return sortAsc ? cmp : -cmp;
  });
  renderModuleTable(sorted);
}

function getFlags(m) {
  const badges = [];
  if (m.hotspot > 50) badges.push('<span class="badge badge-danger">⚠ hotspot</span>');
  if (m.busFactorRisk > 5) badges.push('<span class="badge badge-warn">⚠ bus-factor</span>');
  if (badges.length) return badges.join(' ');
  if (m.layer >= 0 && m.layer <= 1 && m.instability <= 0.5)
    return '<span class="badge badge-good">' + (m.layer === 0 ? 'stable core' : 'healthy') + '</span>';
  if (m.instability > 0.5 && m.layer <= 1 && m.layer >= 0)
    return '<span class="badge badge-warn">⚠ high coupling</span>';
  if (m.instability > 0.5 && m.layer > 1)
    return '<span class="badge badge-neutral">expected</span>';
  return '';
}

function renderGraph() {
  const canvas = document.getElementById('dep-canvas');
  const ctx = canvas.getContext('2d');
  const W = canvas.width, H = canvas.height;
  const modules = DATA.moduleMetrics;
  const allDeps = DATA.allDependencies || [];
  const violSet = {};
  (DATA.violations.current || []).forEach(function(v){ violSet[v.source + '->' + v.target] = true; });

  if (!modules.length) {
    ctx.fillStyle = '#94a3b8'; ctx.font = '14px system-ui';
    ctx.fillText('No module data', W/2 - 50, H/2); return;
  }

  const nodes = modules.map(function(m, i) {
    return {
      name: m.name, layer: m.layer,
      x: W/2 + Math.cos(2*Math.PI*i/modules.length) * Math.min(W,H)*0.32,
      y: H/2 + Math.sin(2*Math.PI*i/modules.length) * Math.min(W,H)*0.32,
      vx: 0, vy: 0
    };
  });
  const nodeMap = {};
  nodes.forEach(function(n){ nodeMap[n.name] = n; });

  function layerColor(layer) {
    if (layer === 0) return '#1e40af';
    if (layer === 1) return '#2563eb';
    if (layer === 2) return '#3b82f6';
    if (layer === 3) return '#60a5fa';
    return '#94a3b8';
  }

  function drawArrow(ax, ay, bx, by, color, width) {
    const dx = bx - ax, dy = by - ay;
    const dist = Math.sqrt(dx*dx + dy*dy) || 1;
    const ux = dx/dist, uy = dy/dist;
    const r = 22;
    const sx = ax + ux*r, sy = ay + uy*r;
    const ex = bx - ux*r, ey = by - uy*r;
    if (Math.sqrt((ex-sx)*(ex-sx)+(ey-sy)*(ey-sy)) < 2) return;
    ctx.beginPath(); ctx.moveTo(sx, sy); ctx.lineTo(ex, ey);
    ctx.strokeStyle = color; ctx.lineWidth = width; ctx.stroke();
    const angle = Math.atan2(ey - sy, ex - sx);
    ctx.beginPath();
    ctx.moveTo(ex, ey);
    ctx.lineTo(ex - 9*Math.cos(angle-0.38), ey - 9*Math.sin(angle-0.38));
    ctx.lineTo(ex - 9*Math.cos(angle+0.38), ey - 9*Math.sin(angle+0.38));
    ctx.closePath(); ctx.fillStyle = color; ctx.fill();
  }

  function tick() {
    for (let i = 0; i < nodes.length; i++) {
      for (let j = i+1; j < nodes.length; j++) {
        let dx = nodes[j].x - nodes[i].x || 0.01;
        let dy = nodes[j].y - nodes[i].y || 0.01;
        const dist = Math.sqrt(dx*dx + dy*dy);
        const force = 7000 / (dist * dist);
        const fx = force*dx/dist, fy = force*dy/dist;
        nodes[i].vx -= fx; nodes[i].vy -= fy;
        nodes[j].vx += fx; nodes[j].vy += fy;
      }
    }
    allDeps.forEach(function(d) {
      const a = nodeMap[d.source], b = nodeMap[d.target];
      if (!a || !b) return;
      const dx = b.x - a.x, dy = b.y - a.y;
      const dist = Math.sqrt(dx*dx + dy*dy) || 1;
      const force = (dist - 170) * 0.035;
      const fx = force*dx/dist, fy = force*dy/dist;
      a.vx += fx; a.vy += fy; b.vx -= fx; b.vy -= fy;
    });
    nodes.forEach(function(n) {
      n.vx += (W/2 - n.x) * 0.002; n.vy += (H/2 - n.y) * 0.002;
      n.vx *= 0.82; n.vy *= 0.82;
      n.x = Math.max(30, Math.min(W-30, n.x + n.vx));
      n.y = Math.max(30, Math.min(H-30, n.y + n.vy));
    });
  }

  function draw() {
    ctx.clearRect(0, 0, W, H);
    allDeps.forEach(function(d) {
      const a = nodeMap[d.source], b = nodeMap[d.target];
      if (!a || !b) return;
      const isViol = violSet[d.source + '->' + d.target];
      drawArrow(a.x, a.y, b.x, b.y, isViol ? '#ef4444' : '#cbd5e1', isViol ? 1.8 : 1);
    });
    nodes.forEach(function(n) {
      ctx.beginPath(); ctx.arc(n.x, n.y, 22, 0, 2*Math.PI);
      ctx.fillStyle = layerColor(n.layer); ctx.fill();
      ctx.strokeStyle = '#fff'; ctx.lineWidth = 2; ctx.stroke();
      ctx.fillStyle = '#fff'; ctx.font = 'bold 9px system-ui';
      ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
      const label = n.name.length > 11 ? n.name.slice(0,10) + '…' : n.name;
      ctx.fillText(label, n.x, n.y);
    });
  }

  let frame = 0;
  function animate() {
    tick(); draw();
    if (++frame < 280) requestAnimationFrame(animate);
  }
  animate();
}

function renderViolations() {
  const v = DATA.violations;
  const tabs = [
    ['current', v.current, 'current'],
    ['new', v.new, 'new-v'],
    ['resolved', v.resolved, 'resolved'],
    ['chronic', v.chronic, 'chronic']
  ];
  const tabsEl = document.getElementById('violation-tabs');
  tabsEl.innerHTML = tabs.map(function(t, i) {
    return '<div class="tab' + (i===0?' active':'') + '" onclick="showViolTab(' + i + ')">'
      + t[0].charAt(0).toUpperCase()+t[0].slice(1) + ' (' + t[1].length + ')</div>';
  }).join('');
  document.getElementById('tab-current').innerHTML = violList(v.current, 'current');
  document.getElementById('tab-new').innerHTML = violList(v.new, 'new-v');
  document.getElementById('tab-resolved').innerHTML = violList(v.resolved, 'resolved');
  document.getElementById('tab-chronic').innerHTML = chronicList(v.chronic);
}

function violList(arr, cls) {
  if (!arr.length) return '<div class="empty">None</div>';
  return arr.map(function(v) {
    return '<div class="violation-row ' + cls + '"><code>' + esc(v.source) + '</code><span class="arrow">→</span><code>' + esc(v.target) + '</code></div>';
  }).join('');
}

function chronicList(arr) {
  if (!arr.length) return '<div class="empty">None</div>';
  return arr.map(function(vr) {
    return '<div class="violation-row chronic"><code>' + esc(vr.source) + '</code><span class="arrow">→</span><code>' + esc(vr.target) + '</code><span class="age">' + vr.ageInSnapshots + ' snapshots</span></div>';
  }).join('');
}

function showViolTab(idx) {
  const ids = ['current','new','resolved','chronic'];
  document.querySelectorAll('.tab').forEach(function(t, i){ t.className = 'tab' + (i===idx?' active':''); });
  ids.forEach(function(id, i){ document.getElementById('tab-'+id).className = 'tab-pane' + (i===idx?' active':''); });
}

function renderWarnings() {
  const el = document.getElementById('warnings-content');
  if (!DATA.instabilityWarnings.length) {
    el.innerHTML = '<div class="empty">No instability warnings</div>'; return;
  }
  el.innerHTML = DATA.instabilityWarnings.map(function(w) {
    return '<div class="warn-row"><strong>⚠ ' + esc(w.module) + '</strong><span>' + esc(w.reason) + '</span></div>';
  }).join('');
}

function renderStaleModules() {
  const stale = DATA.staleModules || [];
  const section = document.getElementById('section-stale');
  if (!stale.length) return;
  section.style.display = '';
  document.getElementById('stale-content').innerHTML = stale.map(function(name) {
    return '<div class="warn-row"><strong>⚠ ' + esc(name) + '</strong><span>no files matched this module in the latest snapshot — pattern may be stale or mismatched</span></div>';
  }).join('');
}

function esc(s) {
  return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}

document.addEventListener('DOMContentLoaded', init);
</script>
</body>
</html>
""";
}

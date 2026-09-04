package ba.sake.codeps.report

import ba.sake.tupson.{*, given}

/** HTML presentation of compact health history. The generated page uses the
  * same small Pico CSS dependency as the documentation site and D3 for its
  * responsive SVG chart. */
object HealthHistoryHtml:
  def render(snapshots: Seq[HealthSnapshot]): String =
    val data = snapshots.map(_.toJson(spaces = 0, sort = true)).mkString("[", ",", "]")
      .replace("<", "\\u003c")
      .replace(">", "\\u003e")
      .replace("&", "\\u0026")
      .replace("\u2028", "\\u2028")
      .replace("\u2029", "\\u2029")
    html(data)

  private def html(data: String): String =
    """<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>codeps status</title>
  <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/@picocss/pico@2/css/pico.min.css">
  <style>
    main.container { --pico-spacing: .75rem; }
    main.container > header { margin-bottom: 1rem; }
    main.container > article, main.container > .grid { margin-bottom: .75rem; }
    article { padding: 1rem; }
    h2 { margin-bottom: .5rem; font-size: 1.25rem; }
    #chart { display: block; width: 100%; height: 290px; }
    #chart .domain, #chart .tick line { stroke: var(--pico-muted-border-color); }
    #chart .grid line { stroke: var(--pico-muted-border-color); stroke-dasharray: 3 5; }
    #chart text { fill: var(--pico-muted-color); font-size: 12px; }
    #chart .trend-line { fill: none; stroke: var(--pico-primary); stroke-width: 3; stroke-linejoin: round; stroke-linecap: round; }
    #chart .trend-point { stroke: var(--pico-background-color); stroke-width: 2; cursor: pointer; }
    #chart .selected-point { stroke: var(--pico-primary); stroke-width: 4; }
    #chart .trend-point:focus { outline: 2px solid var(--pico-primary); outline-offset: 2px; }
    #chart .chart-hit { fill: transparent; cursor: crosshair; }
    #chart .hover-line { stroke: var(--pico-muted-color); stroke-dasharray: 3 3; pointer-events: none; }
    #chart .hover-marker { fill: var(--pico-primary); stroke: var(--pico-background-color); stroke-width: 2; pointer-events: none; }
    .chart-wrap { position: relative; }
    .chart-tooltip { position: absolute; display: none; z-index: 2; min-width: 180px; max-width: 260px; padding: .6rem .7rem; border: 1px solid var(--pico-muted-border-color); border-radius: var(--pico-border-radius); background: var(--pico-card-background-color); box-shadow: var(--pico-box-shadow); pointer-events: none; font-size: .85rem; }
    .chart-tooltip strong, .chart-tooltip small { display: block; }
    .chart-tooltip small { color: var(--pico-muted-color); }
    .trend-header h2 { margin-bottom: 0; }
    .trend-layout { display: grid; grid-template-columns: minmax(12rem, 18rem) minmax(0, 1fr); gap: 1rem; align-items: start; }
    .metric-control { margin-bottom: 1rem; }
    #metric { min-height: 0; padding: .35rem .6rem; font-size: .9rem; }
    .score { font-size: 2rem; font-weight: 750; line-height: 1; }
    .status, .snapshot-meta, #metric-help { color: var(--pico-muted-color); }
    .section-note { display: block; color: var(--pico-muted-color); }
    .snapshot-meta { margin-bottom: 0; }
    .metric-table td { text-align: right; }
    .penalty { display: grid; grid-template-columns: max-content minmax(0, 1fr) max-content; gap: .6rem; align-items: center; margin: .55rem 0; }
    .penalty span, .penalty small { white-space: nowrap; }
    .penalty small { color: var(--pico-muted-color); text-align: right; }
    @media (max-width: 700px) { .trend-layout { grid-template-columns: 1fr; } }
  </style>
</head>
<body>
  <main class="container">
    <header><h1>Codebase status</h1></header>
    <article>
      <h2>Latest snapshot</h2>
      <div id="latest"></div>
    </article>
    <article>
      <div class="trend-header"><h2>Trend</h2><small class="section-note" id="trend-summary"></small></div>
      <div class="trend-layout">
        <aside>
          <div class="metric-control">
            <label for="metric">Metric</label>
            <select id="metric" aria-describedby="metric-help"></select>
            <small id="metric-help"></small>
          </div>
          <p class="snapshot-meta" id="selected-meta">Select a point to inspect that snapshot.</p>
        </aside>
        <div class="chart-wrap">
          <svg id="chart" role="img" aria-label="Health history trend"></svg>
          <div id="chart-tooltip" class="chart-tooltip" role="tooltip" aria-hidden="true"></div>
        </div>
      </div>
    </article>
    <article><h2>Health factors</h2><small class="section-note">Higher is better — each factor is scored from 0 to 10.</small><div id="factors"></div></article>
    <div class="grid">
      <article><h2>Cycles</h2><table class="metric-table"><tbody id="cycle-metrics"></tbody></table></article>
      <article><h2>Architecture</h2><table class="metric-table"><tbody id="architecture-metrics"></tbody></table></article>
      <article><h2>API surface</h2><table class="metric-table"><tbody id="surface-metrics"></tbody></table></article>
    </div>
  </main>
  <script src="https://cdn.jsdelivr.net/npm/d3@7.9.0/dist/d3.min.js"></script>
  <script>
    (() => {
      const snapshots = __HEALTH_DATA__.map(snapshot => ({ ...snapshot, date: new Date(snapshot.at) }));
      const metricDefs = [
        { key: "health.score", label: "Health score", description: "The overall score from 1 to 10; higher is better.", value: s => s.health.score, format: value => `${value}/10` },
        { key: "structure.nodes", label: "Components", description: "Number of components in the analyzed graph.", value: s => s.structure.nodes, format: formatNumber },
        { key: "structure.edges", label: "Relationships", description: "Number of relationships between components.", value: s => s.structure.edges, format: formatNumber },
        { key: "structure.criticalPathLength", label: "Maximum layer depth", description: "Longest chain of component dependencies after each cycle is treated as one component. Higher values can indicate more architectural layers.", value: s => s.structure.criticalPathLength, format: formatNumber },
        { key: "cycles.count", label: "Cycles", description: "Number of cyclic strongly connected components.", value: s => s.cycles.count, format: formatNumber },
        { key: "cycles.nodes", label: "Components in cycles", description: "Number of components that belong to cycles.", value: s => s.cycles.nodes, format: formatNumber },
        { key: "cycles.largestScc", label: "Largest cyclic SCC", description: "Number of components in the largest cyclic SCC.", value: s => s.cycles.largestScc, format: formatNumber },
        { key: "surface.publicMutableSurface", label: "Public mutable declarations", description: "Number of public declarations marked mutable.", value: s => s.surface.publicMutableSurface, format: formatNumber },
        { key: "surface.encapsulationRatio", label: "Public surface ratio", description: "Percentage of declarations that are public.", value: s => s.surface.encapsulationRatio == null ? null : s.surface.encapsulationRatio * 100, format: value => `${formatNumber(value)}%` },
        { key: "findings.total", label: "Findings", description: "Number of reported findings.", value: s => Object.values(s.findings).reduce((sum, count) => sum + count, 0), format: formatNumber }
      ];
      const metricSelect = document.getElementById("metric");
      const metricHelp = document.getElementById("metric-help");
      const chart = document.getElementById("chart");
      const chartWrap = document.querySelector(".chart-wrap");
      const tooltip = document.getElementById("chart-tooltip");
      let selected = snapshots.length - 1;
      metricDefs.forEach(definition => {
        const option = new Option(definition.label, definition.key);
        option.title = definition.description;
        metricSelect.add(option);
      });
      metricSelect.value = "health.score";
      metricSelect.addEventListener("change", () => { updateMetricHelp(); hideTooltip(); draw(); });
      window.addEventListener("resize", draw);

      function formatNumber(value) { return Number(value).toLocaleString(undefined, { maximumFractionDigits: 2 }); }
      function shortCommit(commit) { return commit.length > 12 ? commit.slice(0, 12) : commit; }
      function dateText(at) { return new Date(at).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" }); }
      function statusColor(status) {
        return status === "excellent" || status === "healthy" ? "var(--pico-ins-color)" : status === "needs-attention" ? "var(--pico-mark-background-color)" : "var(--pico-del-color)";
      }
      function selectedMetric() { return metricDefs.find(definition => definition.key === metricSelect.value) || metricDefs[0]; }
      function updateMetricHelp() { metricHelp.textContent = selectedMetric().description; }
      function hideTooltip() {
        tooltip.style.display = "none";
        tooltip.setAttribute("aria-hidden", "true");
        d3.select(chart).selectAll(".hover-line, .hover-marker").style("display", "none");
      }
      function showTooltip(point, xPosition, yPosition, xScale, yScale) {
        const definition = selectedMetric();
        const plotX = xScale(point.date), plotY = yScale(point.value);
        d3.select(chart).select(".hover-line").style("display", null).attr("x1", plotX).attr("x2", plotX);
        d3.select(chart).select(".hover-marker").style("display", null).attr("cx", plotX).attr("cy", plotY);
        tooltip.innerHTML = `<strong>${escapeHtml(definition.label)}: ${escapeHtml(definition.format(point.value))}</strong><small>${escapeHtml(dateText(point.snapshot.at))}</small><small>commit ${escapeHtml(shortCommit(point.snapshot.commit))}</small>`;
        tooltip.style.display = "block";
        tooltip.setAttribute("aria-hidden", "false");
        const bounds = chartWrap.getBoundingClientRect();
        const left = Math.min(Math.max(8, xPosition - bounds.left + 14), bounds.width - tooltip.offsetWidth - 8);
        const top = Math.max(8, yPosition - bounds.top - tooltip.offsetHeight - 14);
        tooltip.style.left = `${left}px`;
        tooltip.style.top = `${top}px`;
      }
      function draw() {
        hideTooltip();
        const definition = selectedMetric();
        const width = Math.max(chart.clientWidth, 520), height = 340;
        const margin = { top: 20, right: 20, bottom: 48, left: 62 };
        const innerWidth = width - margin.left - margin.right, innerHeight = height - margin.top - margin.bottom;
        const svg = d3.select(chart).attr("viewBox", `0 0 ${width} ${height}`);
        svg.selectAll("*").remove();
        if (!snapshots.length) { svg.append("text").attr("x", width / 2).attr("y", height / 2).attr("text-anchor", "middle").text("No snapshots recorded."); return; }
        const points = snapshots.map((snapshot, index) => ({ snapshot, index, date: snapshot.date, value: definition.value(snapshot) }));
        const valid = points.filter(point => point.value != null && Number.isFinite(point.value));
        if (!valid.length) { svg.append("text").attr("x", width / 2).attr("y", height / 2).attr("text-anchor", "middle").text("No numeric values recorded for this metric."); return; }
        const [minValue, maxValue] = d3.extent(valid, point => point.value);
        const valueRange = maxValue === minValue ? Math.max(1, Math.abs(maxValue) * .1) : maxValue - minValue;
        const dates = d3.extent(points, point => point.date);
        const dateRange = dates[0].getTime() === dates[1].getTime() ? [new Date(dates[0].getTime() - 86400000), new Date(dates[1].getTime() + 86400000)] : dates;
        const x = d3.scaleUtc().domain(dateRange).range([0, innerWidth]);
        const y = d3.scaleLinear().domain([minValue - valueRange * .12, maxValue + valueRange * .12]).nice().range([innerHeight, 0]);
        const plot = svg.append("g").attr("transform", `translate(${margin.left},${margin.top})`);
        const hoverLine = plot.append("line").attr("class", "hover-line").attr("y1", 0).attr("y2", innerHeight).style("display", "none");
        const hoverMarker = plot.append("circle").attr("class", "hover-marker").attr("r", 6).style("display", "none");
        const bisect = d3.bisector(point => point.date).center;
        const moveTooltip = function(event) {
          const [pointerX] = d3.pointer(event, this);
          const point = points[Math.max(0, Math.min(points.length - 1, bisect(points, x.invert(pointerX))))];
          if (point.value == null || !Number.isFinite(point.value)) { hideTooltip(); return; }
          showTooltip(point, event.clientX, event.clientY, x, y);
        };
        plot.append("rect").attr("class", "chart-hit").attr("width", innerWidth).attr("height", innerHeight)
          .on("pointermove", moveTooltip).on("pointerleave", hideTooltip).on("pointerdown", moveTooltip);
        plot.append("g").attr("class", "grid").call(d3.axisLeft(y).ticks(5).tickSize(-innerWidth).tickFormat(() => ""));
        plot.append("g").attr("transform", `translate(0,${innerHeight})`).call(d3.axisBottom(x).ticks(Math.min(5, snapshots.length)).tickFormat(d3.timeFormat("%b %Y")));
        plot.append("g").call(d3.axisLeft(y).ticks(5).tickFormat(definition.format));
        plot.append("path").datum(points).attr("class", "trend-line").attr("d", d3.line().defined(point => point.value != null).x(point => x(point.date)).y(point => y(point.value)));
        plot.selectAll(".trend-point").data(valid).join("circle").attr("class", point => point.index === selected ? "trend-point selected-point" : "trend-point").attr("cx", point => x(point.date)).attr("cy", point => y(point.value)).attr("r", point => point.index === selected ? 7 : 5).attr("fill", point => statusColor(point.snapshot.status)).attr("tabindex", 0).attr("role", "button").attr("aria-label", point => `${definition.label}: ${definition.format(point.value)} at ${dateText(point.snapshot.at)}`).on("pointerenter", (event, point) => showTooltip(point, event.clientX, event.clientY, x, y)).on("pointermove", (event, point) => showTooltip(point, event.clientX, event.clientY, x, y)).on("pointerleave", hideTooltip).on("focus", (event, point) => showTooltip(point, event.clientX, event.clientY, x, y)).on("blur", hideTooltip).on("click", (_, point) => { selected = point.index; updateDetails(); draw(); }).on("keydown", (event, point) => { if (event.key === "Enter" || event.key === " ") { event.preventDefault(); selected = point.index; updateDetails(); draw(); } });
      }
      function updateDetails() {
        if (!snapshots.length) { d3.select("#latest").text("No snapshots"); d3.select("#trend-summary").text(""); d3.selectAll("#cycle-metrics, #architecture-metrics, #surface-metrics, #factors").selectAll("*").remove(); return; }
        const snapshot = snapshots[selected], latest = snapshots[snapshots.length - 1];
        d3.select("#latest").html(`<div class="score" style="color:${statusColor(latest.status)}">${latest.health.score}/10</div><div class="status">${escapeHtml(latest.status)}</div><small>${escapeHtml(dateText(latest.at))}</small>`);
        d3.select("#trend-summary").text(`${snapshots.length} recorded snapshot${snapshots.length === 1 ? "" : "s"}`);
        d3.select("#selected-meta").html(`Selected snapshot · health <strong>${snapshot.health.score}/10</strong> · <span style="color:${statusColor(snapshot.status)}">${escapeHtml(snapshot.status)}</span> · ${escapeHtml(dateText(snapshot.at))} · commit <strong>${escapeHtml(shortCommit(snapshot.commit))}</strong>`);
        const cycles = [
          ["Cycles", "Number of cyclic strongly connected components.", formatNumber(snapshot.cycles.count)],
          ["Components in cycles", "Number of components that belong to cycles.", formatNumber(snapshot.cycles.nodes)],
          ["Largest cyclic SCC", "Number of components in the largest cyclic SCC.", formatNumber(snapshot.cycles.largestScc)]
        ];
        const architecture = [
          ["Components", "Number of components in the analyzed graph.", formatNumber(snapshot.structure.nodes)],
          ["Relationships", "Number of relationships between components.", formatNumber(snapshot.structure.edges)],
          ["Maximum layer depth", "Longest chain of component dependencies after each cycle is treated as one component. Higher values can indicate more architectural layers.", formatNumber(snapshot.structure.criticalPathLength)],
          ["Findings", "Number of reported findings.", formatNumber(Object.values(snapshot.findings).reduce((sum, count) => sum + count, 0))]
        ];
        const surface = [
          ["Public mutable declarations", "Number of public declarations marked mutable.", formatNumber(snapshot.surface.publicMutableSurface)],
          ["Public surface ratio", "Percentage of declarations that are public.", snapshot.surface.encapsulationRatio == null ? "—" : `${formatNumber(snapshot.surface.encapsulationRatio * 100)}%`]
        ];
        const renderRows = rows => rows.map(([label, description, value]) => `<tr><th scope="row"><span data-tooltip="${escapeHtml(description)}" data-placement="right">${label}</span></th><td>${value}</td></tr>`).join("");
        d3.select("#cycle-metrics").html(renderRows(cycles));
        d3.select("#architecture-metrics").html(renderRows(architecture));
        d3.select("#surface-metrics").html(renderRows(surface));
        const factors = [
          ["Cycles", "Cycle health from 0 to 10; 10 means no cycle concern.", snapshot.health.factors.cycles],
          ["Public mutability health", "Mutability health from 0 to 10; 10 means no public mutable declarations.", snapshot.health.factors.mutableSurface],
          ["API exposure health", "Public-surface health from 0 to 10; 10 means a smaller public share.", snapshot.health.factors.exposedSurface],
          ["Underused public API", "Structural-use health from 0 to 10; 10 means no underused public API concern.", snapshot.health.factors.structuralUse],
          ["Change propagators", "Change-propagation health from 0 to 10; 10 means no propagator concern.", snapshot.health.factors.propagators]
        ];
        d3.select("#factors").html(factors.map(([name, description, value]) => `<div class="penalty"><span data-tooltip="${escapeHtml(description)}">${name}</span><progress max="10" value="${value}"></progress><small>${formatNumber(value)} / 10</small></div>`).join(""));
      }
      function escapeHtml(value) { return value.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;").replaceAll("'", "&#039;"); }
      updateMetricHelp(); updateDetails(); draw();
    })();
  </script>
</body>
</html>
""".replace("__HEALTH_DATA__", data)

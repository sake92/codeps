/* dsm-matrix.js — DSM (matrix) view module for the codeps demo page.
 *
 * Loaded before the page's inline script. Registers window.codepsMatrix,
 * a factory that takes a `host` object (the page IIFE's internals) and
 * returns the matrix view API:
 *
 *   { order, render, fit, resetView }
 *
 * The page owns pipeline state (graph, meta, filters, cycles, drill-down,
 * viewMode/matrixSort); this module owns only the matrix view/cache state
 * and all canvas drawing + canvas events.
 *
 * Orientation B (DSMSuite-style): cell (row, col) = "col depends on row"
 * = edge col -> row. Rows/cols share one order; topological order keeps
 * cycle members adjacent; DAG edges fall below the diagonal, cycle
 * back-edges above it (red).
 */
(function () {
  'use strict';

  var DSM_CELL = 18;        // world units per cell at zoom 1
  var DSM_LABEL_W = 170;    // left label gutter, screen px
  var DSM_HEADER_H = 26;    // top header band, screen px
  var DSM_MIN_K = 0.2, DSM_MAX_K = 8;

  window.codepsMatrix = function (host) {
    /* ---------- module-owned state (the page owns everything else) ---------- */
    var matrixView = null;   // {k, tx, ty} | null = auto-fit
    var matrixCache = {
      graph: null, sortKey: null, hideFine: null,
      order: [], cells: [], cellIndex: new Map(), cycleMembers: new Set(),
      drawnOrder: [], drawnPos: new Map(), drawnCellK: 0
    };
    var canvas = host.$('#dsm');

    /* ================================================================
     * Pure row/column order.
     * 'topo': Kahn topo-sort on the SCC condensation of the depends-on
     * graph — in-degree-0 SCCs (pure consumers) first, providers last;
     * within an SCC members sort lexicographically (deterministic, mirrors
     * CycleDetector's smallest-first rotation); SCC ties break on their
     * smallest member. 'alpha' | 'in' | 'out' | 'hub': plain comparators
     * (descending for metrics, ties alphabetical) via the `meta` info map.
     * ================================================================ */
    function matrixOrder(ids, edges, sortKey, meta) {
      var arr = Array.from(ids);
      if (sortKey !== 'topo') {
        arr.sort(function (a, b) {
          var ia = meta ? meta.get(a) : null, ib = meta ? meta.get(b) : null;
          var cmp = 0;
          if (sortKey === 'in')  cmp = (ib ? ib.inDeg : 0) - (ia ? ia.inDeg : 0);
          if (sortKey === 'out') cmp = (ib ? ib.outDeg : 0) - (ia ? ia.outDeg : 0);
          if (sortKey === 'hub') cmp = (ib ? ib.hubScore : 0) - (ia ? ia.hubScore : 0);
          return cmp !== 0 ? cmp : (a < b ? -1 : (a > b ? 1 : 0));
        });
        return arr;
      }
      var adj = new Map();
      ids.forEach(function (id) { adj.set(id, []); });
      edges.forEach(function (e) {
        if (adj.has(e.source) && adj.has(e.target)) adj.get(e.source).push(e.target);
      });
      var sccs = host.tarjanScc(ids, adj);   // array of Sets
      var sccOf = new Map();
      sccs.forEach(function (scc, i) { scc.forEach(function (id) { sccOf.set(id, i); }); });
      var inDeg = sccs.map(function () { return 0; });
      var out = sccs.map(function () { return new Set(); });
      edges.forEach(function (e) {
        if (!adj.has(e.source) || !adj.has(e.target)) return;
        var a = sccOf.get(e.source), b = sccOf.get(e.target);
        if (a !== b && !out[a].has(b)) { out[a].add(b); inDeg[b]++; }
      });
      var minOf = sccs.map(function (scc) { return Array.from(scc).sort()[0]; });
      var ready = [];
      for (var i = 0; i < sccs.length; i++) if (inDeg[i] === 0) ready.push(i);
      var sccOrder = [];
      while (ready.length) {
        ready.sort(function (a, b) { return minOf[a] < minOf[b] ? -1 : (minOf[a] > minOf[b] ? 1 : 0); });
        var cur = ready.shift();
        sccOrder.push(cur);
        out[cur].forEach(function (b) { inDeg[b]--; if (inDeg[b] === 0) ready.push(b); });
      }
      var result = [];
      sccOrder.forEach(function (i) {
        Array.from(sccs[i]).sort().forEach(function (id) { result.push(id); });
      });
      return result;
    }

    /* ================================================================
     * Cache + drawing helpers
     * ================================================================ */
    function truncate(ctx, text, maxW) {
      if (ctx.measureText(text).width <= maxW) return text;
      while (text.length > 1 && ctx.measureText(text + '…').width > maxW) text = text.slice(0, -1);
      return text + '…';
    }

    /** Rebuilds cached order + cells when graph/sort/cycles change; refits view on graph change. */
    function resetCache() {
      var c = matrixCache;
      if (c.graph !== host.state.graph) {
        c.graph = host.state.graph;
        c.sortKey = null;
        matrixView = null;      // auto-fit
      }
      if (c.sortKey !== host.state.matrixSort || c.hideFine !== host.state.hideFine) {
        c.sortKey = host.state.matrixSort;
        c.hideFine = host.state.hideFine;
        c.order = matrixOrder(host.state.graph.ids, host.state.graph.edges, host.state.matrixSort, host.state.meta);
        var cycKeys = new Set(), cycMembers = new Set();
        host.visibleCycles().forEach(function (cyc) {
          (cyc.members || []).forEach(function (m) { cycMembers.add(m); });
          (cyc.edges || []).forEach(function (e) { cycKeys.add(e.source + '\u0000' + e.target); });
        });
        c.cycleMembers = cycMembers;
        // cell (row=target, col=source) entries, duplicates summed
        c.cells = [];
        c.cellIndex = new Map();
        var byKey = new Map();
        host.state.graph.edges.forEach(function (e) {
          var key = e.target + '\u0000' + e.source;
          var prev = byKey.get(key);
          if (prev) { prev.weight += e.weight; return; }
          var cell = { row: e.target, col: e.source, weight: e.weight, cyclic: cycKeys.has(e.source + '\u0000' + e.target) };
          byKey.set(key, cell);
          c.cells.push(cell);
          c.cellIndex.set(key, cell);
        });
      }
    }

    /* ================================================================
     * Drawing — called by the page's render() with the current
     * visible set and focused set.
     * ================================================================ */
    function render(visible, focused) {
      if (!host.state.graph) return;
      var ctx = canvas.getContext('2d');
      var main = canvas.parentElement.getBoundingClientRect();
      var dpr = window.devicePixelRatio || 1;
      var W = main.width, H = main.height;
      if (canvas.width !== Math.round(W * dpr) || canvas.height !== Math.round(H * dpr)) {
        canvas.width = Math.round(W * dpr);
        canvas.height = Math.round(H * dpr);
        canvas.style.width = W + 'px';
        canvas.style.height = H + 'px';
      }
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.clearRect(0, 0, W, H);

      resetCache();
      var c = matrixCache;
      // order filtered to visible nodes (hide mode compresses, like graph view)
      var order = host.state.dim ? c.order : c.order.filter(function (id) { return visible.has(id); });
      var n = order.length;
      if (n === 0) {
        matrixCache.drawnOrder = [];
        matrixCache.drawnPos = new Map();
        matrixCache.drawnCellK = 0;
        matrixHover = null;
        return;
      }
      var pos = new Map();
      order.forEach(function (id, i) { pos.set(id, i); });

      if (!matrixView) {
        var availW = Math.max(50, W - DSM_LABEL_W - 20);
        var availH = Math.max(50, H - DSM_HEADER_H - 20);
        var k = Math.min(availW / (n * DSM_CELL), availH / (n * DSM_CELL));
        k = Math.max(DSM_MIN_K, Math.min(DSM_MAX_K, k));
        var gridW = n * DSM_CELL * k, gridH = n * DSM_CELL * k;
        matrixView = { k: k, tx: (availW - gridW) / 2, ty: (availH - gridH) / 2 };
      }
      var view = matrixView;
      var cellK = DSM_CELL * view.k;
      var gx = DSM_LABEL_W + view.tx, gy = DSM_HEADER_H + view.ty;
      var maxW = Math.max(1, host.state.maxWeight);
      var xOf = function (i) { return gx + i * cellK; };
      var yOf = function (i) { return gy + i * cellK; };
      var colFirst = Math.max(0, Math.floor(-gx / cellK));
      var colLast = Math.min(n - 1, Math.ceil((W - gx) / cellK));
      var rowFirst = Math.max(0, Math.floor(-gy / cellK));
      var rowLast = Math.min(n - 1, Math.ceil((H - gy) / cellK));
      var dimAlpha = function (id) { return host.state.dim && !visible.has(id); };

      // grid background + diagonal cells
      ctx.fillStyle = '#0f131b';
      ctx.fillRect(DSM_LABEL_W, DSM_HEADER_H, W - DSM_LABEL_W, H - DSM_HEADER_H);
      ctx.fillStyle = '#12161f';
      for (var d = Math.max(rowFirst, colFirst); d <= Math.min(rowLast, colLast); d++) {
        ctx.fillRect(xOf(d), yOf(d), cellK, cellK);
      }
      // amber strip tint for cycle members
      ctx.fillStyle = 'rgba(250, 204, 21, 0.05)';
      order.forEach(function (id, i) {
        if (!c.cycleMembers.has(id)) return;
        if (i >= rowFirst && i <= rowLast) ctx.fillRect(DSM_LABEL_W, yOf(i), W - DSM_LABEL_W, cellK);
        if (i >= colFirst && i <= colLast) ctx.fillRect(xOf(i), DSM_HEADER_H, cellK, H - DSM_HEADER_H);
      });
      // focus strips
      ctx.fillStyle = 'rgba(56, 189, 248, 0.14)';
      host.state.focusIds.forEach(function (id) {
        var i = pos.get(id);
        if (i === undefined) return;
        if (i >= rowFirst && i <= rowLast) ctx.fillRect(DSM_LABEL_W, yOf(i), W - DSM_LABEL_W, cellK);
        if (i >= colFirst && i <= colLast) ctx.fillRect(xOf(i), DSM_HEADER_H, cellK, H - DSM_HEADER_H);
      });

      // hover crosshair strips
      var isHoverRow = matrixHover && (matrixHover.cell ? matrixHover.row : matrixHover.header === 'row' ? matrixHover.id : null);
      var isHoverCol = matrixHover && (matrixHover.cell ? matrixHover.col : matrixHover.header === 'col' ? matrixHover.id : null);
      var hoverRowIdx = isHoverRow !== null && isHoverRow !== undefined ? pos.get(isHoverRow) : undefined;
      var hoverColIdx = isHoverCol !== null && isHoverCol !== undefined ? pos.get(isHoverCol) : undefined;
      ctx.fillStyle = 'rgba(56, 189, 248, 0.09)';
      if (hoverRowIdx !== undefined) ctx.fillRect(DSM_LABEL_W, yOf(hoverRowIdx), W - DSM_LABEL_W, cellK);
      if (hoverColIdx !== undefined) ctx.fillRect(xOf(hoverColIdx), DSM_HEADER_H, cellK, H - DSM_HEADER_H);

      // cells
      ctx.font = '9px ui-sans-serif, system-ui, sans-serif';
      c.cells.forEach(function (cell) {
        var r = pos.get(cell.row), cc = pos.get(cell.col);
        if (r === undefined || cc === undefined) return;
        if (r < rowFirst || r > rowLast || cc < colFirst || cc > colLast) return;
        var dimmed = dimAlpha(cell.row) || dimAlpha(cell.col);
        ctx.globalAlpha = dimmed ? 0.05 : 1;
        ctx.fillStyle = cell.cyclic ? 'rgba(239, 68, 68, 0.75)' :
          'rgba(56, 189, 248, ' + (0.3 + 0.5 * (cell.weight / maxW)).toFixed(3) + ')';
        ctx.fillRect(xOf(cc), yOf(r), cellK, cellK);
        if (cellK >= 10) {
          ctx.fillStyle = cell.cyclic ? '#fecaca' : '#e0f2fe';
          ctx.textAlign = 'center';
          ctx.textBaseline = 'middle';
          ctx.fillText(String(cell.weight), xOf(cc) + cellK / 2, yOf(r) + cellK / 2, cellK - 2);
        }
        ctx.globalAlpha = 1;
      });
      // grid lines
      ctx.strokeStyle = '#1e2633';
      ctx.lineWidth = 1;
      ctx.beginPath();
      for (var gi = colFirst; gi <= colLast + 1; gi++) {
        ctx.moveTo(Math.max(DSM_LABEL_W, xOf(gi)), DSM_HEADER_H);
        ctx.lineTo(Math.max(DSM_LABEL_W, xOf(gi)), H);
      }
      for (var gj = rowFirst; gj <= rowLast + 1; gj++) {
        ctx.moveTo(DSM_LABEL_W, Math.max(DSM_HEADER_H, yOf(gj)));
        ctx.lineTo(W, Math.max(DSM_HEADER_H, yOf(gj)));
      }
      ctx.stroke();

      // row labels
      ctx.textBaseline = 'middle';
      for (var ri = rowFirst; ri <= rowLast; ri++) {
        var rid = order[ri];
        var ry = yOf(ri) + cellK / 2;
        var dimmed = dimAlpha(rid);
        var hot = rid === isHoverRow || host.state.focusIds.has(rid);
        ctx.globalAlpha = dimmed ? 0.13 : 1;
        if (hot) { ctx.fillStyle = '#0c4a6e'; ctx.fillRect(0, yOf(ri), DSM_LABEL_W - 1, cellK); }
        var info = host.state.meta.get(rid);
        ctx.font = '11px ui-sans-serif, system-ui, sans-serif';
        ctx.fillStyle = hot ? '#bae6fd' : '#e2e8f0';
        ctx.textAlign = 'right';
        ctx.fillText(truncate(ctx, rid, DSM_LABEL_W - 12), DSM_LABEL_W - 10, ry - 6);
        ctx.font = '9px ui-sans-serif, system-ui, sans-serif';
        ctx.fillStyle = '#64748b';
        ctx.fillText('in ' + (info ? info.inDeg : 0) + ' · out ' + (info ? info.outDeg : 0), DSM_LABEL_W - 10, ry + 6);
        ctx.globalAlpha = 1;
      }
      // column headers (rotated)
      for (var ci = colFirst; ci <= colLast; ci++) {
        var cid = order[ci];
        var cx = xOf(ci) + cellK / 2;
        var dimmed = dimAlpha(cid);
        var hot = cid === isHoverCol || host.state.focusIds.has(cid);
        ctx.globalAlpha = dimmed ? 0.13 : 1;
        if (hot) { ctx.fillStyle = '#0c4a6e'; ctx.fillRect(xOf(ci), 0, cellK, DSM_HEADER_H - 1); }
        ctx.save();
        ctx.translate(cx, DSM_HEADER_H - 3);
        ctx.rotate(-Math.PI / 3.5);
        ctx.font = '10px ui-sans-serif, system-ui, sans-serif';
        ctx.fillStyle = hot ? '#bae6fd' : '#94a3b8';
        ctx.textAlign = 'left';
        ctx.textBaseline = 'bottom';
        ctx.fillText(truncate(ctx, cid, 140), 0, 0);
        ctx.restore();
        ctx.globalAlpha = 1;
      }
      // gutter borders
      ctx.strokeStyle = '#1e2633';
      ctx.beginPath();
      ctx.moveTo(DSM_LABEL_W - 1, 0); ctx.lineTo(DSM_LABEL_W - 1, H);
      ctx.moveTo(0, DSM_HEADER_H - 1); ctx.lineTo(W, DSM_HEADER_H - 1);
      ctx.stroke();

      // cache the drawn state for hit-testing (Task 2)
      c.drawnOrder = order;
      c.drawnPos = pos;
      c.drawnCellK = cellK;
    }

    /* ================================================================
     * Interactions
     * ================================================================ */
    var matrixHover = null;       // {header:'row'|'col', id} | {cell, row, col} | null
    var drag = null;              // {x, y, tx, ty, moved}
    var clickTimer = null;        // delays click so dblclick can cancel it
    var rafId = 0;                // rAF throttle for hover redraws

    function eventPos(e) {
      var r = canvas.getBoundingClientRect();
      return { x: e.clientX - r.left, y: e.clientY - r.top };
    }

    function hit(x, y) {
      var c = matrixCache;
      var order = c.drawnOrder, view = matrixView;
      var n = order.length;
      if (!n || !view) return null;
      var cellK = c.drawnCellK;
      var gx = DSM_LABEL_W + view.tx, gy = DSM_HEADER_H + view.ty;
      if (y < DSM_HEADER_H) {
        var ci = Math.floor((x - gx) / cellK);
        return (x >= DSM_LABEL_W && ci >= 0 && ci < n) ? { header: 'col', id: order[ci] } : null;
      }
      if (x < DSM_LABEL_W) {
        var ri = Math.floor((y - gy) / cellK);
        return (ri >= 0 && ri < n) ? { header: 'row', id: order[ri] } : null;
      }
      var c2 = Math.floor((x - gx) / cellK), r2 = Math.floor((y - gy) / cellK);
      if (c2 < 0 || c2 >= n || r2 < 0 || r2 >= n) return null;
      return { cell: true, row: order[r2], col: order[c2] };
    }

    function showCellTooltip(h, clientX, clientY) {
      var cell = matrixCache.cellIndex.get(h.row + '\u0000' + h.col);
      var el = host.$('#tooltip');
      if (!cell) { el.classList.add('hidden'); return; }
      el.innerHTML =
        '<div class="tt-row"><span class="tt-name">' + host.escapeHtml(h.col) + ' → ' + host.escapeHtml(h.row) + '</span></div>' +
        '<div class="tt-row">weight ' + cell.weight + (cell.cyclic ? ' · in a cycle' : '') + '</div>';
      el.classList.remove('hidden');
      host.positionTooltip(clientX, clientY);
    }

    function redraw() {
      render(host.computeVisibleIds(), host.focusedIds());
    }

    function pointerDown(e) {
      if (e.button !== 0) return;
      if (clickTimer) { clearTimeout(clickTimer); clickTimer = null; }
      var view = matrixView;
      if (!view) return;
      var p = eventPos(e);
      drag = { x: p.x, y: p.y, tx: view.tx, ty: view.ty, moved: false };
      host.hideTooltip();
      canvas.setPointerCapture(e.pointerId);
    }

    function pointerMove(e) {
      var p = eventPos(e);
      if (drag) {
        var view = matrixView;
        view.tx = drag.tx + (p.x - drag.x);
        view.ty = drag.ty + (p.y - drag.y);
        if (Math.abs(p.x - drag.x) + Math.abs(p.y - drag.y) > 4) drag.moved = true;
        redraw();
        return;
      }
      // hover
      var h = hit(p.x, p.y);
      var changed = JSON.stringify(h) !== JSON.stringify(matrixHover);
      matrixHover = h;
      if (h && (h.cell || h.header)) {
        if (h.cell) showCellTooltip(h, e.clientX, e.clientY);
        else host.showTooltip(h.id, e.clientX, e.clientY);
      } else {
        host.hideTooltip();
      }
      if (changed && !rafId) {
        rafId = requestAnimationFrame(function () {
          rafId = 0;
          redraw();
        });
      }
    }

    function pointerUp(e) {
      if (e.button !== 0) return;
      var wasDrag = drag;
      drag = null;
      if (!wasDrag || wasDrag.moved) return;
      var p = eventPos(e);
      var h = hit(p.x, p.y);
      if (clickTimer) clearTimeout(clickTimer);
      clickTimer = setTimeout(function () {
        clickTimer = null;
        if (h && h.cell) {
          var cell = matrixCache.cellIndex.get(h.row + '\u0000' + h.col);
          if (cell) host.drillIntoEdge(h.col, h.row);  // cell (row t, col s) = edge s -> t
          else host.clearFocus();
        } else if (h && h.header) {
          host.setFocus(h.id);
        } else {
          host.clearFocus();
        }
      }, 220);
    }

    function dblClick(e) {
      if (clickTimer) { clearTimeout(clickTimer); clickTimer = null; }
      var p = eventPos(e);
      var h = hit(p.x, p.y);
      if (h && h.header) host.drillInto(h.id);
    }

    function contextMenu(e) {
      e.preventDefault();
      var p = eventPos(e);
      var h = hit(p.x, p.y);
      if (!h || !h.header) return;
      var id = h.id;
      var subtree = host.collectSubtree(id);
      subtree.forEach(function (n) { host.state.subtreeHidden.add(n); });
      host.toast('Hidden ' + subtree.size + ' package' + (subtree.size === 1 ? '' : 's') + ' (subtree of ' + id + ')');
      host.render();
    }

    function wheel(e) {
      var view = matrixView;
      if (!view) return;
      e.preventDefault();
      var p = eventPos(e);
      var before = { x: (p.x - DSM_LABEL_W - view.tx) / view.k, y: (p.y - DSM_HEADER_H - view.ty) / view.k };
      view.k = Math.max(DSM_MIN_K, Math.min(DSM_MAX_K, view.k * Math.exp(-e.deltaY * 0.0015)));
      view.tx = p.x - DSM_LABEL_W - before.x * view.k;
      view.ty = p.y - DSM_HEADER_H - before.y * view.k;
      redraw();
    }

    function pointerLeave() {
      matrixHover = null;
      host.hideTooltip();
      redraw();
    }

    canvas.addEventListener('pointerdown', pointerDown);
    canvas.addEventListener('pointermove', pointerMove);
    canvas.addEventListener('pointerup', pointerUp);
    canvas.addEventListener('pointerleave', pointerLeave);
    canvas.addEventListener('dblclick', dblClick);
    canvas.addEventListener('contextmenu', contextMenu);
    canvas.addEventListener('wheel', wheel, { passive: false });

    function resetView() { matrixView = null; }
    function fit() { resetView(); host.render(); }

    window.addEventListener('resize', host.debounce(function () {
      if (host.state.viewMode === 'matrix') render(host.computeVisibleIds(), host.focusedIds());
    }, 150));

    return { order: matrixOrder, render: render, fit: fit, resetView: resetView };
  };
})();

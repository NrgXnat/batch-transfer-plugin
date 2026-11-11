/*
 * Batch Transfer screen: the New transfer / History tabs, and the History panel. History lists the runs that
 * involve the screen's project (or, from search results, the user's own runs), opens a run's per-item outcomes,
 * stops a running run, and exports a run's results or its failed and stopped sessions as a manifest for a rerun.
 * Reads /xapi/transfer/history. batchTransfer.js drives New transfer.
 */
var XNAT = getObject(XNAT || {});
(function(factory) {
    if (typeof define === 'function' && define.amd) {
        define(factory);
    } else if (typeof exports === 'object') {
        module.exports = factory();
    } else {
        return factory();
    }
}(function() {
    XNAT.plugin = getObject(XNAT.plugin || {});
    XNAT.plugin.batchtransfer = getObject(XNAT.plugin.batchtransfer || {});
    var api = XNAT.plugin.batchtransfer.history = getObject(XNAT.plugin.batchtransfer.history || {});

    var PAGE_SIZE = 25;
    var POLL_MS = 4000;          // while History shows something unfinished
    var BADGE_POLL_MS = 15000;   // while New transfer shows and a run is unfinished, for the tab's badge
    var GROUP_LIMIT = 50;        // subjects listed in run detail before "Show all"

    var FINISHED = { Completed: true, Warnings: true, Failed: true, Stopped: true };
    var ITEM_RANK = { Failed: 0, Stopped: 1, Running: 2, Queued: 3, Done: 4 };

    var config = {};
    var view = 'transfer';           // the tab showing: 'transfer' | 'history'
    var page = 0;                    // runs list page
    var listData = null;             // last GET /history response
    var listError = null;
    var listSeq = 0;                 // request counter, so only the newest list response is used
    var badgeRuns = [];              // the newest page's runs, for the tab's badge
    var runId = null;                // tracking id of the open run; null shows the list
    var detailData = null;           // last GET /history/{id} response
    var detailError = null;
    var detailSeq = 0;
    var highlight = null;            // tracking id of the run just submitted
    var problemsOnly = false;        // run detail lists only failed and stopped items
    var showAllGroups = false;
    var expanded = {};               // item id -> its values row is open
    var pollTimer = null;

    api.init = function(cfg) {
        config = cfg || {};
        bindTabs();
        bindHistory();
        loadList();
    };

    // After a submit: History, the runs list, the new run highlighted.
    api.showSubmitted = function(trackingId) {
        highlight = trackingId;
        page = 0;
        runId = null;
        detailData = null;
        showView('history', true);
    };

    // -- Tabs (WAI-ARIA tabs, automatic activation) --

    function bindTabs() {
        $(document).on('click', '.bt-viewtab', function() {
            showView(this.getAttribute('data-view'));
        });
        $(document).on('keydown', '.bt-viewtab', function(e) {
            var $tabs = $('.bt-viewtab');
            var i = $tabs.index(this);
            var next = e.key === 'ArrowRight' ? (i + 1) % $tabs.length
                     : e.key === 'ArrowLeft' ? (i - 1 + $tabs.length) % $tabs.length
                     : e.key === 'Home' ? 0
                     : e.key === 'End' ? $tabs.length - 1
                     : -1;
            if (next < 0) { return; }
            e.preventDefault();
            showView($tabs.eq(next).attr('data-view'));
            $tabs.eq(next).focus();
        });
    }

    // Switching only hides the other panel, so New transfer keeps its selections and History its place.
    function showView(v, reload) {
        var changed = v !== view;
        view = v;
        $('.bt-viewtab').each(function() {
            var on = this.getAttribute('data-view') === v;
            this.setAttribute('aria-selected', String(on));
            this.setAttribute('tabindex', on ? '0' : '-1');
        });
        $('#bt-view-transfer').prop('hidden', v !== 'transfer');
        $('#bt-view-history').prop('hidden', v !== 'history');
        if (v === 'transfer') {
            if (changed && XNAT.plugin.batchtransfer.viewShown) { XNAT.plugin.batchtransfer.viewShown(); }
            schedulePoll();
        } else if (changed || reload) {
            render();
            if (runId) { loadDetail(); } else { loadList(); }
        }
        if (changed || reload) { revealHeader(); }
    }

    function revealHeader() {
        var head = document.querySelector('.bt-pagehead');
        if (head && head.getBoundingClientRect().top < 0) { head.scrollIntoView(); }
    }

    // -- Loading and polling --

    function fetchRuns(p, done, fail) {
        var params = { page: p, size: PAGE_SIZE };
        if (config.projectContext) { params.project = config.projectContext; }
        $.ajax({
            type: 'GET',
            url: XNAT.url.rootUrl('/xapi/transfer/history?' + $.param(params)),
            dataType: 'json',
            cache: false,
            success: done,
            error: fail
        });
    }

    function loadList() {
        var seq = ++listSeq;
        var p = page;
        fetchRuns(p, function(data) {
            if (seq !== listSeq) { return; }
            listData = data;
            listError = null;
            if (p === 0) { setBadge(data.runs); }
            if (view === 'history' && !runId) { render(); }
            schedulePoll();
        }, function(xhr) {
            if (seq !== listSeq) { return; }
            listError = responseMessage(xhr);
            if (view === 'history' && !runId) { render(); }
            schedulePoll();
        });
    }

    function loadBadge() {
        fetchRuns(0, function(data) { setBadge(data.runs); schedulePoll(); }, function() { schedulePoll(); });
    }

    function loadDetail() {
        var seq = ++detailSeq;
        var id = runId;
        $.ajax({
            type: 'GET',
            url: XNAT.url.rootUrl('/xapi/transfer/history/' + encodeURIComponent(id)),
            dataType: 'json',
            cache: false,
            success: function(data) {
                if (seq !== detailSeq || id !== runId) { return; }
                var wasActive = !!detailData && isActive(detailData.run);
                detailData = data;
                detailError = null;
                if (view === 'history') { render(); }
                if (wasActive && !isActive(data.run)) { loadBadge(); }
                schedulePoll();
            },
            error: function(xhr) {
                if (seq !== detailSeq || id !== runId) { return; }
                detailError = xhr.status === 403 ? 'You do not have access to this transfer.'
                            : xhr.status === 404 ? 'No transfer has this tracking id.'
                            : 'Could not load the transfer: ' + responseMessage(xhr);
                if (view === 'history') { render(); }
                schedulePoll();
            }
        });
    }

    // Polls while something shown is unfinished: fast while History shows, slower for the badge otherwise.
    function schedulePoll() {
        clearTimeout(pollTimer);
        pollTimer = null;
        var delay = 0;
        if (view === 'history') {
            if (runId) {
                if (detailData && !detailError && isActive(detailData.run)) { delay = POLL_MS; }
            } else if (listData && (listData.runs || []).some(isActive)) {
                delay = POLL_MS;
            }
        } else if (badgeRuns.some(isActive)) {
            delay = BADGE_POLL_MS;
        }
        if (delay) { pollTimer = setTimeout(poll, delay); }
    }

    function poll() {
        if (view !== 'history') { loadBadge(); }
        else if (runId) { loadDetail(); }
        else { loadList(); }
    }

    function setBadge(runs) {
        badgeRuns = runs || [];
        var running = badgeRuns.filter(function(r) { return r.status === 'Running' || r.status === 'Stopping'; }).length;
        var queued = badgeRuns.filter(function(r) { return r.status === 'Queued'; }).length;
        var text = running ? running + ' running' : queued ? queued + ' queued' : '';
        $('#bt-history-badge').prop('hidden', !text);
        $('#bt-history-badge-text').text(text);
    }

    // -- Rendering --

    function render() {
        var host = document.getElementById('bt-history');
        if (!host) { return; }
        // Re-rendering replaces the controls, so focus goes back to the matching control afterwards.
        var focused = document.activeElement && host.contains(document.activeElement) ? focusKey(document.activeElement) : null;
        host.innerHTML = runId ? detailHtml() : listHtml();
        if (focused) {
            var again = host.querySelector(focused);
            if (again) { again.focus(); }
        }
    }

    function focusKey(el) {
        var act = el.getAttribute('data-act');
        if (act) {
            var id = el.getAttribute('data-id') || el.getAttribute('data-only');
            return '[data-act="' + act + '"]' + (id ? '[data-' + (el.hasAttribute('data-id') ? 'id' : 'only') + '="' + cssEscape(id) + '"]' : '');
        }
        var run = el.getAttribute('data-run');
        return run ? 'tr[data-run="' + cssEscape(run) + '"]' : null;
    }

    // -- Runs list --

    function listHtml() {
        var heading = config.projectContext ? 'Transfers involving ' + esc(config.projectContext) : 'Your transfers';
        var meta = '';
        var body;
        if (!listData) {
            body = listError ? errorHtml('Could not load the transfer history: ' + esc(listError), 'reload-list')
                             : '<div class="bt-h-empty">Loading...</div>';
        } else if (!(listData.runs || []).length) {
            body = '<div class="bt-h-empty">' + (listData.total ? 'No transfers on this page.'
                 : config.projectContext ? 'No transfers involve ' + esc(config.projectContext) + ' yet.'
                 : 'You have not started any transfers yet.') + '</div>';
        } else {
            meta = '<span>' + countText(listData.total || 0, 'transfer') + ', newest first</span>';
            body = runsTable(listData.runs) + pagerHtml();
        }
        return '<div class="bt-panel bt-h-panel">' +
               '<div class="bt-panel-head"><div class="bt-panel-title">' + heading + '</div>' +
               '<div class="bt-panel-meta">' + meta + '</div></div>' + body + '</div>';
    }

    function runsTable(runs) {
        var h = '<table class="bt-h-table bt-h-runs"><thead><tr>' +
                '<th class="bt-h-col-status">Status</th><th>Transfer</th><th class="bt-h-col-started">Started</th>' +
                '<th class="bt-h-col-outcome">Outcome</th><th class="bt-h-col-chev"></th></tr></thead><tbody>';
        runs.forEach(function(r) {
            h += '<tr class="bt-h-row' + (r.tracking_id === highlight ? ' is-new' : '') + '" tabindex="0"' +
                 ' data-run="' + esc(r.tracking_id) + '" aria-label="Open ' + esc(r.operation) + ' to ' +
                 esc(r.destination_project || 'several projects') + ', ' + esc(when(r.created)) + '">' +
                 '<td>' + pill(r.status) + '</td>' +
                 '<td><div class="bt-h-what">' + opBadge(r.operation) + route(r) + '</div>' +
                 '<div class="bt-h-sub">' + countText(r.item_count, unitNoun(r)) + (r.custom_script ? ' &middot; custom script' : '') + '</div></td>' +
                 '<td>' + esc(when(r.created)) + '<div class="bt-h-sub">by ' + esc(r.created_by) + '</div></td>' +
                 '<td>' + outcome(r) + '</td>' +
                 '<td class="bt-h-chev">&#8250;</td></tr>';
        });
        return h + '</tbody></table>';
    }

    function pagerHtml() {
        var total = listData.total || 0;
        var size = listData.size || PAGE_SIZE;
        var start = (listData.page || 0) * size;
        var end = Math.min(start + (listData.runs || []).length, total);
        if (start === 0 && end >= total) { return ''; }
        return '<div class="bt-pager"><span class="bt-pager-nav">' +
               '<button type="button" class="bt-pager-arrow" data-act="page-prev" aria-label="Newer transfers"' + (start === 0 ? ' disabled' : '') + '>&#8249;</button>' +
               '<span class="bt-pager-range">Transfers ' + (start + 1) + '-' + end + ' of ' + total + '</span>' +
               '<button type="button" class="bt-pager-arrow" data-act="page-next" aria-label="Older transfers"' + (end >= total ? ' disabled' : '') + '>&#8250;</button>' +
               '</span></div>';
    }

    function outcome(r) {
        if (r.status === 'Queued') { return '<span class="bt-h-sub">Waiting to start</span>'; }
        if (isActive(r)) {
            var done = r.passed_count + r.failed_count + r.stopped_count;
            return '<div class="bt-h-out">' + progressBar(r.passed_count, r.failed_count, r.stopped_count, r.item_count) +
                   '<span>' + done + ' / ' + r.item_count + '</span></div>' +
                   (r.status === 'Stopping' ? '<div class="bt-h-sub">Finishing the items in progress</div>' : '');
        }
        return '<div class="bt-h-out">' + outcomeCounts(r.passed_count, r.failed_count, r.stopped_count) + '</div>';
    }

    function outcomeCounts(passed, failed, stopped) {
        var h = '';
        if (passed) { h += '<span class="bt-h-ok" title="' + passed + ' done">&#10003; ' + passed + '</span>'; }
        if (failed) { h += '<span class="bt-h-bad" title="' + failed + ' failed">&#10007; ' + failed + '</span>'; }
        if (stopped) { h += '<span class="bt-h-stop" title="' + stopped + ' stopped">&#9632; ' + stopped + '</span>'; }
        return h || '<span class="bt-h-sub">No items</span>';
    }

    function progressBar(passed, failed, stopped, total) {
        var t = total || 1;
        function seg(cls, n) {
            return n ? '<span class="' + cls + '" style="width:' + (n / t * 100).toFixed(2) + '%"></span>' : '';
        }
        return '<span class="bt-h-bar" aria-hidden="true">' + seg('is-ok', passed) + seg('is-bad', failed) + seg('is-stop', stopped) + '</span>';
    }

    // -- Run detail --

    function detailHtml() {
        var back = '<div class="bt-h-backrow"><button type="button" class="bt-h-back" data-act="back">&lsaquo; All transfers</button></div>';
        if (detailError) { return back + errorHtml(esc(detailError), 'reload-detail'); }
        if (!detailData) { return back + '<div class="bt-panel"><div class="bt-h-empty">Loading...</div></div>'; }
        var d = detailData;
        var r = d.run;
        return back +
               '<div class="bt-h-detailhead"><h2>' + esc(r.operation) + ' &rarr; ' + esc(r.destination_project || 'several projects') + '</h2>' +
               pill(r.status) + '<div class="bt-h-actions">' + actionsHtml(d) + '</div></div>' +
               '<div class="bt-panel">' + factsHtml(r) + '</div>' +
               (d.anon_script ? scriptPanelHtml(d.anon_script) : '') +
               '<div class="bt-panel bt-h-panel">' + itemsPanelHtml(d) + '</div>';
    }

    function actionsHtml(d) {
        var h = '';
        if (d.can_stop && (d.run.status === 'Queued' || d.run.status === 'Running')) {
            h += '<button type="button" class="bt-h-btn bt-h-btn-stop" data-act="stop">&#9632; Stop</button>';
        }
        if (rerunAvailable(d)) {
            h += '<button type="button" class="bt-h-btn" data-act="export-rerun" title="A manifest of the failed and stopped ' +
                 'sessions. Upload it unchanged as a manifest CSV to transfer exactly those sessions again.">Export for rerun</button>';
        }
        return h + '<button type="button" class="bt-h-btn" data-act="export-results" title="Every item with its outcome, ' +
                   'message and destination, for your records">Export results</button>';
    }

    function factsHtml(r) {
        var f = [
            ['Started', esc(when(r.created)) + ' &middot; ' + esc(r.created_by)],
            ['Finished', r.finished ? esc(when(r.finished)) : r.status === 'Queued' ? 'Not started' : 'In progress'],
            ['Source', projectName(r.source_project)],
            ['Destination', projectName(r.destination_project)],
            ['Items', countText(r.item_count, unitNoun(r))],
            ['Anonymization', anonFact(r)]
        ];
        if (r.operation === 'Reimport') { f.push(['Labels', labelsFact(r)]); }
        if (r.stop_requested_by) { f.push(['Stopped by', esc(r.stop_requested_by) + ' &middot; ' + esc(when(r.stop_requested_at))]); }
        f.push(['Tracking id', '<span class="bt-h-mono">' + esc(r.tracking_id) + '</span>']);
        return '<div class="bt-h-facts">' + f.map(function(x) {
            return '<div><div class="k">' + x[0] + '</div><div class="v">' + x[1] + '</div></div>';
        }).join('') + '</div>';
    }

    // What anonymized the run's sessions, in the screen's own terms (the Anonymize with picker).
    function anonFact(r) {
        if (r.operation === 'Reimport') {
            if (r.anon_replaced_pipeline) { return 'Custom script only'; }
            return r.custom_script ? 'Destination + custom script' : 'Destination only';
        }
        if (r.operation === 'Share' || r.operation === 'Clone') { return 'None (' + esc(r.operation) + ' transfers data as-is)'; }
        return '-';
    }

    function labelsFact(r) {
        var subject = r.preserve_subject_label === true;
        var session = r.preserve_session_label === true;
        if (subject && session) { return 'Source subject and session labels kept'; }
        if (subject) { return 'Source subject label kept; session label from DICOM'; }
        if (session) { return 'Source session label kept; subject label from DICOM'; }
        return 'From DICOM';
    }

    function scriptPanelHtml(script) {
        return '<div class="bt-panel"><div class="bt-panel-head"><div class="bt-panel-title">Custom anonymization script</div></div>' +
               '<div class="bt-panel-body"><pre class="bt-script-preview bt-h-script">' + esc(script) + '</pre></div></div>';
    }

    function itemsPanelHtml(d) {
        var r = d.run;
        var items = d.items || [];
        var tally = { Done: 0, Failed: 0, Stopped: 0, Running: 0, Queued: 0 };
        items.forEach(function(it) { tally[it.status] = (tally[it.status] || 0) + 1; });
        var problems = tally.Failed + tally.Stopped;
        if (!problems) { problemsOnly = false; }
        var togo = tally.Running + tally.Queued;

        var h = '<div class="bt-panel-head"><div class="bt-panel-title">' + (r.operation === 'Reimport' ? 'Sessions' : 'Items') + '</div>' +
                '<div class="bt-panel-meta">' +
                (isActive(r) ? '<span class="bt-h-livenote"><span class="bt-live-dot"></span>Updating</span>' : '') +
                '<span>' + countText(items.length, unitNoun(r)) + '</span></div></div>';
        h += '<div class="bt-h-sumbar">' + progressBar(tally.Done, tally.Failed, tally.Stopped, items.length) +
             '<span><span class="bt-h-ok">&#10003; ' + tally.Done + '</span> ' + doneWord(r.operation).toLowerCase() + '</span>' +
             (tally.Failed ? '<span><span class="bt-h-bad">&#10007; ' + tally.Failed + '</span> failed</span>' : '') +
             (tally.Stopped ? '<span><span class="bt-h-stop">&#9632; ' + tally.Stopped + '</span> stopped</span>' : '') +
             (togo ? '<span class="bt-h-muted">' + togo + ' to go</span>' : '');
        if (problems) {
            h += '<div class="bt-h-seg" role="group" aria-label="Items shown">' +
                 '<button type="button" data-act="only-problems" data-only="0" aria-pressed="' + !problemsOnly + '">All</button>' +
                 '<button type="button" data-act="only-problems" data-only="1" aria-pressed="' + problemsOnly + '">Failed and stopped</button></div>';
        }
        h += '</div>';
        if (!items.length) { return h + '<div class="bt-h-empty">This run has no items.</div>'; }

        var showRow = items.some(function(it) { return it.row_number != null; });
        var hasValues = items.some(function(it) { return !!it.csv_values; });
        var span = 3 + (showRow ? 1 : 0) + (hasValues ? 1 : 0);
        var groups = groupItems(items);
        var shown = showAllGroups ? groups : groups.slice(0, GROUP_LIMIT);

        h += '<table class="bt-h-table bt-h-items"><thead><tr>' +
             (hasValues ? '<th class="bt-h-col-caret"><span class="bt-sr">Values</span></th>' : '') +
             (showRow ? '<th class="bt-h-col-row" title="The row in the manifest, numbered as a spreadsheet shows it">Row</th>' : '') +
             '<th>Source</th><th>Destination</th><th>Outcome</th></tr></thead><tbody>';
        shown.forEach(function(g) {
            h += '<tr class="bt-h-group"><td colspan="' + span + '"><span class="bt-h-mono">' +
                 (g.label ? esc(g.label) : '(no subject)') + '</span><span class="bt-h-groupsum">' + groupSummary(g, r) + '</span></td></tr>';
            g.items.forEach(function(it) { h += itemRowHtml(it, showRow, hasValues, span); });
        });
        if (shown.length < groups.length) {
            h += '<tr class="bt-h-more"><td colspan="' + span + '"><button type="button" data-act="show-all">Show all ' +
                 groups.length + ' subjects</button></td></tr>';
        }
        return h + '</tbody></table>';
    }

    // Items grouped by source subject. Groups with failures come first, then stopped ones, then manifest order.
    function groupItems(items) {
        var groups = [];
        var byLabel = Object.create(null);
        items.forEach(function(it) {
            if (problemsOnly && it.status !== 'Failed' && it.status !== 'Stopped') { return; }
            var label = it.source_subject_label || '';
            var g = byLabel[label];
            if (!g) {
                g = byLabel[label] = { label: label, items: [], failed: 0, stopped: 0, firstRow: null };
                groups.push(g);
            }
            g.items.push(it);
            if (it.status === 'Failed') { g.failed++; }
            if (it.status === 'Stopped') { g.stopped++; }
            if (it.row_number != null && (g.firstRow === null || it.row_number < g.firstRow)) { g.firstRow = it.row_number; }
        });
        groups.forEach(function(g) { g.items.sort(compareItems); });
        return groups.sort(function(a, b) {
            return ((b.failed > 0) - (a.failed > 0)) ||
                   ((b.stopped > 0) - (a.stopped > 0)) ||
                   compareNullable(a.firstRow, b.firstRow) ||
                   compareText(a.label, b.label);
        });
    }

    function compareItems(a, b) {
        return (ITEM_RANK[a.status] - ITEM_RANK[b.status]) ||
               compareNullable(a.row_number, b.row_number) ||
               ((isSubjectItem(b) ? 1 : 0) - (isSubjectItem(a) ? 1 : 0)) ||
               compareText(a.source_label, b.source_label);
    }

    function groupSummary(g, r) {
        var parts = [countText(g.items.length, unitNoun(r))];
        if (g.failed) { parts.push(g.failed + ' failed'); }
        if (g.stopped) { parts.push(g.stopped + ' stopped'); }
        return parts.join(' &middot; ');
    }

    function itemRowHtml(it, showRow, hasValues, span) {
        var open = !!expanded[it.item_id];
        var h = '<tr class="bt-h-item' + (it.status === 'Failed' ? ' bt-h-item-bad' : '') + '">';
        if (hasValues) {
            h += '<td class="bt-h-col-caret">' + (it.csv_values
                ? '<button type="button" class="bt-h-caret" data-act="toggle-item" data-id="' + esc(it.item_id) + '"' +
                  ' aria-expanded="' + open + '" aria-label="Manifest values for ' + esc(it.source_label || it.item_id) + '">' +
                  (open ? '&#9662;' : '&#9656;') + '</button>'
                : '') + '</td>';
        }
        if (showRow) { h += '<td class="bt-h-col-row">' + (it.row_number != null ? it.row_number : '') + '</td>'; }
        h += '<td><span class="bt-h-mono">' + esc(it.source_label || it.item_id) + '</span>' +
             '<div class="bt-h-sub">' + (isSubjectItem(it) ? 'Subject &middot; ' : '') + esc(it.item_id) + '</div></td>' +
             destinationCellHtml(it) + outcomeCellHtml(it) + '</tr>';
        if (open && it.csv_values) {
            h += '<tr class="bt-h-values"><td colspan="' + span + '"><dl class="bt-h-kv">' +
                 Object.keys(it.csv_values).map(function(k) {
                     return '<dt>' + esc(k) + '</dt><dd>' + esc(it.csv_values[k]) + '</dd>';
                 }).join('') + '</dl></td></tr>';
        }
        return h;
    }

    function destinationCellHtml(it) {
        if (it.destination_hidden) {
            return '<td><span class="bt-h-muted" title="You can\'t read ' + esc(it.destination_project) +
                   ', so this item\'s destination isn\'t shown.">Hidden</span></td>';
        }
        var requested = [it.destination_subject_label, it.destination_session_label].filter(Boolean).join(' / ');
        if (it.destination_id) {
            return '<td><a class="bt-h-mono" href="' + esc(itemUrl(it)) + '">' + esc(it.destination_label || it.destination_id) + '</a>' +
                   (it.destination_label ? '<div class="bt-h-sub">' + esc(it.destination_id) + '</div>' : '') + '</td>';
        }
        if (it.status === 'Done') {
            return '<td><span class="bt-h-muted" title="XNAT reported no archived session. A Reimport into a project that ' +
                   'does not auto-archive goes to the prearchive.">Not archived</span>' +
                   (requested ? '<div class="bt-h-sub">requested ' + esc(requested) + '</div>' : '') + '</td>';
        }
        return '<td>' + (requested ? '<span class="bt-h-mono">' + esc(requested) + '</span><div class="bt-h-sub">requested</div>'
                                   : '<span class="bt-h-muted">-</span>') + '</td>';
    }

    function outcomeCellHtml(it) {
        var icon;
        if (it.status === 'Done') { icon = '<span class="bt-h-ic is-ok">&#10003;</span>'; }
        else if (it.status === 'Failed') { icon = '<span class="bt-h-ic is-bad">&#10007;</span>'; }
        else if (it.status === 'Stopped') { icon = '<span class="bt-h-ic is-stop">&#9632;</span>'; }
        else if (it.status === 'Running') { icon = '<span class="bt-h-spin" aria-hidden="true"></span>'; }
        else { icon = '<span class="bt-h-ic is-wait">&middot;</span>'; }
        return '<td><div class="bt-h-outcome">' + icon + '<div><strong>' + esc(it.status) + '</strong>' +
               (it.message ? '<div class="' + (it.status === 'Failed' ? 'bt-h-reason' : 'bt-h-sub') + '">' + esc(it.message) + '</div>' : '') +
               '</div></div></td>';
    }

    // A subject item of a Share or Clone run: its own label is its subject's label.
    function isSubjectItem(it) {
        return it.mode !== 'Reimport' && !!it.source_label && it.source_label === it.source_subject_label;
    }

    function itemUrl(it) {
        return XNAT.url.rootUrl('/data/' + (isSubjectItem(it) ? 'subjects/' : 'experiments/') +
               encodeURIComponent(it.destination_id) + '?format=html');
    }

    // -- Stop --

    function confirmStop() {
        var r = detailData && detailData.run;
        if (!r) { return; }
        var units = r.operation === 'Reimport' ? 'Sessions' : 'Items';
        var content = '<div style="min-width:440px;max-width:520px;font-size:13.5px;line-height:1.6;color:#3c4043;">' +
            '<p style="margin:0 0 10px;">Stop this ' + esc(r.operation) + ' to <strong>' + esc(r.destination_project || 'several projects') + '</strong>?</p>' +
            '<ul style="margin:0 0 0 18px;padding:0;">' +
            '<li>' + units + ' already transferring will finish.</li>' +
            '<li>' + units + ' not started yet will be skipped.</li>' +
            '<li>Nothing already transferred is undone.</li>' +
            '</ul></div>';
        xModalConfirm({
            title: 'Stop this transfer?',
            content: content,
            okLabel: 'Stop transfer',
            cancelLabel: 'Keep running',
            okAction: postStop
        });
    }

    function postStop() {
        var id = runId;
        $.ajax({
            type: 'POST',
            url: XNAT.url.rootUrl('/xapi/transfer/history/' + encodeURIComponent(id) + '/stop'),
            dataType: 'json',
            success: function(run) {
                if (id !== runId || !detailData) { return; }
                detailData.run = run;
                detailData.can_stop = false;
                render();
                loadDetail();
                loadBadge();
            },
            error: function(xhr) {
                xmodal.message('Batch Transfer', 'The transfer was not stopped: ' + esc(responseMessage(xhr)));
                if (id === runId) { loadDetail(); }
            }
        });
    }

    // -- Exports, built in the browser from run detail --

    // A manifest of the failed and stopped sessions, offered once the run has finished. Not offered when the
    // viewer would get an incomplete one: values or the script withheld, or a destination hidden, which hides
    // the routing labels.
    function rerunAvailable(d) {
        var r = d && d.run;
        if (!r || r.operation !== 'Reimport' || !FINISHED[r.status]) { return false; }
        if (r.custom_script && !d.anon_script) { return false; }
        var items = d.items || [];
        if (items.some(function(it) { return it.destination_hidden; })) { return false; }
        return items.some(rerunCandidate);
    }

    function rerunCandidate(it) {
        return it.mode === 'Reimport' && (it.status === 'Failed' || it.status === 'Stopped');
    }

    function exportRerun() {
        var d = detailData;
        if (!rerunAvailable(d)) { return; }
        var items = (d.items || []).filter(rerunCandidate);
        // A blank source_session_label would mean "all of the subject's sessions", so an item whose labels
        // weren't recorded is left out rather than widened.
        var usable = items.filter(function(it) { return it.source_subject_label && it.source_label; });
        usable.sort(function(a, b) { return compareNullable(a.row_number, b.row_number) || compareText(a.source_label, b.source_label); });

        // An item's values include its routing labels, since a script can bind them. They are written once, as
        // the routing columns: a second copy would be the one the manifest parser reads.
        var valueColumns = [];
        (d.items || []).forEach(function(it) {
            Object.keys(it.csv_values || {}).forEach(function(k) {
                if (k !== 'destination_subject_label' && k !== 'destination_session_label' && valueColumns.indexOf(k) < 0) {
                    valueColumns.push(k);
                }
            });
        });
        var routeSubject = usable.some(function(it) { return it.destination_subject_label; });
        var routeSession = usable.some(function(it) { return it.destination_session_label; });
        var columns = ['source_subject_label', 'source_session_label']
            .concat(routeSubject ? ['destination_subject_label'] : [])
            .concat(routeSession ? ['destination_session_label'] : [])
            .concat(valueColumns);

        var lines = [columns.map(csvCell).join(',')];
        usable.forEach(function(it) {
            var row = [it.source_subject_label, it.source_label];
            if (routeSubject) { row.push(it.destination_subject_label); }
            if (routeSession) { row.push(it.destination_session_label); }
            valueColumns.forEach(function(k) { row.push(it.csv_values ? it.csv_values[k] : ''); });
            lines.push(row.map(csvCell).join(','));
        });
        download(fileBase(d.run) + '_rerun.csv', lines.join('\r\n') + '\r\n');

        if (usable.length < items.length) {
            var left = items.length - usable.length;
            xmodal.message('Batch Transfer', left + ' ' + plural(left, 'session') + ' left out of the rerun manifest: ' +
                'their subject or session label was not recorded when the run was submitted.');
        }
    }

    // Every item with its outcome. Not a manifest: its columns aren't the manifest's, so it can't be uploaded by
    // mistake as one.
    function exportResults() {
        var d = detailData;
        if (!d) { return; }
        var columns = ['row', 'subject', 'label', 'item_id', 'operation', 'status', 'message',
                       'destination_project', 'destination_id', 'destination_label', 'started', 'finished'];
        var items = (d.items || []).slice().sort(function(a, b) {
            return compareNullable(a.row_number, b.row_number) || compareText(a.source_subject_label, b.source_subject_label) ||
                   compareText(a.source_label, b.source_label);
        });
        var lines = [columns.join(',')];
        items.forEach(function(it) {
            var hidden = !!it.destination_hidden;
            lines.push([it.row_number, it.source_subject_label, it.source_label, it.item_id, it.mode, it.status, it.message,
                        it.destination_project, hidden ? '(hidden)' : it.destination_id, hidden ? '(hidden)' : it.destination_label,
                        isoTime(it.started), isoTime(it.finished)].map(csvCell).join(','));
        });
        download(fileBase(d.run) + '_results.csv', lines.join('\r\n') + '\r\n');
    }

    function download(name, text) {
        var blob = new Blob([text], { type: 'text/csv;charset=utf-8' });
        if (window.navigator && window.navigator.msSaveOrOpenBlob) {
            window.navigator.msSaveOrOpenBlob(blob, name);
            return;
        }
        var url = URL.createObjectURL(blob);
        var a = document.createElement('a');
        a.href = url;
        a.download = name;
        a.style.display = 'none';
        document.body.appendChild(a);
        a.click();
        setTimeout(function() { URL.revokeObjectURL(url); a.parentNode.removeChild(a); }, 0);
    }

    function fileBase(r) {
        return String(r.tracking_id || 'batch_transfer').replace(/[^A-Za-z0-9_-]/g, '_');
    }

    function csvCell(v) {
        v = v == null ? '' : String(v);
        return /[",\r\n]|^\s|\s$/.test(v) ? '"' + v.replace(/"/g, '""') + '"' : v;
    }

    // -- Events --

    function bindHistory() {
        $(document).on('click', '#bt-history [data-act]', function() {
            if (this.disabled) { return; }
            var act = this.getAttribute('data-act');
            if (act === 'back') {
                openList();
            } else if (act === 'page-prev' || act === 'page-next') {
                page = Math.max(0, page + (act === 'page-next' ? 1 : -1));
                loadList();
            } else if (act === 'reload-list') {
                listError = null;
                render();
                loadList();
            } else if (act === 'reload-detail') {
                detailError = null;
                render();
                loadDetail();
            } else if (act === 'stop') {
                confirmStop();
            } else if (act === 'export-rerun') {
                exportRerun();
            } else if (act === 'export-results') {
                exportResults();
            } else if (act === 'only-problems') {
                problemsOnly = this.getAttribute('data-only') === '1';
                render();
            } else if (act === 'show-all') {
                showAllGroups = true;
                render();
            } else if (act === 'toggle-item') {
                var id = this.getAttribute('data-id');
                expanded[id] = !expanded[id];
                render();
            }
        });
        $(document).on('click', '#bt-history tr.bt-h-row', function(e) {
            if ($(e.target).closest('a, button').length) { return; }
            openRun(this.getAttribute('data-run'));
        });
        $(document).on('keydown', '#bt-history tr.bt-h-row', function(e) {
            if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                openRun(this.getAttribute('data-run'));
            }
        });
    }

    function openRun(id) {
        runId = id;
        detailData = null;
        detailError = null;
        problemsOnly = false;
        showAllGroups = false;
        expanded = {};
        render();
        loadDetail();
        revealHeader();
        var back = document.querySelector('#bt-history [data-act="back"]');
        if (back) { back.focus(); }
    }

    function openList() {
        var from = runId;
        runId = null;
        detailData = null;
        detailError = null;
        render();
        loadList();
        var row = from && document.querySelector('#bt-history tr[data-run="' + cssEscape(from) + '"]');
        if (row) { row.focus(); }
    }

    // -- Helpers --

    function isActive(r) {
        return !!r && (r.status === 'Queued' || r.status === 'Running' || r.status === 'Stopping');
    }

    function unitNoun(r) {
        return r.operation === 'Reimport' ? 'session' : 'item';
    }

    function doneWord(op) {
        return op === 'Reimport' ? 'Reimported' : op === 'Clone' ? 'Cloned' : op === 'Share' ? 'Shared' : 'Done';
    }

    function pill(status) {
        return '<span class="bt-st bt-st-' + esc(String(status || '').toLowerCase()) + '"><i></i>' + esc(status) + '</span>';
    }

    function opBadge(op) {
        return '<span class="bt-opb">' + esc(op) + '</span>';
    }

    function route(r) {
        return '<span class="bt-h-route">' + projectName(r.source_project) + '<span class="arr">&rarr;</span>' +
               projectName(r.destination_project) + '</span>';
    }

    // A null project means the run had several.
    function projectName(p) {
        if (!p) { return '<span class="bt-h-muted">several projects</span>'; }
        return p === config.projectContext ? '<span class="here">' + esc(p) + '</span>' : esc(p);
    }

    function errorHtml(message, retryAct) {
        return '<div class="bt-panel"><div class="bt-h-error">' + message +
               ' <button type="button" class="bt-h-linkbtn" data-act="' + retryAct + '">Retry</button></div></div>';
    }

    function when(ms) {
        if (ms == null) { return ''; }
        var d = new Date(ms);
        if (isNaN(d.getTime())) { return ''; }
        return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
    }

    function isoTime(ms) {
        if (ms == null) { return ''; }
        var d = new Date(ms);
        return isNaN(d.getTime()) ? '' : d.toISOString();
    }

    function pad(n) { return n < 10 ? '0' + n : String(n); }

    function compareNullable(a, b) {
        if (a == null && b == null) { return 0; }
        if (a == null) { return 1; }
        if (b == null) { return -1; }
        return a - b;
    }

    function compareText(a, b) {
        return String(a || '').localeCompare(String(b || ''));
    }

    function plural(n, noun) { return noun + (n === 1 ? '' : 's'); }
    function countText(n, noun) { return n + ' ' + plural(n, noun); }

    // The server's message for a failed request, shortened if long; an HTML error page falls back to the status.
    function responseMessage(xhr) {
        var text = $.trim((xhr && xhr.responseText) || '');
        if (!text || text.charAt(0) === '<') { return xhr && xhr.status ? 'HTTP ' + xhr.status : 'no response from the server'; }
        return text.length > 600 ? text.substring(0, 600) + '...' : text;
    }

    function esc(s) {
        if (s == null) { return ''; }
        return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    function cssEscape(s) {
        return window.CSS && CSS.escape ? CSS.escape(s) : String(s).replace(/["\\]/g, '\\$&');
    }
}));

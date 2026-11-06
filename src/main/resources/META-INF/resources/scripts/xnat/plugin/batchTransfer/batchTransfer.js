var XNAT = getObject(XNAT || {});
(function(factory){
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

    var projectAnonCache = {};
    var selectedOp = 'Share';
    var lastOp = 'Share';          // previous operation, to detect leaving Reimport
    var selectedProject = null;
    var config = {};
    var submitInFlight = false;
    var cohortMode = 'tree';       // 'tree' (interactive) | 'csv' (manifest upload)
    var caps = null;               // GET /capabilities (per-import anon support + limits)
    var anonMode = 'none';         // 'none' (destination only) | 'script' (custom Anon-Script), Reimport only
    var anonVerdictValid = null;   // last /validate/script: true/false, or null (not validated / n/a)
    var anonValidateSeq = 0;       // /validate/script request counter, so a stale response can't win

    // Row index, built once after load (rows are static - only classes/visibility change).
    // Replaces per-row `$('#bt-data-body tr[data-id=...]')` scans that made tree walks O(n2).
    var rowById = {};              // data-id  -> DOM <tr>
    var childrenByParent = {};     // data-parent -> [DOM <tr>, ...]

    var operationDetails = {
        Share: 'This operation shares the selected data into the destination project. ' +
               'The data is not copied - it remains the same data element, owned by the source project, ' +
               'and changes made there are reflected in the destination.',
        Clone: 'This operation creates an independent copy of the selected data in the destination project. ' +
               'The cloned data is editable separately from the source and is not anonymized. ' +
               'Cloned session files are hard-linked to the source, requiring no additional disk space at the time of cloning.',
        Reimport: 'This operation reimports the selected image sessions through the destination project\'s ' +
                  'anonymization or a user provided DicomEdit script. Only DICOM files are transferred; target session metadata ' +
                  'is derived from DICOM tags and/or anonymization parameters.'
    };

    // -- Initialization --

    XNAT.plugin.batchtransfer.init = function(cfg) {
        config = cfg || {};
        selectedOp = 'Share';
        lastOp = 'Share';

        if (config.presetDestination) {
            selectedProject = config.presetDestination;
            XNAT.plugin.batchtransfer.checkProjectAnon(selectedProject, function(anonEnabled) {
                projectAnonCache[selectedProject] = anonEnabled;
                updateAnonBanner();
                updateSummary();
            });
        } else {
            loadProjects();
        }

        buildRowIndex();
        bindOperationCards();
        bindProjectSelect();
        bindTableInteractions();
        bindCohortToggle();
        configureCohortModes();
        bindCsvUpload();
        bindAnonBand();
        bindViewportSync();
        fetchCapabilities();
        // One item set is rendered (all readable items). Apply the default operation's
        // eligibility so rows whose project can't be shared are hidden/excluded on load.
        applyOperationEligibility('Share');
        updateOperationDetail();
        updateSummary();
    };

    // Build the id/parent indexes once. The server renders a fixed row set; rows are never
    // added or removed (only .selected / .bt-import-* classes and visibility toggle), so the
    // index stays valid for the page lifetime.
    function buildRowIndex() {
        rowById = {};
        childrenByParent = {};
        var rows = document.querySelectorAll('#bt-data-body tr');
        for (var i = 0; i < rows.length; i++) {
            var el = rows[i];
            var id = el.getAttribute('data-id');
            var parent = el.getAttribute('data-parent');
            if (id) rowById[id] = el;
            if (parent) {
                (childrenByParent[parent] || (childrenByParent[parent] = [])).push(el);
            }
            // Cache lowercased row text once for the filter (label/type/id are static; the only
            // volatile part is the checkmark glyph, which never matters for a text query).
            el._btFilterText = (el.textContent || '').toLowerCase();
        }
    }

    function rowFor(id) {
        var el = rowById[id];
        return el ? $(el) : $();
    }

    function childrenOf(parentId) {
        var arr = childrenByParent[parentId];
        return (arr && arr.length) ? $(arr) : $();
    }

    // -- Project Loading --

    function loadProjects() {
        window.projectLoader = new ProjectLoader();
        window.projectLoader.onLoadComplete.subscribe(function() {
            var projects = window.projectLoader.list;
            if (config.projectContext) {
                projects = projects.filter(function(p) { return p.id !== config.projectContext; });
            }
            renderProjects(document.getElementById('bt-project-select'), projects);
            $('#bt-project-select').prop('disabled', false);
        });
        window.projectLoader.init();
    }

    // -- Anonymization Check --

    XNAT.plugin.batchtransfer.checkProjectAnon = function(projectId, callback, forceRefresh) {
        if (!projectId) {
            callback(null, 'No project selected');
            return;
        }
        // forceRefresh re-queries the server even on a cache hit, so the destination's anon
        // status is current at selection time (it may have changed since the page loaded).
        if (!forceRefresh && projectAnonCache.hasOwnProperty(projectId)) {
            callback(projectAnonCache[projectId], null);
            return;
        }
        $.ajax({
            type: 'GET',
            url: XNAT.url.rootUrl('/xapi/anonymize/projects/' + projectId + '/enabled'),
            contentType: 'application/json',
            success: function(data) {
                projectAnonCache[projectId] = data;
                callback(data, null);
            },
            error: function(xhr) {
                callback(null, 'Could not retrieve anonymization setting: ' + xhr.status);
            }
        });
    };

    // -- Operation Banners --
    // The "About this transfer" panel only carries alerts the action bar does not show. Reimport's
    // anonymization status and storage cost are action-bar chips (anonChipsHtml), each with its full
    // explanation as a tooltip, so they are not repeated here. What remains is Share or Clone into a
    // destination with anonymization enabled: the data is transferred as-is, and neither operation has
    // an anonymization chip.

    function updateAnonBanner() {
        var area = document.getElementById('bt-op-detail-warning-area');
        if (!area) return;

        var html = '';
        var dest = selectedProject;
        if (dest && projectAnonCache[dest] === true) {
            if (selectedOp === 'Share') {
                html += banner('warn', '<strong>' + dest + ' has anonymization enabled</strong>, but Share does not anonymize - the data is made visible as-is.');
            } else if (selectedOp === 'Clone') {
                html += banner('warn', '<strong>' + dest + ' has anonymization enabled</strong>, but Clone copies the data as-is - the cloned copy is not anonymized.');
            }
        }
        area.innerHTML = html;
    }

    // Severity -> class + icon: good = green check, warn = red triangle, caution/notice = info glyph.
    function banner(sev, text) {
        var cls = sev === 'good' ? 'bt-anon-good' : sev === 'caution' ? 'bt-anon-caution' : sev === 'notice' ? 'bt-anon-ok' : 'bt-anon-warn';
        var ic = sev === 'good' ? '&#10003;' : sev === 'warn' ? '&#9888;' : '&#8505;';
        return '<div class="bt-anon-banner ' + cls + '"><span class="bt-anon-icon">' + ic + '</span><div>' + text + '</div></div>';
    }

    // The single rule for whether this build accepts a per-import Anon-Script. An unread or failed
    // capabilities probe counts as supported, so the editor is offered AND anything the user can see
    // and validate in it is submitted. The server's 409 is what rejects a build that genuinely lacks
    // the param; deciding otherwise here would drop the script and reimport with no anonymization
    // while the screen still showed a valid script.
    function perImportAnonSupported() {
        return !(caps && caps.per_import_anon === false);
    }

    // A custom Anon-Script is "present" only for Reimport on a supporting build, in script mode, with
    // non-blank text. Drives the double-anon vs not-anonymized banner branches.
    function hasCustomScript() {
        return selectedOp === 'Reimport'
            && perImportAnonSupported()
            && anonMode === 'script'
            && ($('#bt-anon-script').val() || '').trim().length > 0;
    }

    // -- Operation Detail --

    function updateOperationDetail() {
        $('#bt-op-detail-desc').text(operationDetails[selectedOp] || '');
        // Reimport-only affordances: the Anonymization mode toggle and the preserve-source-label checkboxes.
        $('#bt-anon-band').toggle(selectedOp === 'Reimport');
        $('#bt-reimport-label-option').toggle(selectedOp === 'Reimport');
        // The script editor lives in the workspace (not inside the anon band), so hide it explicitly
        // when leaving Reimport or when the build lacks per-import anon.
        $('#bt-anon-editor').toggle(selectedOp === 'Reimport' && anonMode === 'script' && perImportAnonSupported());
        // Eligibility warnings are per operation - each explains why rows are hidden for that
        // operation only, so a stale one must not linger after the operation changes.
        $('#bt-share-warning').toggle(selectedOp === 'Share');
        $('#bt-copy-warning').toggle(selectedOp === 'Clone');
    }

    // Re-pin the rail whenever the viewport, the rail or the action bar changes height: rail sections
    // show and hide per operation, and the bar's summary chips wrap. Resize re-pins only; it never
    // re-renders the cohort table.
    function bindViewportSync() {
        var timer;
        $(window).on('resize.bttransfer', function() {
            clearTimeout(timer);
            timer = setTimeout(syncRailSticky, 150);
        });
        if (window.ResizeObserver) {
            var ro = new ResizeObserver(syncRailSticky);
            ['.bt-rail', '.bt-action-bar'].forEach(function(sel) {
                var el = document.querySelector(sel);
                if (el) { ro.observe(el); }
            });
        }
        syncRailSticky();
    }

    // The rail sticks at top: 0 while it fits above the pinned action bar. Taller than that, a negative
    // top makes it stick by its bottom edge instead: it scrolls only until its lowest controls clear the
    // bar, so they stay reachable without the whole rail scrolling away.
    function syncRailSticky() {
        var rail = document.querySelector('.bt-rail');
        if (!rail) { return; }
        var bar = document.querySelector('.bt-action-bar');
        var room = window.innerHeight - (bar ? bar.offsetHeight : 56);
        rail.style.top = Math.min(0, room - rail.offsetHeight) + 'px';
    }

    // -- Cohort mode (interactive tree | CSV manifest) --
    // Swaps the workspace between the interactive selection tree and the manifest dropzone; the
    // loaded-manifest state itself is rendered by renderCsvResults.
    // Resolving a manifest needs a source project, which the search-results entry point does not have.
    // Disable the mode up front rather than letting the user pick a file and only then be told.
    function configureCohortModes() {
        if (csvSourceProject()) { return; }
        $('.bt-cohort-mode[data-cohort="csv"]')
            .prop('disabled', true)
            .attr('title', 'Open Batch Transfer from a project to upload a manifest.');
    }

    function bindCohortToggle() {
        $(document).on('click', '.bt-cohort-mode', function() {
            if (this.disabled) { return; }
            cohortMode = $(this).data('cohort');
            $('.bt-cohort-mode').removeClass('selected');
            $(this).addClass('selected');
            var tree = cohortMode === 'tree';
            $('#bt-cohort-tree').toggle(tree);
            $('#bt-cohort-csv').toggle(!tree);
            if (!tree) { refreshCsvRequiredHint(); }
            updateSummary();
        });
    }

    // -- Anonymization band (Reimport only) --
    // Choice: destination anonymization only | add a custom Anon-Script. The custom script is a
    // batch-level template; per-item ${csv.*} values come from the manifest. Gated on the deployed
    // build's per-import support (GET /capabilities); Validate calls POST /validate/script.

    function fetchCapabilities() {
        $.ajax({
            type: 'GET',
            url: XNAT.url.rootUrl('/xapi/transfer/capabilities'),
            contentType: 'application/json',
            success: function(data) { caps = data; configureAnonSupport(); updateAnonBanner(); updateSummary(); },
            error: function() { caps = null; }
        });
    }

    // Collapse the control to a notice when the build lacks the Anon-Script param; otherwise show it.
    // (caps null = not yet loaded: assume supported, corrected on load.)
    function configureAnonSupport() {
        var supported = perImportAnonSupported();
        $('#bt-anon-seg').toggle(supported);
        $('#bt-anon-unsupported').toggle(!supported);
        if (!supported) {
            anonMode = 'none';
            anonVerdictValid = null;
            $('#bt-anon-editor').hide();
        }
    }

    function bindAnonBand() {
        $(document).on('click', '.bt-anon-mode', function() {
            anonMode = $(this).attr('data-anon');
            $('.bt-anon-mode').removeClass('selected');
            $(this).addClass('selected');
            $('#bt-anon-editor').toggle(anonMode === 'script');
            if (anonMode !== 'script') { anonVerdictValid = null; $('#bt-anon-verdict').empty(); }
            else { validateAnonScript(false); }
            updateAnonBanner();
            updateSummary();
        });
        $(document).on('click', '#bt-anon-validate', function() { validateAnonScript(true); });
        var t = null;
        $(document).on('input', '#bt-anon-script', function() {
            clearTimeout(t);
            t = setTimeout(function() { validateAnonScript(false); updateAnonBanner(); updateSummary(); }, 400);
        });
        // Preserve-source-labels toggles drive the "preserved labels not anonymized" action-bar chip,
        // so re-render the summary/chips whenever they change.
        $(document).on('change', '#bt-preserve-subject-label, #bt-preserve-session-label', function() {
            updateSummary();
        });
    }

    // The ${csv.*}-bindable columns for /validate/script: value + routing columns from the manifest
    // (CSV mode). Interactive mode has no manifest, so binding is not checked (null).
    function anonManifestColumns() {
        if (cohortMode === 'csv' && manifestResult) {
            return (manifestResult.value_columns || []).concat(manifestResult.routing_columns || []);
        }
        return null;
    }

    function validateAnonScript(showLoading) {
        var script = $('#bt-anon-script').val() || '';
        if (script.trim().length === 0) { $('#bt-anon-verdict').empty(); anonVerdictValid = null; return; }
        var body = { anon_script: script };
        var cols = anonManifestColumns();
        if (cols) { body.manifest_columns = cols; }
        if (showLoading) { $('#bt-anon-verdict').html('<div class="bt-verdict-line bt-muted">Validating...</div>'); }
        // Responses can land out of order, so only the newest request may write the verdict - otherwise a
        // slow verdict for an older draft can overwrite the current one and gate submit on stale text.
        var seq = ++anonValidateSeq;
        $.ajax({
            type: 'POST',
            url: XNAT.url.rootUrl('/xapi/transfer/validate/script'),
            contentType: 'application/json',
            data: JSON.stringify(body),
            success: function(v) {
                if (seq !== anonValidateSeq) { return; }
                anonVerdictValid = !!v.valid;
                renderAnonVerdict(v);
                updateSummary();
            },
            error: function(xhr) {
                if (seq !== anonValidateSeq) { return; }
                anonVerdictValid = false;
                $('#bt-anon-verdict').html('<div class="bt-verdict-line bt-verdict-bad">Validation request failed (' + xhr.status + ').</div>');
                updateSummary();
            }
        });
    }

    function renderAnonVerdict(v) {
        var h = '<div class="bt-verdict-line ' + (v.valid ? 'bt-verdict-ok' : 'bt-verdict-bad') + '">' +
                '<span>' + (v.valid ? '&#10003;' : '&#9888;') + '</span><div>' +
                (v.valid ? ('Script is valid' + (v.binding ? ' and every placeholder is bound.' : '.'))
                         : 'Script has issues - submit is blocked.') + '</div></div>';
        (v.errors || []).forEach(function(e) { h += verdictBad('Parse: ' + escapeHtml(e)); });
        (v.restriction_violations || []).forEach(function(r) { h += verdictBad('Restriction: ' + escapeHtml(r)); });
        if (v.binding && !v.binding.bound) {
            h += verdictBad('Unbound: ' + (v.binding.unbound || []).map(function(u) { return '${csv.' + escapeHtml(u) + '}'; }).join(', '));
        }
        var meta = [];
        if (v.dialect) { meta.push(escapeHtml(v.dialect)); }
        if (v.size_bytes != null) { meta.push(v.size_bytes + ' bytes'); }
        if (v.placeholders && v.placeholders.length) {
            meta.push('placeholders: ' + v.placeholders.map(function(p) { return 'csv.' + escapeHtml(p); }).join(', '));
        }
        if (v.message) { meta.push(escapeHtml(v.message)); }
        if (meta.length) { h += '<div class="bt-verdict-meta">' + meta.join(' &middot; ') + '</div>'; }
        $('#bt-anon-verdict').html(h);
    }

    function verdictBad(t) {
        return '<div class="bt-verdict-line bt-verdict-bad"><span>&#9888;</span><div>' + t + '</div></div>';
    }

    // Submit is blocked only when a custom script is present and known-invalid (advisory; submit
    // re-validates server-side regardless).
    function anonBlocks() {
        return hasCustomScript() && anonVerdictValid === false;
    }

    // The batch-level Anon-Script template to submit, or null (no custom script).
    function anonScriptForSubmit() {
        return hasCustomScript() ? ($('#bt-anon-script').val() || '') : null;
    }

    // -- Operation Selection --

    function bindOperationCards() {
        $(document).on('click', '.bt-op-card-sm', function() {
            $('.bt-op-card-sm').removeClass('selected');
            $(this).addClass('selected');
            selectedOp = $(this).data('op');
            toggleOperation(selectedOp);
        });
    }

    function toggleOperation(op) {
        $('#bt-data-search').attr('placeholder',
            op === 'Reimport' ? 'Filter sessions by name or datatype...' : 'Filter subjects and sessions by name or datatype...');

        // Single row set persists across operations, so selection carries naturally.
        // Reset any prior op's hide/disable, then apply the new op's eligibility.
        enableAllRows();
        applyOperationEligibility(op);

        // Leaving Reimport: subjects follow their session selections (a subject with no
        // selected sessions becomes deselected, rather than staying stuck at its old value).
        if (lastOp === 'Reimport' && op !== 'Reimport') {
            reconcileSubjectsFromSessions();
        }
        lastOp = op;

        // Restore visibility (expand state) / reapply the current filter. filterData() also
        // refreshes the counts + summary, so no separate count call is needed here.
        filterData($('#bt-data-search').val() || '');

        updateAnonBanner();
        updateOperationDetail();

        // In CSV mode the value/routing columns depend on the operation, so re-render on switch.
        if (cohortMode === 'csv') {
            refreshCsvRequiredHint();
            if (manifestResult) { renderCsvResults(); }
        }
    }

    // Hide+disable rows not eligible for the chosen operation (excluded from counts/submission
    // and from view). Eligibility is a per-row data attribute, uniform across operations:
    //   Share    -> data-shareable    (per project's sharing feature)
    //   Clone    -> data-cloneable    (per project's copying feature)
    //   Reimport -> data-reimportable (per item type - only image sessions; never feature-gated)
    // A non-reimportable subject is hidden, but its (reimportable) sessions still render:
    // showRows() treats a bt-import-hidden parent as logically visible.
    function applyOperationEligibility(op) {
        // In Reimport, a session's only children are image assessors, which are not re-importable.
        // Their expand arrows would open to nothing. Flag the body so CSS suppresses the arrows here.
        var body = document.getElementById('bt-data-body');
        if (body) body.classList.toggle('bt-reimport-mode', op === 'Reimport');

        var attr = (op === 'Clone') ? 'data-cloneable'
                 : (op === 'Reimport') ? 'data-reimportable'
                 : 'data-shareable';
        $('#bt-data-body tr').each(function() {
            if (this.getAttribute(attr) !== 'true') {
                $(this).addClass('bt-import-disabled bt-import-hidden');
            }
        });
    }

    // Re-enable everything an operation disabled/hid, and re-sync each checkmark to its
    // (preserved) .selected state. Selection itself is untouched.
    function enableAllRows() {
        $('#bt-data-body tr.bt-import-disabled').each(function() {
            $(this).removeClass('bt-import-disabled bt-import-hidden');
            syncCheckbox($(this));
        });
        $('#bt-data-body tr.bt-import-hidden').removeClass('bt-import-hidden');
    }

    // Does any selected, still-eligible descendant exist under parentId? Raw-DOM walk via the
    // index, short-circuiting on the first hit - no jQuery set building.
    function subtreeHasSelected(parentId) {
        var kids = childrenByParent[parentId];
        if (!kids) return false;
        for (var i = 0; i < kids.length; i++) {
            var el = kids[i];
            if (!el.classList.contains('bt-import-disabled') && el.classList.contains('selected')) return true;
            var cid = el.getAttribute('data-id');
            if (cid && subtreeHasSelected(cid)) return true;
        }
        return false;
    }

    // A subject is selected iff it has at least one selected, eligible descendant. Used when
    // leaving Reimport so the (hidden) subjects reflect the session choices made there.
    // Raw DOM + short-circuit + write only on an actual flip, so the common (unchanged) case
    // costs nothing - this is what removes the Reimport->Share/Clone lag at ~1,000 subjects.
    function reconcileSubjectsFromSessions() {
        var subs = document.querySelectorAll('#bt-data-body tr[data-type="subject"]');
        for (var i = 0; i < subs.length; i++) {
            var el = subs[i];
            var id = el.getAttribute('data-id');
            if (!id) continue;
            var want = subtreeHasSelected(id);
            if (want === el.classList.contains('selected')) continue;   // unchanged -> no DOM write
            el.classList.toggle('selected', want);
            syncCheckbox($(el));
        }
    }

    function syncCheckbox($row) {
        var $cb = $row.find('.bt-cb');
        if (!$cb.length) return;
        var want = $row.hasClass('selected');
        if (want === $cb.hasClass('checked')) return;   // checkmark already correct - skip the DOM write
        if (want) $cb.addClass('checked').html('&#10003;');
        else $cb.removeClass('checked').html('');
    }

    // -- Project Selection --

    function bindProjectSelect() {
        $(document).on('change', '#bt-project-select', function() {
            selectedProject = $(this).val() || null;
            if (selectedProject) {
                // Re-query the destination's anon status on every selection so the banner is
                // fresh, rather than reflecting whatever was cached when the page loaded.
                XNAT.plugin.batchtransfer.checkProjectAnon(selectedProject, function(anonEnabled) {
                    // The action-bar anon chips read the same status as the banner, so both have to be
                    // re-rendered once it arrives - the updateSummary() below runs before this resolves.
                    updateAnonBanner();
                    updateSummary();
                }, true);
            } else {
                updateAnonBanner();
            }
            updateSummary();
        });
    }

    // -- Data Table Interactions --

    function bindTableInteractions() {
        // Row checkbox toggle
        $(document).on('click', '#bt-data-body .bt-cb', function(e) {
            e.stopPropagation();
            var $row = $(this).closest('tr');
            // In Reimport mode, subjects are disabled for submission but still toggle children
            if ($row.hasClass('bt-import-disabled') && $row.data('type') === 'subject') {
                toggleSubjectChildren($row);
                return;
            }
            if ($row.hasClass('bt-import-disabled')) return;
            toggleRow(this);
        });

        // Header checkbox (select all)
        $(document).on('click', '#bt-header-cb', function() {
            toggleSelectAll();
        });

        // Expand/collapse
        $(document).on('click', '.bt-expand', function(e) {
            e.stopPropagation();
            toggleChildren(this);
        });

        // Select all link
        $(document).on('click', '.bt-select-all-link', function() {
            toggleSelectAll();
        });

        // Filter - debounced so fast typing doesn't run the filter on every keystroke.
        var filterTimer = null;
        $(document).on('input', '#bt-data-search', function() {
            var val = this.value;
            clearTimeout(filterTimer);
            filterTimer = setTimeout(function() { filterData(val); }, 150);
        });
    }

    function toggleRow(cb) {
        var $cb = $(cb);
        var $row = $cb.closest('tr');
        var isChecked = $cb.hasClass('checked');
        var rowId = $row.attr('data-id');

        if (isChecked) {
            $cb.removeClass('checked').html('');
            $row.removeClass('selected');
            if (rowId) deselectChildren(rowId);
        } else {
            $cb.addClass('checked').html('&#10003;');
            $row.addClass('selected');
            if (rowId) selectChildren(rowId);
            selectParentChain($row);
        }
        updateSummary();
    }

    // Toggle children of a subject in Reimport mode (subject itself stays disabled)
    function toggleSubjectChildren($row) {
        var rowId = $row.attr('data-id');
        if (!rowId) return;

        var $eligibleChildren = getAllDescendants(rowId).not('.bt-import-disabled');
        var allSelected = $eligibleChildren.length > 0 &&
            $eligibleChildren.filter('.selected').length === $eligibleChildren.length;

        if (allSelected) deselectChildren(rowId);
        else selectChildren(rowId);

        updateSummary();
    }

    function getAllDescendants(parentId) {
        var $result = $();
        childrenOf(parentId).each(function() {
            $result = $result.add(this);
            var childId = this.getAttribute('data-id');
            if (childId) {
                $result = $result.add(getAllDescendants(childId));
            }
        });
        return $result;
    }

    function deselectChildren(parentId) {
        childrenOf(parentId).each(function() {
            if (this.classList.contains('bt-import-disabled')) return;
            $(this).removeClass('selected');
            $(this).find('.bt-cb').removeClass('checked').html('');
            var childId = this.getAttribute('data-id');
            if (childId) deselectChildren(childId);
        });
    }

    function selectChildren(parentId) {
        childrenOf(parentId).each(function() {
            if (this.classList.contains('bt-import-disabled')) return;
            $(this).addClass('selected');
            $(this).find('.bt-cb').addClass('checked').html('&#10003;');
            var childId = this.getAttribute('data-id');
            if (childId) selectChildren(childId);
        });
    }

    function selectParentChain($row) {
        var parentId = $row.attr('data-parent');
        if (!parentId) return;
        var $parent = rowFor(parentId);
        if ($parent.length && !$parent.hasClass('bt-import-disabled')) {
            $parent.addClass('selected');
            $parent.find('.bt-cb').first().addClass('checked').html('&#10003;');
            selectParentChain($parent);
        }
    }

    // Show rows respecting expand/collapse state - WITHOUT reading layout. The previous version
    // called jQuery :visible on each parent, which forces a reflow; interleaved with the
    // show/hide writes that is layout thrashing (O(n) reflows), and it's what made clearing the
    // filter lag badly (clearing transitions ~all rows from display:none -> shown). Here we
    // compute each row's visibility top-down from the index + expand classes into a map and
    // write `display` in a single pass, so the browser reflows once.
    // Rows render in parent-before-child document order, so a parent's visibility is already
    // known when we reach its children. (.bt-import-hidden still hides reimport-hidden /
    // ineligible rows via CSS regardless of the inline display we set.)
    function showRows() {
        var shown = {};   // data-id -> did we set this row to display (independent of CSS !important hides)
        var rows = document.querySelectorAll('#bt-data-body tr');
        for (var i = 0; i < rows.length; i++) {
            var el = rows[i];
            var parentId = el.getAttribute('data-parent');
            var show;
            if (!parentId) {
                show = true;
            } else {
                var parentEl = rowById[parentId];
                // Treat a bt-import-hidden parent (e.g. a Reimport-hidden subject) as logically
                // visible so its sessions still render.
                var parentVisible = !!parentEl && (shown[parentId] || parentEl.classList.contains('bt-import-hidden'));
                var expandBtn = parentEl ? parentEl.querySelector('.bt-expand') : null;
                var parentExpanded = !!expandBtn && expandBtn.classList.contains('expanded');
                show = parentVisible && parentExpanded;
            }
            el.style.display = show ? '' : 'none';
            var id = el.getAttribute('data-id');
            if (id) shown[id] = show;
        }
    }

    function toggleChildren(btn) {
        var $btn = $(btn);
        var $row = $btn.closest('tr');
        var parentId = $row.attr('data-id');
        var isExpanded = $btn.hasClass('expanded');
        $btn.toggleClass('expanded');

        if (isExpanded) {
            hideDescendants(parentId);
        } else {
            childrenOf(parentId).each(function() {
                $(this).show();
                var childId = this.getAttribute('data-id');
                var childBtn = $(this).find('.bt-expand');
                if (childBtn.length && childBtn.hasClass('expanded') && childId) {
                    showDescendants(childId);
                }
            });
        }
    }

    function hideDescendants(parentId) {
        childrenOf(parentId).each(function() {
            $(this).hide();
            var childId = this.getAttribute('data-id');
            if (childId) hideDescendants(childId);
        });
    }

    function showDescendants(parentId) {
        childrenOf(parentId).each(function() {
            $(this).show();
            var childId = this.getAttribute('data-id');
            var childBtn = $(this).find('.bt-expand');
            if (childBtn.length && childBtn.hasClass('expanded') && childId) {
                showDescendants(childId);
            }
        });
    }

    function toggleSelectAll() {
        var $rows = getVisibleRows();
        var allChecked = $rows.length > 0 && $rows.filter('.selected').length === $rows.not('.bt-import-disabled').length;

        $rows.each(function() {
            if (this.classList.contains('bt-import-disabled')) return;
            if (allChecked) {
                $(this).removeClass('selected');
                $(this).find('.bt-cb').removeClass('checked').html('');
            } else {
                $(this).addClass('selected');
                $(this).find('.bt-cb').addClass('checked').html('&#10003;');
            }
        });

        var $headerCb = $('#bt-header-cb');
        if (allChecked) {
            $headerCb.removeClass('checked').html('');
        } else {
            $headerCb.addClass('checked').html('&#10003;');
        }
        updateSummary();
    }

    function getVisibleRows() {
        return $('#bt-data-body tr').filter(':visible');
    }

    function filterData(query) {
        var q = (query || '').toLowerCase();

        // The filter is a VIEW filter only: it shows/hides rows but never changes which
        // rows are selected, so the user's manual selections survive filtering and clearing.
        if (q.length === 0) {
            showRows();
            updateSummary();
            return;
        }

        // Raw DOM, matching against text cached at index time, one batched display pass - so
        // each keystroke stays cheap even at tens of thousands of rows.
        var rows = document.querySelectorAll('#bt-data-body tr');
        var i, el, parentId, parentEl;

        // Pass 1: rows whose cached text matches the query.
        var matching = {};
        for (i = 0; i < rows.length; i++) {
            el = rows[i];
            if ((el._btFilterText || '').indexOf(q) !== -1) {
                matching[el.getAttribute('data-id')] = true;
            }
        }

        // Pass 2: include ancestors of matches so the tree context shows (index walk; stop as
        // soon as an already-marked ancestor is reached).
        for (i = 0; i < rows.length; i++) {
            el = rows[i];
            if (matching[el.getAttribute('data-id')]) {
                parentId = el.getAttribute('data-parent');
                while (parentId && !matching[parentId]) {
                    matching[parentId] = true;
                    parentEl = rowById[parentId];
                    parentId = parentEl ? parentEl.getAttribute('data-parent') : null;
                }
            }
        }

        // Pass 3: show matches (and ancestors), hide the rest - raw display writes (one reflow).
        // Selection state is left untouched.
        for (i = 0; i < rows.length; i++) {
            el = rows[i];
            el.style.display = matching[el.getAttribute('data-id')] ? '' : 'none';
        }

        updateSummary();
    }

    // -- Summary + selection counts --
    // Single O(n) pass drives both the selection bar and the sidebar summary (previously two
    // or three separate full scans ran on every click).

    // Module cache of the last interactive-tree counts, so the action bar renders without re-walking
    // the table (updateSummary already made one O(n) pass).
    var lastTreeCount = { subjects: 0, sessions: 0, assessors: 0, selectedCount: 0, eligible: 0 };

    function updateSummary() {
        if (cohortMode === 'csv') { renderBuilderState(); renderActionSummary(); return; }

        var subjects = 0, sessions = 0, assessors = 0, selectedCount = 0, eligible = 0;
        var visibleEligible = 0, visibleSelected = 0;
        var rows = document.querySelectorAll('#bt-data-body tr');
        for (var i = 0; i < rows.length; i++) {
            var el = rows[i];
            if (el.classList.contains('bt-import-disabled')) continue;
            // Eligible for the chosen operation, whether or not the filter is currently hiding it -
            // it describes the cohort's scope, so it must not drop as the user types in the filter.
            eligible++;
            // Every hide on this table is an inline display write (filter, expand/collapse), so this
            // reads visibility without forcing a reflow the way :visible would.
            var visible = el.style.display !== 'none';
            if (visible) visibleEligible++;
            if (!el.classList.contains('selected')) continue;
            selectedCount++;
            if (visible) visibleSelected++;
            var type = el.getAttribute('data-type') || '';
            if (type === 'subject') {
                subjects++;
            } else if (type === 'assessor') {
                if (el.getAttribute('data-reimportable') === 'true') sessions++;
                else assessors++;
            }
        }

        // The count is over every selected row (matching what submit sends, filtered-out rows included),
        // but the link must describe what clicking it does - and toggleSelectAll acts on visible rows
        // only. Measuring the label on the same set keeps it from reading "Select all" while deselecting.
        $('#bt-sel-count').text(selectedCount);
        $('.bt-select-all-link').text(visibleEligible > 0 && visibleSelected === visibleEligible ? 'Deselect all' : 'Select all');
        lastTreeCount = { subjects: subjects, sessions: sessions, assessors: assessors,
                          selectedCount: selectedCount, eligible: eligible };
        renderBuilderState();
        renderActionSummary();
    }

    // -- Cohort builder panel header --
    // Reports which cohort mode is active and one line of state for it. The mode is switched from the
    // rail, so the pill is the panel's own statement of what it is currently building from.

    function renderBuilderState() {
        var el = document.getElementById('bt-builder-mode');
        if (!el) return;
        var pill = '<span class="bt-mode-pill">' + (cohortMode === 'csv' ? 'Manifest CSV' : 'Interactive') + '</span>';
        el.innerHTML = pill + '<span>' + (cohortMode === 'csv' ? csvBuilderState() : treeBuilderState()) + '</span>';
    }

    // Eligibility is per operation, so the noun follows it: Reimport counts only image sessions
    // (subjects are ineligible), everything else counts items.
    function treeBuilderState() {
        var n = lastTreeCount.eligible;
        var noun = selectedOp === 'Reimport' ? 'session' : 'item';
        return n + ' eligible ' + noun + (n === 1 ? '' : 's');
    }

    function csvBuilderState() {
        if (!manifestResult) { return 'no file uploaded'; }
        if (!manifestResult.required_present) { return 'missing required columns'; }
        var n = (manifestResult.rows || []).length;
        return n + ' row' + (n === 1 ? '' : 's') + ' resolved';
    }

    function formatItemList(counts) {
        var parts = [];
        if (counts.subjects > 0) parts.push(counts.subjects + ' ' + (counts.subjects === 1 ? 'subject' : 'subjects'));
        if (counts.sessions > 0) parts.push(counts.sessions + ' ' + (counts.sessions === 1 ? 'session' : 'sessions'));
        if (counts.assessors > 0) parts.push(counts.assessors + ' ' + (counts.assessors === 1 ? 'assessor' : 'assessors'));
        if (parts.length === 0) return '0 items';
        if (parts.length === 1) return parts[0];
        if (parts.length === 2) return parts.join(' and ');
        return parts.slice(0, -1).join(', ') + ', and ' + parts[parts.length - 1];
    }

    // -- Action bar: one-line summary + anon-status chips + submit label/gating --
    // Matches the mockup: "<Op> <N> <noun> - <src> -> <dest>" plus anonymization chips, and a
    // "Blocked: ..." note when submit is gated.

    function actionCountInfo() {
        if (cohortMode === 'csv') {
            var matched = (manifestResult && manifestResult.summary) ? manifestResult.summary.matched : 0;
            return { count: matched, noun: selectedOp === 'Reimport' ? 'sessions' : 'items' };
        }
        if (selectedOp === 'Reimport') { return { count: lastTreeCount.sessions, noun: 'sessions' }; }
        return { count: lastTreeCount.selectedCount, noun: 'items' };
    }

    // A status chip. The optional title is the full sentence behind the short chip text.
    function chip(kind, html, title) {
        return '<span class="bt-mini-' + kind + '"' + (title ? ' title="' + escapeHtml(title) + '"' : '') + '>' + html + '</span>';
    }

    // Which source labels the user chose to preserve (Reimport only): 'subject' and/or 'session'.
    function preservedLabels() {
        if (selectedOp !== 'Reimport') { return []; }
        var out = [];
        if ($('#bt-preserve-subject-label').is(':checked')) { out.push('subject'); }
        if ($('#bt-preserve-session-label').is(':checked')) { out.push('session'); }
        return out;
    }

    // Anonymization-status chips for the action bar. Reimport reflects destination-anon x custom-script;
    // Clone advertises hard-linking; Share shows none. These are the only place Reimport's anonymization
    // status and storage cost appear, so each chip's title carries the full explanation.
    function anonChipsHtml() {
        if (selectedOp === 'Reimport') {
            var script = hasCustomScript();
            var dest = selectedProject;
            var anon = projectAnonCache[dest];   // true / false / undefined (not yet known)
            var out;
            if (anon === true && script) {
                out = chip('warn', '&#9888; double anonymization',
                    'Both ' + dest + '\'s anonymization and your custom script will be applied.');
            } else if (anon === true) {
                out = chip('good', '&#10003; destination anonymization',
                    'Image sessions will be anonymized by ' + dest + '\'s anonymization on reimport.');
            } else if (anon === false && script) {
                out = chip('good', '&#10003; custom script',
                    dest + ' has no anonymization configured; your custom script will anonymize the reimported sessions.');
            } else if (anon === false) {
                out = chip('warn', '&#9888; not anonymized',
                    dest + ' has no anonymization configured and no custom script is set - reimported sessions will not be anonymized.');
            } else {
                out = script ? chip('good', '&#10003; custom script') : chip('good', '&#10003; destination anonymization');
            }
            // Anonymization (destination pipeline or custom script) is in effect, but preserving the source
            // XNAT labels re-introduces the original subject/session labels - which may carry identifiers and
            // are not touched by anonymization. Warn when both are set.
            var anonInEffect = anon === true || script;
            var preserved = preservedLabels();
            if (anonInEffect && preserved.length) {
                // Kept short: this is the longest chip, and the action bar is a single row. The
                // reason behind it is in its title and in the rail's label help.
                out += chip('warn', '&#9888; ' + preserved.join(' + ') + ' label'
                    + (preserved.length > 1 ? 's' : '') + ' not anonymized',
                    'Preserved source labels are kept as they are; anonymization does not change XNAT labels.');
            }
            return out + chip('caution', '&#9888; duplicates storage',
                'Reimported data can take up significant storage in the destination project, and reimporting may take some time.');
        }
        if (selectedOp === 'Clone') { return chip('good', '&#10003; hard-linked, no extra storage'); }
        return '';
    }

    // Short, user-facing reasons submit is blocked (advisory; the server re-validates regardless).
    function submitBlockReasons() {
        if (!selectedProject) { return ['select a destination']; }
        if (cohortMode === 'csv') {
            if (!manifestResult) { return ['upload a manifest']; }
            if (!manifestResult.required_present) { return ['manifest missing required columns']; }
            var matched = manifestResult.summary ? manifestResult.summary.matched : 0;
            if (matched === 0) { return ['no matched rows']; }
            var reasons = [];
            if (selectedOp === 'Reimport') {
                var errCount = (manifestResult.rows || []).filter(function(row) {
                    return row.status === 'matched' && (row.value_errors || []).length > 0;
                }).length;
                if (errCount) { reasons.push(errCount + ' row(s) have a value error'); }
            }
            if (anonBlocks()) { reasons.push('fix the anonymization script'); }
            return reasons;
        }
        // interactive tree
        var treeReasons = [];
        if (actionCountInfo().count === 0) {
            treeReasons.push('select at least one ' + (selectedOp === 'Reimport' ? 'session' : 'item'));
        }
        if (anonBlocks()) { treeReasons.push('fix the anonymization script'); }
        return treeReasons;
    }

    function renderActionSummary() {
        var info = actionCountInfo();
        var src = config.projectContext || 'source';
        var dest = selectedProject || '-';
        var reasons = submitBlockReasons();
        // The blocked reason is rendered INSIDE the summary so it flows inline with the status chips
        // in the same wrapping row. As a separate flex sibling it always took a line of its own,
        // making the sticky bar a row taller than it needed to be.
        var note = reasons.length
            ? '<span class="bt-submit-note">Blocked: ' + escapeHtml(reasons.join('; ')) + '</span>'
            : '';
        $('#bt-action-summary').html(
            '<span><strong>' + selectedOp + ' ' + info.count + ' ' + info.noun + '</strong> &middot; ' +
            escapeHtml(src) + ' &#10142; ' + escapeHtml(dest) + '</span>' + anonChipsHtml() + note);
        $('#bt-submit-btn').text(selectedOp + ' ' + info.count + ' ' + info.noun);
        $('#bt-submit-btn').prop('disabled', submitInFlight || reasons.length > 0);
    }

    // -- Cohort: CSV manifest mode --
    // Upload a manifest -> POST /validate/manifest -> render the resolved cohort (matched rows +
    // not-found-with-reasons + value/routing columns for Reimport). file_count is computed lazily,
    // per row, on expand (a re-POST with file_count=true). Share/Clone drop the value/routing/Files
    // columns and show an advisory that those columns are ignored.

    var manifestResult = null;      // last /validate/manifest response
    var manifestFileName = '';      // uploaded file name (status + confirmation)
    var manifestExpanded = {};      // row index -> expanded?
    var manifestFileCounts = {};    // row index -> reimportable DICOM count (lazy, Reimport only)
    var manifestFileCountErr = {};  // row index -> the count request failed (so the row stops saying "counting")

    // -- Manifest pagination --------------------------------------------------------------------------
    // Offset-based pager: prev/next around a "start-end of total" range, plus one "Show all". Slicing is
    // by manifest-row index, so renderMatchedRow's expand/file-count state (keyed by row.index) lines up
    // with whatever page is on screen.
    // Only the manifest table pages; the interactive tree renders every row (its selection state lives in
    // the DOM, so rows can't be sliced out of it).
    // The page size is FIXED. Sizing it to the visible area only mattered while the workspace was a
    // bounded scroll box; the page is the only scroller now, so a page taller than the viewport simply
    // scrolls. Measuring instead produced 4 rows in the ordinary Reimport case, where an About panel plus
    // the script editor leave ~119px above the action bar - far too few to review a cohort. Nothing here
    // reacts to window size any more.
    var MANIFEST_PAGE_SIZE = 25;
    var manifestStart = 0;             // index of the first row on the current page
    var manifestPageStack = [];        // start indices of prior pages, for Prev
    var manifestShowAll = false;       // "Show all" -> render every row (the page scrolls)

    function manifestTotal() { return (manifestResult && manifestResult.rows) ? manifestResult.rows.length : 0; }

    function manifestPagerHtml(start, end, total) {
        // No pager when a single paginated page already shows everything.
        if (!manifestShowAll && start === 0 && end >= total) { return ''; }
        var prevDis = manifestShowAll || manifestPageStack.length === 0;
        var nextDis = manifestShowAll || end >= total;
        return '<div class="bt-pager"><span class="bt-pager-nav">' +
               '<button type="button" class="bt-pager-arrow" id="bt-page-prev"' + (prevDis ? ' disabled' : '') + '>&#8249;</button>' +
               '<span class="bt-pager-range">' + (total ? start + 1 : 0) + '-' + end + ' of ' + total + '</span>' +
               '<button type="button" class="bt-pager-arrow" id="bt-page-next"' + (nextDis ? ' disabled' : '') + '>&#8250;</button>' +
               '</span><span class="bt-pager-spacer"></span>' +
               '<a class="bt-pager-showall" id="bt-page-showall">' + (manifestShowAll ? 'Paginate' : 'Show all') + '</a></div>';
    }

    function csvSourceProject() { return config.projectContext || null; }

    function bindCsvUpload() {
        $(document).on('click', '#bt-csv-choose', function() { $('#bt-csv-file').click(); });
        $(document).on('change', '#bt-csv-file', function() {
            if (this.files && this.files[0]) { uploadManifest(this.files[0]); }
        });
        $(document).on('dragover', '#bt-dropzone', function(e) { e.preventDefault(); $(this).addClass('dragover'); });
        $(document).on('dragleave', '#bt-dropzone', function() { $(this).removeClass('dragover'); });
        $(document).on('drop', '#bt-dropzone', function(e) {
            e.preventDefault();
            $(this).removeClass('dragover');
            var dt = e.originalEvent.dataTransfer;
            if (dt && dt.files && dt.files[0]) { uploadManifest(dt.files[0]); }
        });
        $(document).on('click', '#bt-csv-replace', function() { resetManifest(); });
        $(document).on('click', '.bt-csv-caret, .bt-files-lazy', function() {
            toggleManifestRow(parseInt($(this).attr('data-row'), 10));
        });
        // Simple pager: prev/next walk the page by row offset; Show all toggles pagination off (scrolls).
        $(document).on('click', '#bt-page-prev', function() {
            if (this.disabled || !manifestPageStack.length) return;
            manifestStart = manifestPageStack.pop();
            renderCsvResults();
        });
        $(document).on('click', '#bt-page-next', function() {
            if (this.disabled || manifestStart + MANIFEST_PAGE_SIZE >= manifestTotal()) return;
            manifestPageStack.push(manifestStart);
            manifestStart += MANIFEST_PAGE_SIZE;
            renderCsvResults();
        });
        $(document).on('click', '#bt-page-showall', function() {
            manifestShowAll = !manifestShowAll; manifestStart = 0; manifestPageStack = [];
            renderCsvResults();
        });
    }

    function refreshCsvRequiredHint() {
        var routing = (selectedOp === 'Reimport')
            ? ' &middot; optional routing: <span class="bt-mono">destination_subject_label</span>, <span class="bt-mono">destination_session_label</span>'
            : '';
        $('#bt-csv-required').html('Required: <span class="bt-mono">source_subject_label</span>, ' +
            '<span class="bt-mono">source_session_label</span>' + routing);
    }

    function resetManifest() {
        manifestResult = null; manifestFileName = ''; manifestExpanded = {};
        manifestFileCounts = {}; manifestFileCountErr = {};
        manifestStart = 0; manifestPageStack = []; manifestShowAll = false;
        $('#bt-csv-file').val('');
        $('#bt-csv-results').empty();
        $('#bt-dropzone').show();
        updateSummary();
    }

    function uploadManifest(file) {
        var src = csvSourceProject();
        if (!src) {
            xmodal.message('Batch Transfer', 'A source project is required to resolve a manifest. Open Batch Transfer from a project.');
            return;
        }
        manifestFileName = file.name || 'manifest.csv';
        var fd = new FormData();
        fd.append('manifest', file);
        fd.append('source_project', src);
        fd.append('mode', selectedOp);
        $('#bt-csv-results').html('<div class="bt-csv-loading">Resolving manifest...</div>');
        $.ajax({
            type: 'POST',
            url: XNAT.url.rootUrl('/xapi/transfer/validate/manifest'),
            data: fd,
            processData: false,
            contentType: false,
            success: function(data) {
                manifestResult = data;
                manifestExpanded = {}; manifestFileCounts = {}; manifestFileCountErr = {};
                manifestStart = 0; manifestPageStack = []; manifestShowAll = false;
                $('#bt-dropzone').hide();
                renderCsvResults();
                // Re-check binding against the new manifest columns if a custom script is in play.
                if (anonMode === 'script') { validateAnonScript(false); }
                updateSummary();
            },
            error: function(xhr) {
                var msg = (xhr.responseText && xhr.responseText.length < 300) ? xhr.responseText : ('HTTP ' + xhr.status);
                $('#bt-csv-results').html('<div class="bt-csv-error">Could not resolve the manifest: ' + escapeHtml(msg) + '</div>');
            }
        });
    }

    function renderCsvResults() {
        if (!manifestResult) { $('#bt-csv-results').empty(); return; }
        var r = manifestResult;
        // Manifest bar: the loaded file name + a control to clear it and return to the dropzone. Rendered
        // whenever a manifest is loaded (including the missing-columns error) so it is always clearable.
        var bar = '<div class="bt-manifest-bar"><span class="bt-csv-file">&#128196; ' +
                  escapeHtml(manifestFileName) + '</span>' +
                  '<a id="bt-csv-replace" class="bt-csv-replace-link">Replace file</a></div>';
        if (!r.required_present) {
            $('#bt-csv-results').html(bar + '<div class="bt-csv-error">Manifest is missing required column(s): ' +
                escapeHtml((r.missing_columns || []).join(', ')) + '.</div>');
            return;
        }
        var reimport = selectedOp === 'Reimport';

        var html = bar;
        // Share/Clone: routing/value columns are ignored - say so rather than drop them silently.
        if (!reimport && ((r.routing_columns || []).length || (r.value_columns || []).length)) {
            html += '<div class="bt-anon-banner bt-anon-ok" style="margin:0 0 12px;"><span class="bt-anon-icon">&#8505;</span>' +
                '<div>Routing and ${csv.*} value columns in this manifest are ignored for ' + selectedOp +
                '; only source_subject_label and source_session_label are used.</div></div>';
        }

        var matched = (r.summary && r.summary.matched) || 0;
        var notFound = (r.summary && r.summary.not_found) || 0;
        html += '<div class="bt-csv-head"><span class="bt-csv-ok"><strong>' + matched + '</strong> matched</span>' +
                '<span class="bt-csv-miss"><strong>' + notFound + '</strong> not found</span></div>';

        // Fixed column set: where the session comes from, and what it becomes. Value columns are not
        // shown as columns - a manifest can carry any number of them, they were what made the table
        // scroll sideways, and a row's values are still on its expanded compiled-script preview (plus
        // any bad value still reports itself on the row's own error line).
        var cols = '<th class="bt-csv-caret-col"></th><th>Source subject</th><th>Source session</th>';
        if (reimport) {
            cols += '<th class="bt-route-head">Destination subject</th>' +
                    '<th class="bt-route-head">Destination session</th><th>Files</th>';
        }
        var span = 3 + (reimport ? 3 : 0);

        // Render the current page: MANIFEST_PAGE_SIZE rows from manifestStart (or every row on Show all).
        var allRows = r.rows || [];
        var total = allRows.length;
        var start = manifestShowAll ? 0 : Math.min(Math.max(0, manifestStart), total > 0 ? total - 1 : 0);
        manifestStart = start;
        var count = manifestShowAll ? total : MANIFEST_PAGE_SIZE;
        var end = Math.min(start + count, total);

        var body = '';
        allRows.slice(start, end).forEach(function(row) {
            body += (row.status === 'matched')
                ? renderMatchedRow(row, reimport, span)
                : renderNotFoundRow(row, span);
        });

        // Horizontally-scrollable wrapper (not .bt-table-wrap, which clips): the manifest table can be
        // wider than the workspace once routing/value columns are present, so it needs its own x-scroll.
        html += '<div class="bt-cohort-scroll"><table class="bt-table bt-cohort-table"><thead><tr>' +
                cols + '</tr></thead><tbody>' + body + '</tbody></table></div>';
        html += manifestPagerHtml(start, end, total);
        $('#bt-csv-results').html(html);
    }

    function renderMatchedRow(row, reimport, span) {
        var idx = row.index;
        var hasErr = reimport && (row.value_errors || []).length > 0;
        var hasWarn = reimport && (row.value_warnings || []).length > 0;
        // The expand caret only reveals content for Reimport (file count + compiled script preview);
        // Share/Clone rows have nothing to drop down, so render an empty status cell, not a dead arrow.
        // (The cell is kept so the not-found ✗ column stays aligned across rows.)
        var caretCell = reimport
            ? '<td><span class="bt-csv-caret" data-row="' + idx + '">' + (manifestExpanded[idx] ? '&#9662;' : '&#9656;') + '</span></td>'
            : '<td></td>';
        var tr = '<tr class="bt-csv-row' + (hasErr ? ' bt-csv-rowerr' : '') + '">' +
                 caretCell +
                 '<td class="bt-mono">' + escapeHtml(row.source_subject_label) + '</td>' +
                 '<td class="bt-mono">' + escapeHtml(row.source_session_label) + '</td>';
        if (reimport) {
            tr += destinationCell(row, 'subject') + destinationCell(row, 'session');
            // Short labels on purpose: this cell's text sets the Files column width, and the dotted
            // underline already marks it as clickable. The title carries the full explanation.
            var fc = manifestFileCounts[idx];
            tr += '<td>' + (fc != null ? fc
                : manifestFileCountErr[idx]
                    ? '<span class="bt-muted" title="The file count could not be retrieved. Expand the row to retry.">n/a</span>'
                    : '<span class="bt-files-lazy" data-row="' + idx + '"'
                        + ' title="Count the reimportable DICOM files in this session">count</span>') + '</td>';
        }
        tr += '</tr>';

        if (manifestExpanded[idx] && reimport) {
            var fc2 = manifestFileCounts[idx];
            var detail = '<p class="bt-csv-detail-line">Reimportable DICOM files: <strong>' +
                (fc2 != null ? fc2 : manifestFileCountErr[idx] ? 'unavailable' : 'counting...') + '</strong></p>';
            if (hasCustomScript()) {
                detail += '<p class="bt-csv-detail-line bt-muted">Compiled DicomEdit for this session:</p>' +
                    '<pre class="bt-script-preview">' + escapeHtml(compileScriptForRow(row)) + '</pre>';
            }
            tr += '<tr class="bt-csv-detailrow"><td></td><td colspan="' + (span - 1) + '">' + detail + '</td></tr>';
        }
        if (hasErr) {
            tr += '<tr class="bt-csv-noterow bt-csv-errrow"><td></td><td colspan="' + (span - 1) + '">' +
                  escapeHtml(row.value_errors.join('; ')) + '</td></tr>';
        } else if (hasWarn) {
            tr += '<tr class="bt-csv-noterow bt-csv-warnrow"><td></td><td colspan="' + (span - 1) + '">' +
                  escapeHtml(row.value_warnings.join('; ')) + '</td></tr>';
        }
        return tr;
    }

    // What the session will actually be called in the destination, by the same precedence the server
    // applies at import: an explicit manifest destination_*_label wins, else the preserved source label,
    // else the label is derived from DICOM tags during import and cannot be known here. Showing the
    // resolved value (not just the manifest column) is the point of these two columns.
    function destinationCell(row, which) {
        var subject = which === 'subject';
        var explicit = subject ? row.destination_subject_label : row.destination_session_label;
        if (explicit) {
            return '<td class="bt-mono bt-route">' + escapeHtml(explicit) + '</td>';
        }
        var preserved = subject
            ? $('#bt-preserve-subject-label').is(':checked')
            : $('#bt-preserve-session-label').is(':checked');
        if (preserved) {
            return '<td class="bt-mono bt-dest-inherited" title="Preserved from the source label">'
                 + escapeHtml(subject ? row.source_subject_label : row.source_session_label) + '</td>';
        }
        return '<td class="bt-muted" title="Derived from DICOM tags when the session is imported">'
             + 'from DICOM</td>';
    }

    function renderNotFoundRow(row, span) {
        var head = '<td><span class="bt-csv-x">&#10007;</span></td>' +
                   '<td class="bt-mono">' + escapeHtml(row.source_subject_label) + '</td>' +
                   '<td class="bt-mono">' + escapeHtml(row.source_session_label) + '</td>';
        // Reimport (>=4 cols): inline reason spans the rest. Share/Clone (3 cols): reason on a sub-row.
        if (span > 3) {
            return '<tr class="bt-csv-missrow">' + head +
                   '<td colspan="' + (span - 3) + '" class="bt-csv-reason"><strong>' + escapeHtml(row.status) + '</strong></td></tr>';
        }
        return '<tr class="bt-csv-missrow">' + head + '</tr>' +
               '<tr class="bt-csv-missrow"><td></td><td colspan="2" class="bt-csv-reason"><strong>' + escapeHtml(row.status) + '</strong></td></tr>';
    }

    function toggleManifestRow(idx) {
        manifestExpanded[idx] = !manifestExpanded[idx];
        if (manifestExpanded[idx] && selectedOp === 'Reimport' && manifestFileCounts[idx] == null) {
            delete manifestFileCountErr[idx];   // re-expanding retries a count that failed
            fetchFileCount(idx);
        }
        renderCsvResults();
    }

    // Lazy per-row file_count: re-POST a one-row manifest with file_count=true (only source labels
    // are needed for the count).
    function fetchFileCount(idx) {
        var row = findManifestRow(idx);
        if (!row) return;
        var csv = 'source_subject_label,source_session_label\n' +
                  csvCell(row.source_subject_label) + ',' + csvCell(row.source_session_label) + '\n';
        $.ajax({
            type: 'POST',
            url: XNAT.url.rootUrl('/xapi/transfer/validate/manifest'),
            contentType: 'application/json',
            data: JSON.stringify({ source_project: csvSourceProject(), mode: selectedOp, file_count: true, manifest_csv: csv }),
            success: function(data) {
                var rows = (data && data.rows) || [];
                if (rows.length && rows[0].file_count != null) {
                    manifestFileCounts[idx] = rows[0].file_count;
                } else {
                    manifestFileCountErr[idx] = true;
                }
                if (manifestExpanded[idx]) { renderCsvResults(); }
            },
            // Without this the row is stuck reading "counting..." forever.
            error: function() {
                manifestFileCountErr[idx] = true;
                if (manifestExpanded[idx]) { renderCsvResults(); }
            }
        });
    }

    function findManifestRow(idx) {
        var rows = (manifestResult && manifestResult.rows) || [];
        for (var i = 0; i < rows.length; i++) { if (rows[i].index === idx) return rows[i]; }
        return null;
    }

    function csvCell(v) {
        v = (v == null) ? '' : String(v);
        return /[",\n]/.test(v) ? '"' + v.replace(/"/g, '""') + '"' : v;
    }

    // The value a ${csv.<col>} placeholder binds to for a given row: routing columns come from the
    // recognized destination_*_label fields, everything else from the value-column map.
    function csvColValue(row, col) {
        if (col === 'destination_subject_label') return row.destination_subject_label;
        if (col === 'destination_session_label') return row.destination_session_label;
        return row.csv_values ? row.csv_values[col] : undefined;
    }

    // Client-side preview of the compiled DicomEdit for one row: substitute ${csv.*} from the row's
    // values (the authoritative compile is server-side at submit). Unbound placeholders stay visible.
    function compileScriptForRow(row) {
        var tpl = $('#bt-anon-script').val() || '';
        return tpl.replace(/\$\{csv\.([A-Za-z0-9_]+)\}/g, function(m, col) {
            var v = csvColValue(row, col);
            return (v != null) ? v : m;
        });
    }

    function escapeHtml(s) {
        if (s == null) return '';
        return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    // -- Submit --

    function buildTreeItems() {
        var preserveSubjectLabel = (selectedOp === 'Reimport') && $('#bt-preserve-subject-label').is(':checked');
        var preserveSessionLabel = (selectedOp === 'Reimport') && $('#bt-preserve-session-label').is(':checked');
        var items = [];
        $('#bt-data-body tr.selected').not('.bt-import-disabled').each(function() {
            var id = $(this).data('id');
            if (!id) return;
            var item = { id: id, mode: selectedOp, destination_project: selectedProject };
            if (selectedOp === 'Reimport') {
                item.preserve_subject_label = preserveSubjectLabel;
                item.preserve_session_label = preserveSessionLabel;
            }
            items.push(item);
        });
        return items;
    }

    function buildCsvItems() {
        if (!manifestResult) return [];
        var preserveSubjectLabel = (selectedOp === 'Reimport') && $('#bt-preserve-subject-label').is(':checked');
        var preserveSessionLabel = (selectedOp === 'Reimport') && $('#bt-preserve-session-label').is(':checked');
        var items = [];
        (manifestResult.rows || []).forEach(function(row) {
            if (row.status !== 'matched' || !row.resolved_id) return;
            var item = { id: row.resolved_id, mode: selectedOp, destination_project: selectedProject };
            if (selectedOp === 'Reimport') {
                item.preserve_subject_label = preserveSubjectLabel;
                item.preserve_session_label = preserveSessionLabel;
                if (row.csv_values) { item.csv_values = row.csv_values; }
                if (row.destination_subject_label) { item.destination_subject_label = row.destination_subject_label; }
                if (row.destination_session_label) { item.destination_session_label = row.destination_session_label; }
            }
            items.push(item);
        });
        return items;
    }

    // Inline-styled confirmation body. xModalConfirm ignores width props and plugin CSS may not load
    // inside the modal, so the wrapper sets min-width inline and every element is inline-styled. It
    // restates the irreversible facts (cohort + exclusions, operation, anonymization, labels) with a
    // no-rollback caution.
    function dlgRow(k, v) {
        return '<div style="display:flex;padding:8px 12px;border-bottom:1px solid #f1f3f4;">' +
               '<span style="width:130px;color:#5f6368;flex-shrink:0;">' + k + '</span><span>' + v + '</span></div>';
    }

    function buildConfirmationHtml(items) {
        var isCsv = cohortMode === 'csv';
        var count = items.length;
        var noun = (selectedOp === 'Reimport' ? 'session' : 'item') + (count === 1 ? '' : 's');
        var excluded = (isCsv && manifestResult && manifestResult.summary) ? manifestResult.summary.not_found : 0;
        var src = config.projectContext ? escapeHtml(config.projectContext) : 'the source project';
        var dest = escapeHtml(selectedProject);
        var destAnon = projectAnonCache[selectedProject];
        var script = hasCustomScript();

        var rows = dlgRow('Cohort', isCsv
            ? escapeHtml(manifestFileName) + ' &middot; <strong>' + count + ' matched</strong>' +
              (excluded ? ' <span style="color:#b06000;">(' + excluded + ' not-found excluded)</span>' : '')
            // lastTreeCount is current whenever the tree is the active cohort, which is the only case
            // this branch runs in, so the counts need no second walk of the table.
            : 'interactive &middot; <strong>' + escapeHtml(formatItemList(lastTreeCount)) + '</strong>');
        rows += dlgRow('Operation', selectedOp + ' &middot; ' + src + ' to ' + dest);

        if (selectedOp === 'Reimport') {
            var anonText;
            if (destAnon === true && script) { anonText = 'Destination anonymization <strong>+ custom script (double anonymization)</strong>'; }
            else if (destAnon === true) { anonText = 'Destination anonymization'; }
            else if (destAnon === false && script) { anonText = 'Custom script only'; }
            else if (destAnon === false) { anonText = '<span style="color:#c5221f;">None - sessions will not be anonymized</span>'; }
            else { anonText = script ? 'Destination anonymization <strong>+ custom script</strong>' : 'Destination anonymization'; }
            rows += dlgRow('Anonymization', anonText);
            if (script) { rows += dlgRow('Custom script', 'applied per session' + (isCsv ? ' (values from manifest)' : '')); }
            var preserve = [];
            if ($('#bt-preserve-subject-label').is(':checked')) { preserve.push('subject'); }
            if ($('#bt-preserve-session-label').is(':checked')) { preserve.push('session'); }
            var labelText = preserve.length ? 'preserve source ' + preserve.join(' + ') + ' label(s)' : 'derive from DICOM tags';
            if (isCsv) { labelText += '; per-row destination_*_label override'; }
            rows += dlgRow('Labels', labelText);
        } else if (selectedOp === 'Clone') {
            rows += dlgRow('Storage', 'Cloned files are hard-linked; no additional storage at clone time');
            rows += dlgRow('Anonymization', 'None - Clone copies data as-is');
        } else {
            rows += dlgRow('Anonymization', 'None - Share makes data visible as-is');
        }

        var lead = (selectedOp === 'Reimport' ? 'Reimport ' : selectedOp + ' ') +
            '<strong>' + count + ' ' + noun + '</strong> from <strong>' + src + '</strong> into <strong>' + dest + '</strong>.' +
            (selectedOp === 'Share' ? '' : ' This is a one-time operation.');
        var caution = 'Data is written to the destination archive; there is no automatic rollback.' +
            (excluded ? ' The ' + excluded + ' not-found row(s) will not be transferred.' : '');

        return '<div style="min-width:460px;max-width:520px;font-size:13.5px;line-height:1.6;color:#3c4043;">' +
               '<p style="margin:0 0 14px;">' + lead + '</p>' +
               '<div style="border:1px solid #e8eaed;border-radius:8px;overflow:hidden;font-size:12.5px;">' + rows + '</div>' +
               '<p style="margin:12px 0 0;font-size:12px;color:#b06000;background:#fef7e0;border:1px solid #f9d77a;border-radius:6px;padding:8px 11px;">&#9888; ' + caution + '</p>' +
               '</div>';
    }

    XNAT.plugin.batchtransfer.submitTransfer = function() {
        if (!selectedProject) {
            xmodal.message('Batch Transfer', 'Please select a destination ' + XNAT.app.displayNames.singular.project.toLowerCase());
            return false;
        }

        var csv = cohortMode === 'csv';
        var items = csv ? buildCsvItems() : buildTreeItems();
        if (items.length === 0) {
            xmodal.message('Batch Transfer', csv ? 'No matched sessions to transfer.' : 'Please select at least one item');
            return false;
        }

        if (anonBlocks()) {
            xmodal.message('Batch Transfer', 'The custom anonymization script is not valid. Fix it (Validate) before submitting.');
            return false;
        }

        var noun = (selectedOp === 'Reimport' ? 'session' : 'item') + (items.length === 1 ? '' : 's');
        xModalConfirm({
            title: selectedOp + ' ' + items.length + ' ' + noun + '?',
            content: buildConfirmationHtml(items),
            okAction: function() {
                // Guard against a second submission while one is already in flight.
                if (submitInFlight) return;
                submitInFlight = true;
                $('#bt-submit-btn').prop('disabled', true);

                var batchTransfer = {
                    requests: items,
                    tracking_id: 'batch_transfer_' + Date.now()
                };
                // Batch-level Anon-Script template (Reimport + custom script); per-item ${csv.*} values
                // ride each request's csv_values. Omitted entirely when no custom script is set.
                var anonScript = anonScriptForSubmit();
                if (anonScript) {
                    batchTransfer.anon_script = anonScript;
                    batchTransfer.anon_replace_pipeline = false;
                }
                $.ajax({
                    type: 'POST',
                    url: XNAT.url.rootUrl('/xapi/transfer'),
                    data: JSON.stringify(batchTransfer),
                    contentType: 'application/json',
                    success: function() {
                        XNAT.ui.banner.top(3000, 'Transfer request submitted.', 'success');
                        XNAT.app.activityTab.start(
                            'Batch Transfer (' + batchTransfer.requests.length + ' items)',
                            batchTransfer.tracking_id,
                            'XNAT.plugin.batchtransfer.updateBatchTransferProgress'
                        );
                    },
                    error: function() {
                        xmodal.message('Error', 'Failed to submit transfer request.');
                    },
                    complete: function() {
                        submitInFlight = false;
                        // Re-render rather than force-enabling, so the button still honours any
                        // outstanding block reason instead of being clickable regardless.
                        renderActionSummary();
                    }
                });
            }
        });
    };

}));

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
    var selectedAnon = null;
    var config = {};
    var submitInFlight = false;

    // Row index, built once after load (rows are static — only classes/visibility change).
    // Replaces per-row `$('#bt-data-body tr[data-id=…]')` scans that made tree walks O(n²).
    var rowById = {};              // data-id  -> DOM <tr>
    var childrenByParent = {};     // data-parent -> [DOM <tr>, ...]

    var operationDetails = {
        Share: 'Shares the selected data into the destination project. ' +
               'The data is not copied - it remains the same data element, owned by the source project, ' +
               'and changes made there are reflected in the destination.',
        Clone: 'Creates an independent copy of the selected data in the destination project. ' +
               'The cloned data is editable separately from the source and is not anonymized. ' +
               'Cloned session files are hard-linked to the source, requiring no additional disk space at the time of cloning.',
        Reimport: 'Reimports the selected image sessions through the destination project\'s ' +
                  'anonymization pipeline. Only DICOM files are transferred; target session metadata ' +
                  'is derived from DICOM tags and/or anonymization parameters.'
    };

    // ── Initialization ──

    XNAT.plugin.batchtransfer.init = function(cfg) {
        config = cfg || {};
        selectedOp = 'Share';
        lastOp = 'Share';

        if (config.presetDestination) {
            selectedProject = config.presetDestination;
            XNAT.plugin.batchtransfer.checkProjectAnon(selectedProject, function(anonEnabled) {
                selectedAnon = anonEnabled;
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

    // ── Project Loading ──

    function loadProjects() {
        window.projectLoader = new ProjectLoader();
        window.projectLoader.onLoadComplete.subscribe(function() {
            var projects = window.projectLoader.list;
            if (config.projectContext) {
                projects = projects.filter(function(p) { return p.id !== config.projectContext; });
            }
            renderProjects(document.getElementById('bt-project-select'), projects);
            $('#bt-project-select').prop('disabled', false);
            fetchAnonForProjects(projects);
        });
        window.projectLoader.init();
    }

    // Cache each project's anonymization state up-front so the operation-detail
    // panel banner can render synchronously when the user picks a destination.
    function fetchAnonForProjects(projects) {
        projects.forEach(function(p) {
            XNAT.plugin.batchtransfer.checkProjectAnon(p.id, function(anonEnabled) {
                projectAnonCache[p.id] = anonEnabled;
            });
        });
    }

    // ── Anonymization Check ──

    XNAT.plugin.batchtransfer.checkProjectAnon = function(projectId, callback) {
        if (!projectId) {
            callback(null, 'No project selected');
            return;
        }
        if (projectAnonCache.hasOwnProperty(projectId)) {
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

    // ── Operation Banners ──
    // Renders 0–2 alert banners into the "About this transfer" panel:
    //   1) Anon-status banner — only "warn" or "good" severities; never shown when there's
    //      nothing actionable to say (e.g. Share into a non-anonymized project).
    //   2) Storage caution banner — shown for Reimport (its storage/time cost is a property
    //      of the operation). Clone's storage note lives in its operation description instead.

    function updateAnonBanner() {
        var area = document.getElementById('bt-op-detail-warning-area');
        if (!area) return;

        var html = '';

        // Anon banner — needs a destination + a known anon state.
        if (selectedProject) {
            var anonEnabled = projectAnonCache[selectedProject];
            if (anonEnabled === true) {
                if (selectedOp === 'Share') {
                    html += '<div class="bt-anon-banner bt-anon-warn">' +
                        '<span class="bt-anon-icon">&#9888;</span>' +
                        '<div><strong>' + selectedProject + ' has anonymization enabled.</strong> Sharing does not apply anonymization - the data is shared as-is.</div></div>';
                } else if (selectedOp === 'Clone') {
                    html += '<div class="bt-anon-banner bt-anon-warn">' +
                        '<span class="bt-anon-icon">&#9888;</span>' +
                        '<div><strong>' + selectedProject + ' has anonymization enabled</strong>, but the Clone operation will not apply anonymization to the cloned data.</div></div>';
                } else if (selectedOp === 'Reimport') {
                    html += '<div class="bt-anon-banner bt-anon-good">' +
                        '<span class="bt-anon-icon">&#10003;</span>' +
                        '<div>Image sessions will be re-anonymized using <strong>' + selectedProject + '</strong>\'s anonymization pipeline.</div></div>';
                }
            } else if (anonEnabled === false && selectedOp === 'Reimport') {
                html += '<div class="bt-anon-banner bt-anon-warn">' +
                    '<span class="bt-anon-icon">&#9888;</span>' +
                    '<div><strong>' + selectedProject + ' has no anonymization configured.</strong> Reimport will pass image sessions through without re-anonymization.</div></div>';
            }
            // Share + no anon, Clone + no anon: no banner — nothing actionable to flag.
        }

        // Storage caution — shown for Reimport (Clone's storage note is in its description).
        if (selectedOp === 'Reimport') {
            html += '<div class="bt-anon-banner bt-anon-caution">' +
                '<span class="bt-anon-icon">&#9888;</span>' +
                '<div>Reimported data can take up significant storage in the destination project, and the reimporting operation may take some time.</div></div>';
        }

        area.innerHTML = html;
    }

    // ── Operation Detail ──

    function updateOperationDetail() {
        $('#bt-op-detail-desc').text(operationDetails[selectedOp] || '');
    }

    // ── Operation Selection ──

    function bindOperationCards() {
        $(document).on('click', '.bt-op-card-sm', function() {
            $('.bt-op-card-sm').removeClass('selected');
            $(this).addClass('selected');
            selectedOp = $(this).data('op');
            toggleOperation(selectedOp);
        });
    }

    function toggleOperation(op) {
        var opLabels = { Share: 'share', Clone: 'clone', Reimport: 'reimport' };
        $('#bt-main-title').text('Select data to ' + (opLabels[op] || 'transfer'));

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
    }

    // Hide+disable rows not eligible for the chosen operation (excluded from counts/submission
    // and from view). Eligibility is a per-row data attribute, uniform across operations:
    //   Share    → data-shareable    (per project's sharing feature)
    //   Clone    → data-cloneable    (per project's copying feature)
    //   Reimport → data-reimportable (per item type — only image sessions; never feature-gated)
    // A non-reimportable subject is hidden, but its (reimportable) sessions still render:
    // showRows() treats a bt-import-hidden parent as logically visible.
    function applyOperationEligibility(op) {
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
    // index, short-circuiting on the first hit — no jQuery set building.
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
    // costs nothing — this is what removes the Reimport→Share/Clone lag at ~1,000 subjects.
    function reconcileSubjectsFromSessions() {
        var subs = document.querySelectorAll('#bt-data-body tr[data-type="subject"]');
        for (var i = 0; i < subs.length; i++) {
            var el = subs[i];
            var id = el.getAttribute('data-id');
            if (!id) continue;
            var want = subtreeHasSelected(id);
            if (want === el.classList.contains('selected')) continue;   // unchanged → no DOM write
            el.classList.toggle('selected', want);
            syncCheckbox($(el));
        }
    }

    function syncCheckbox($row) {
        var $cb = $row.find('.bt-cb');
        if (!$cb.length) return;
        var want = $row.hasClass('selected');
        if (want === $cb.hasClass('checked')) return;   // checkmark already correct — skip the DOM write
        if (want) $cb.addClass('checked').html('&#10003;');
        else $cb.removeClass('checked').html('');
    }

    // ── Project Selection ──

    function bindProjectSelect() {
        $(document).on('change', '#bt-project-select', function() {
            selectedProject = $(this).val() || null;
            if (selectedProject && projectAnonCache.hasOwnProperty(selectedProject)) {
                selectedAnon = projectAnonCache[selectedProject];
                updateAnonBanner();
            } else if (selectedProject) {
                XNAT.plugin.batchtransfer.checkProjectAnon(selectedProject, function(anonEnabled) {
                    selectedAnon = anonEnabled;
                    updateAnonBanner();
                });
            } else {
                selectedAnon = null;
                updateAnonBanner();
            }
            updateSummary();
        });
    }

    // ── Data Table Interactions ──

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

        // Filter — debounced so fast typing doesn't run the filter on every keystroke.
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

    // Show rows respecting expand/collapse state — WITHOUT reading layout. The previous version
    // called jQuery :visible on each parent, which forces a reflow; interleaved with the
    // show/hide writes that is layout thrashing (O(n) reflows), and it's what made clearing the
    // filter lag badly (clearing transitions ~all rows from display:none → shown). Here we
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

        // Raw DOM, matching against text cached at index time, one batched display pass — so
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

        // Pass 3: show matches (and ancestors), hide the rest — raw display writes (one reflow).
        // Selection state is left untouched.
        for (i = 0; i < rows.length; i++) {
            el = rows[i];
            el.style.display = matching[el.getAttribute('data-id')] ? '' : 'none';
        }

        updateSummary();
    }

    // ── Summary + selection counts ──
    // Single O(n) pass drives both the selection bar and the sidebar summary (previously two
    // or three separate full scans ran on every click).

    function updateSummary() {
        $('#bt-sum-op').text(selectedOp);
        $('#bt-sum-dest').text(selectedProject || '—');

        var subjects = 0, sessions = 0, assessors = 0, selectedCount = 0, eligibleCount = 0;
        var rows = document.querySelectorAll('#bt-data-body tr');
        for (var i = 0; i < rows.length; i++) {
            var el = rows[i];
            if (el.classList.contains('bt-import-disabled')) continue;
            eligibleCount++;
            if (!el.classList.contains('selected')) continue;
            selectedCount++;
            var type = el.getAttribute('data-type') || '';
            if (type === 'subject') {
                subjects++;
            } else if (type === 'assessor') {
                if (el.getAttribute('data-reimportable') === 'true') sessions++;
                else assessors++;
            }
        }

        $('#bt-sel-count').text(selectedCount);
        $('.bt-select-all-link').text(eligibleCount > 0 && selectedCount === eligibleCount ? 'Deselect all' : 'Select all');
        $('#bt-sum-subjects').text(subjects);
        $('#bt-sum-sessions').text(sessions);
        $('#bt-sum-assessors').text(assessors);
        updateSubmitButton();
    }

    // Used only by the submit confirmation (infrequent) for its "N subjects, M sessions" text.
    function getSelectionCounts() {
        var subjects = 0, sessions = 0, assessors = 0;
        var rows = document.querySelectorAll('#bt-data-body tr');
        for (var i = 0; i < rows.length; i++) {
            var el = rows[i];
            if (el.classList.contains('bt-import-disabled') || !el.classList.contains('selected')) continue;
            var type = el.getAttribute('data-type') || '';
            if (type === 'subject') subjects++;
            else if (type === 'assessor') {
                if (el.getAttribute('data-reimportable') === 'true') sessions++;
                else assessors++;
            }
        }
        return { subjects: subjects, sessions: sessions, assessors: assessors };
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

    function updateSubmitButton() {
        var label = selectedProject ? selectedOp + ' to ' + selectedProject : selectedOp;
        $('#bt-submit-btn').text(label);
    }

    // ── Submit ──

    XNAT.plugin.batchtransfer.submitTransfer = function() {
        if (!selectedProject) {
            xmodal.message('Batch Transfer', 'Please select a destination ' + XNAT.app.displayNames.singular.project.toLowerCase());
            return false;
        }

        var $selected = $('#bt-data-body tr.selected').not('.bt-import-disabled');
        if ($selected.length === 0) {
            xmodal.message('Batch Transfer', 'Please select at least one item');
            return false;
        }

        var items = [];
        $selected.each(function() {
            var id = $(this).data('id');
            if (id) {
                items.push({
                    id: id,
                    mode: selectedOp,
                    destination_project: selectedProject
                });
            }
        });

        var counts = getSelectionCounts();
        var gerunds = { Share: 'sharing', Clone: 'cloning', Reimport: 'reimporting' };
        var gerund = gerunds[selectedOp] || 'transferring';
        var oneTimeSuffix = (selectedOp === 'Share') ? '' : ' This is a one-time operation.';
        var confirmationMsg = 'Transfer ' + formatItemList(counts) + ' to ' + selectedProject +
            ' by ' + gerund + '?' + oneTimeSuffix;

        xModalConfirm({
            content: confirmationMsg,
            okAction: function() {
                // Guard against a second submission while one is already in flight.
                if (submitInFlight) return;
                submitInFlight = true;
                $('#bt-submit-btn').prop('disabled', true);

                var batchTransfer = {
                    requests: items,
                    tracking_id: 'batch_transfer_' + Date.now()
                };
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
                        $('#bt-submit-btn').prop('disabled', false);
                    }
                });
            }
        });
    };

}));

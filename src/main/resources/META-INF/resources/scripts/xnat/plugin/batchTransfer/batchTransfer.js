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
    var selectedProject = null;
    var selectedAnon = null;
    var config = {};
    var submitInFlight = false;
    var selectionByItem = {};
    var activePool = 'share';

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

        bindOperationCards();
        bindProjectSelect();
        bindTableInteractions();
        updateOperationDetail();
        // The table rows are pre-selected server-side (class="selected"), so prime the
        // selection bar on load too — otherwise it keeps the template's hardcoded
        // "0 items selected" / "Select all" until the user's first interaction.
        updateSelectionCount();
        updateSummary();
    };

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
    // No visual decoration is applied to the dropdown options themselves.
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
        // Remember the selection of the currently visible pool before switching, so the
        // user's choices carry over to the operation they're switching to.
        captureSelection(activePool);

        var opLabels = { Share: 'share', Clone: 'clone', Reimport: 'reimport' };
        $('#bt-main-title').text('Select data to ' + (opLabels[op] || 'transfer'));

        // Update filter placeholder
        $('#bt-data-search').attr('placeholder',
            op === 'Reimport' ? 'Filter sessions by name or datatype...' : 'Filter subjects and sessions by name or datatype...');

        // Show/hide data pools (Clone uses the 'copy' pool internally; data-pool values are unchanged in Phase 1)
        var showPool = (op === 'Clone') ? 'copy' : 'share';
        var hidePool = (op === 'Clone') ? 'share' : 'copy';
        $('#bt-data-body tr[data-pool="' + hidePool + '"]').hide();
        showPoolRows(showPool);

        // For Reimport, disable subject-level checkboxes and non-session assessors
        if (op === 'Reimport') {
            disableImportSubjects();
        } else {
            enableAllRows();
        }

        // Restore the remembered selection onto the now-visible pool (matched by data-id),
        // then record it as the active pool for the next switch.
        applySelection(showPool);
        activePool = showPool;

        // Reapply the current filter to the newly visible pool
        var currentFilter = $('#bt-data-search').val() || '';
        filterData(currentFilter);

        updateAnonBanner();
        updateOperationDetail();
        updateSelectionCount();
        updateSummary();
    }

    function isSessionData(xsiType) {
        return xsiType && /SessionData$/i.test(xsiType);
    }

    function disableImportSubjects() {
        $('#bt-data-body tr[data-pool="share"]').each(function() {
            var xsi = $(this).attr('data-xsi') || '';
            var type = $(this).attr('data-type') || '';

            // Hide subjects entirely in Reimport mode and exclude them from submission.
            // Their selection state stays in selectionByItem and is restored on the way out.
            if (type === 'subject') {
                $(this).addClass('bt-import-disabled bt-import-hidden');
                return;
            }

            // Non-SessionData assessors can't be reimported: disable and clear them.
            // SessionData assessors stay enabled; applySelection() sets their checked state
            // from the per-item store, so the user's session selections are retained.
            if (type === 'assessor') {
                if (isSessionData(xsi)) {
                    $(this).removeClass('bt-import-disabled');
                } else {
                    $(this).addClass('bt-import-disabled');
                    $(this).find('.bt-cb').removeClass('checked').html('');
                    $(this).removeClass('selected');
                }
            }
        });
    }

    function enableAllRows() {
        // Re-enable rows that Reimport mode disabled/hid. Selection is restored separately
        // by applySelection() from the per-item store, so we don't force-select here.
        $('#bt-data-body tr.bt-import-disabled').each(function() {
            $(this).removeClass('bt-import-disabled bt-import-hidden');
        });
    }

    // ── Selection persistence across operation switches ──
    // The 'share' pool (Share/Reimport) and 'copy' pool (Clone) are separate row sets that
    // represent the same underlying items, matched by data-id. To keep the user's selection
    // stable when they switch operations, capture the visible pool's selection into a
    // per-item store before switching and re-apply it to whichever pool becomes visible.
    // bt-import-disabled rows (Reimport's hidden subjects / non-session assessors) are
    // skipped: their submittable state is fixed by the operation, while their underlying
    // selection stays in the store and is restored when leaving Reimport.

    function captureSelection(pool) {
        $('#bt-data-body tr[data-pool="' + pool + '"]').each(function() {
            var id = $(this).data('id');
            if (id == null) return;
            if ($(this).hasClass('bt-import-disabled')) {
                // Reimport hides/disables subjects. Derive a subject's stored selection from
                // its sessions so that leaving Reimport reflects the session choices — a
                // subject with no selected sessions becomes deselected rather than staying
                // stuck "selected" at its pre-Reimport value.
                if ($(this).data('type') === 'subject') {
                    selectionByItem[id] = getAllDescendants(id, pool).not('.bt-import-disabled').filter('.selected').length > 0;
                }
                // Non-session assessors keep their previously stored selection.
                return;
            }
            selectionByItem[id] = $(this).hasClass('selected');
        });
    }

    function applySelection(pool) {
        $('#bt-data-body tr[data-pool="' + pool + '"]').each(function() {
            if ($(this).hasClass('bt-import-disabled')) return;
            var id = $(this).data('id');
            if (id == null) return;
            // Items the user hasn't touched default to selected.
            var selected = selectionByItem.hasOwnProperty(id) ? selectionByItem[id] : true;
            if (selected) {
                $(this).addClass('selected');
                $(this).find('.bt-cb').addClass('checked').html('&#10003;');
            } else {
                $(this).removeClass('selected');
                $(this).find('.bt-cb').removeClass('checked').html('');
            }
        });
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

        // Filter
        $(document).on('input', '#bt-data-search', function() {
            filterData($(this).val());
        });
    }

    function toggleRow(cb) {
        var $cb = $(cb);
        var $row = $cb.closest('tr');
        var isChecked = $cb.hasClass('checked');
        var rowId = $row.data('id');
        var pool = getVisiblePool();

        if (isChecked) {
            $cb.removeClass('checked').html('');
            $row.removeClass('selected');
            // Deselect all children
            if (rowId) {
                deselectChildren(rowId, pool);
            }
        } else {
            $cb.addClass('checked').html('&#10003;');
            $row.addClass('selected');
            // Select all eligible children
            if (rowId) {
                selectChildren(rowId, pool);
            }
            // Select parent chain
            selectParentChain($row);
        }
        updateSelectionCount();
        updateSummary();
    }

    // Toggle children of a subject in Reimport mode (subject itself stays disabled)
    function toggleSubjectChildren($row) {
        var rowId = $row.data('id');
        var pool = getVisiblePool();
        if (!rowId) return;

        // Check if children are currently selected
        var $eligibleChildren = getAllDescendants(rowId, pool).not('.bt-import-disabled');
        var allSelected = $eligibleChildren.length > 0 &&
            $eligibleChildren.filter('.selected').length === $eligibleChildren.length;

        if (allSelected) {
            deselectChildren(rowId, pool);
        } else {
            selectChildren(rowId, pool);
        }
        updateSelectionCount();
        updateSummary();
    }

    function getAllDescendants(parentId, pool) {
        var $result = $();
        var $children = $('#bt-data-body tr[data-parent="' + parentId + '"][data-pool="' + pool + '"]');
        $children.each(function() {
            $result = $result.add($(this));
            var childId = $(this).data('id');
            if (childId) {
                $result = $result.add(getAllDescendants(childId, pool));
            }
        });
        return $result;
    }

    function deselectChildren(parentId, pool) {
        $('#bt-data-body tr[data-parent="' + parentId + '"][data-pool="' + pool + '"]').each(function() {
            if ($(this).hasClass('bt-import-disabled')) return;
            $(this).removeClass('selected');
            $(this).find('.bt-cb').removeClass('checked').html('');
            var childId = $(this).data('id');
            if (childId) {
                deselectChildren(childId, pool);
            }
        });
    }

    function selectChildren(parentId, pool) {
        $('#bt-data-body tr[data-parent="' + parentId + '"][data-pool="' + pool + '"]').each(function() {
            if ($(this).hasClass('bt-import-disabled')) return;
            $(this).addClass('selected');
            $(this).find('.bt-cb').addClass('checked').html('&#10003;');
            var childId = $(this).data('id');
            if (childId) {
                selectChildren(childId, pool);
            }
        });
    }

    function selectParentChain($row) {
        var parentId = $row.data('parent');
        var pool = $row.data('pool');
        if (!parentId) return;
        var $parent = $('#bt-data-body tr[data-id="' + parentId + '"][data-pool="' + pool + '"]');
        if ($parent.length && !$parent.hasClass('bt-import-disabled')) {
            $parent.addClass('selected');
            $parent.find('.bt-cb').first().addClass('checked').html('&#10003;');
            selectParentChain($parent);
        }
    }

    // Show rows for a pool respecting expand/collapse state
    function showPoolRows(pool) {
        // Show top-level rows (no parent), then recursively show children of expanded parents
        $('#bt-data-body tr[data-pool="' + pool + '"]').each(function() {
            var parentId = $(this).data('parent');
            if (!parentId) {
                // Top-level row: always show (CSS class .bt-import-hidden keeps subjects hidden in Reimport mode)
                $(this).show();
            } else {
                // Child row: only show if parent is visible and expanded.
                // Treat parents hidden by Reimport mode as logically visible so their session children still render.
                var $parent = $('#bt-data-body tr[data-id="' + parentId + '"][data-pool="' + pool + '"]');
                var parentIsVisible = $parent.length && ($parent.is(':visible') || $parent.hasClass('bt-import-hidden'));
                if (parentIsVisible) {
                    var expandBtn = $parent.find('.bt-expand');
                    if (expandBtn.length && expandBtn.hasClass('expanded')) {
                        $(this).show();
                    } else {
                        $(this).hide();
                    }
                } else {
                    $(this).hide();
                }
            }
        });
    }

    function toggleChildren(btn) {
        var $btn = $(btn);
        var $row = $btn.closest('tr');
        var parentId = $row.data('id');
        var pool = $row.data('pool');
        var isExpanded = $btn.hasClass('expanded');
        $btn.toggleClass('expanded');

        var $children = $('#bt-data-body tr[data-parent="' + parentId + '"][data-pool="' + pool + '"]');
        if (isExpanded) {
            // Collapse: hide children and all descendants
            hideDescendants(parentId, pool);
        } else {
            // Expand: show direct children, respect their own expand state
            $children.each(function() {
                $(this).show();
                var childId = $(this).data('id');
                var childBtn = $(this).find('.bt-expand');
                if (childBtn.length && childBtn.hasClass('expanded') && childId) {
                    showDescendants(childId, pool);
                }
            });
        }
    }

    function hideDescendants(parentId, pool) {
        $('#bt-data-body tr[data-parent="' + parentId + '"][data-pool="' + pool + '"]').each(function() {
            $(this).hide();
            var childId = $(this).data('id');
            if (childId) hideDescendants(childId, pool);
        });
    }

    function showDescendants(parentId, pool) {
        $('#bt-data-body tr[data-parent="' + parentId + '"][data-pool="' + pool + '"]').each(function() {
            $(this).show();
            var childId = $(this).data('id');
            var childBtn = $(this).find('.bt-expand');
            if (childBtn.length && childBtn.hasClass('expanded') && childId) {
                showDescendants(childId, pool);
            }
        });
    }

    function toggleSelectAll() {
        var pool = getVisiblePool();
        var $rows = getVisibleRows(pool);
        var allChecked = $rows.length > 0 && $rows.filter('.selected').length === $rows.not('.bt-import-disabled').length;

        $rows.each(function() {
            var $cb = $(this).find('.bt-cb');
            if ($(this).hasClass('bt-import-disabled')) return;
            if (allChecked) {
                $cb.removeClass('checked').html('');
                $(this).removeClass('selected');
            } else {
                $cb.addClass('checked').html('&#10003;');
                $(this).addClass('selected');
            }
        });

        var $headerCb = $('#bt-header-cb');
        if (allChecked) {
            $headerCb.removeClass('checked').html('');
        } else {
            $headerCb.addClass('checked').html('&#10003;');
        }
        updateSelectionCount();
        updateSummary();
    }

    function filterData(query) {
        var q = query.toLowerCase();
        var pool = getVisiblePool();
        var $rows = $('#bt-data-body tr[data-pool="' + pool + '"]');

        // The filter is a VIEW filter only: it shows/hides rows but never changes which
        // rows are selected. This preserves the user's manual selections across filtering
        // and clearing. (Previously, clearing the filter re-selected every row and
        // filtering deselected every non-match, silently discarding the user's choices.)
        if (q.length === 0) {
            // No filter: restore full tree visibility (respecting expand/collapse state).
            showPoolRows(pool);
            updateSelectionCount();
            updateSummary();
            return;
        }

        // First pass: determine which rows match the query text
        var matchingIds = {};
        $rows.each(function() {
            var text = $(this).text().toLowerCase();
            if (text.includes(q)) {
                matchingIds[$(this).data('pool') + ':' + $(this).data('id')] = true;
            }
        });

        // Second pass: include ancestors of matching rows so the tree context is visible
        $rows.each(function() {
            var key = $(this).data('pool') + ':' + $(this).data('id');
            if (matchingIds[key]) {
                var parentId = $(this).data('parent');
                while (parentId) {
                    var parentKey = pool + ':' + parentId;
                    matchingIds[parentKey] = true;
                    var $parent = $('#bt-data-body tr[data-id="' + parentId + '"][data-pool="' + pool + '"]');
                    parentId = $parent.data('parent');
                }
            }
        });

        // Third pass: show matching rows (and their ancestors), hide the rest.
        // Selection state is intentionally left untouched.
        $rows.each(function() {
            var key = $(this).data('pool') + ':' + $(this).data('id');
            if (matchingIds[key]) {
                $(this).show();
            } else {
                $(this).hide();
            }
        });

        updateSelectionCount();
        updateSummary();
    }

    function getVisiblePool() {
        return selectedOp === 'Clone' ? 'copy' : 'share';
    }

    function getVisibleRows(pool) {
        return $('#bt-data-body tr[data-pool="' + pool + '"]').filter(':visible');
    }

    function updateSelectionCount() {
        var pool = getVisiblePool();
        var count = $('#bt-data-body tr[data-pool="' + pool + '"].selected').not('.bt-import-disabled').length;
        $('#bt-sel-count').text(count);

        var $link = $('.bt-select-all-link');
        var total = $('#bt-data-body tr[data-pool="' + pool + '"]').not('.bt-import-disabled').length;
        $link.text(count === total ? 'Deselect all' : 'Select all');
    }

    // ── Summary ──

    function getSelectionCounts() {
        var pool = getVisiblePool();
        var $rows = $('#bt-data-body tr[data-pool="' + pool + '"].selected').not('.bt-import-disabled');
        var subjects = 0, sessions = 0, assessors = 0;
        $rows.each(function() {
            var type = $(this).attr('data-type') || '';
            var xsi = $(this).attr('data-xsi') || '';
            if (type === 'subject') {
                subjects++;
            } else if (type === 'assessor') {
                if (isSessionData(xsi)) sessions++;
                else assessors++;
            }
        });
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

    function updateSummary() {
        $('#bt-sum-op').text(selectedOp);
        $('#bt-sum-dest').text(selectedProject || '\u2014');
        var counts = getSelectionCounts();
        $('#bt-sum-subjects').text(counts.subjects);
        $('#bt-sum-sessions').text(counts.sessions);
        $('#bt-sum-assessors').text(counts.assessors);
        updateSubmitButton();
    }

    // ── Submit ──

    XNAT.plugin.batchtransfer.submitTransfer = function() {
        if (!selectedProject) {
            xmodal.message('Batch Transfer', 'Please select a destination ' + XNAT.app.displayNames.singular.project.toLowerCase());
            return false;
        }

        var pool = getVisiblePool();
        var $selected = $('#bt-data-body tr[data-pool="' + pool + '"].selected').not('.bt-import-disabled');
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
                // Guard against a second submission while one is already in flight
                // (the submit button isn't otherwise blocked once the dialog confirms).
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

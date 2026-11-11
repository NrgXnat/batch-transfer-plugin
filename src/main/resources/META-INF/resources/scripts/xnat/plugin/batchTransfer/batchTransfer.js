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
    var siteAnon;                  // site-wide script: true / false / null (check failed) / undefined (not checked)
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
        bindScriptFile();
        fetchCapabilities();
        checkSiteAnon();
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
        // The cache holds true or false once checked, and null when the check failed. No entry means not
        // checked yet, which is also the state while this request is in flight.
        delete projectAnonCache[projectId];
        $.ajax({
            type: 'GET',
            url: XNAT.url.rootUrl('/xapi/anonymize/projects/' + projectId + '/enabled'),
            contentType: 'application/json',
            success: function(data) {
                projectAnonCache[projectId] = data;
                callback(data, null);
            },
            error: function(xhr) {
                projectAnonCache[projectId] = null;
                callback(null, 'Could not retrieve anonymization setting: ' + xhr.status);
            }
        });
    };

    // The site-wide anonymization script runs on every Reimport when it is enabled. Any logged-in user may read
    // whether it is.
    function checkSiteAnon() {
        $.ajax({
            type: 'GET',
            url: XNAT.url.rootUrl('/xapi/anonymize/site/enabled'),
            contentType: 'application/json',
            success: function(data) { siteAnon = data === true || data === 'true'; siteAnonChecked(); },
            error: function() { siteAnon = null; siteAnonChecked(); }
        });
    }

    function siteAnonChecked() {
        var help = siteAnon === true
            ? 'The site-wide anonymization script is enabled on this site, so it runs on every reimported session.'
            : siteAnon === false
                ? 'The site-wide anonymization script is not enabled on this site.'
                : 'Could not check whether the site-wide anonymization script is enabled on this site.';
        $('#bt-help-site').text(help).prop('hidden', false);
        if (XNAT.plugin.batchtransfer.help) { XNAT.plugin.batchtransfer.help.layout(); }
        updateSummary();
    }

    // The anonymization scripts a Reimport applies to each session, in the order they run: the site-wide script as
    // each file is received, the custom script right after it, and the destination project's script when the
    // session is archived. Each is 'on', 'off', or 'unknown' while its check hasn't answered or after it failed.
    function anonScripts() {
        function state(v) { return v === true ? 'on' : v === false ? 'off' : 'unknown'; }
        return [
            { key: 'site-wide', name: 'the site-wide script', state: state(siteAnon) },
            { key: 'custom', name: 'your custom script', state: hasCustomScript() ? 'on' : 'off' },
            { key: 'destination', name: selectedProject + '\'s script',
              state: state(projectAnonCache[selectedProject]) }
        ];
    }

    function scriptsIn(scripts, state) {
        return scripts.filter(function(s) { return s.state === state; });
    }

    function scriptNames(scripts, separator) {
        return scripts.map(function(s) { return s.name; }).join(separator);
    }

    // -- Operation Banners --
    // Share or Clone into a destination with anonymization enabled: the Anonymization row warns that the
    // destination's script won't run, since neither operation has an anonymization chip. Reimport's
    // anonymization status and storage cost are action-bar chips (anonChipsHtml), each with its full
    // explanation as a tooltip, so they get no banner.

    function updateAnonBanner() {
        var area = document.getElementById('bt-op-detail-warning-area');
        if (!area) return;

        var html = '';
        var dest = escapeHtml(selectedProject);
        if (selectedProject && projectAnonCache[selectedProject] === true) {
            if (selectedOp === 'Share') {
                html += banner('warn', '<strong>' + dest + ' has anonymization enabled</strong>, but its anonymization script will not be applied by Share - the data is made visible as-is.');
            } else if (selectedOp === 'Clone') {
                html += banner('warn', '<strong>' + dest + ' has anonymization enabled</strong>, but its anonymization script will not be applied by Clone - the cloned copy is not anonymized.');
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
        renderSummary();
        var reimport = selectedOp === 'Reimport';
        // The Anonymization row: Reimport's anonymization and label controls, or for Share and Clone the note that
        // only Reimport anonymizes.
        $('#bt-anon-band').toggle(reimport);
        $('#bt-anon-na').toggle(!reimport);
        $('#bt-anon-na-op').text(selectedOp);
        // The script editor is a panel of its own, so hide it explicitly when leaving Reimport or when the build
        // lacks per-import anon.
        $('#bt-anon-editor').toggle(reimport && anonMode === 'script' && perImportAnonSupported());
        // Eligibility warnings are per operation - each explains why rows are hidden for that
        // operation only, so a stale one must not linger after the operation changes.
        $('#bt-share-warning').toggle(selectedOp === 'Share');
        $('#bt-copy-warning').toggle(selectedOp === 'Clone');
        syncHelp();
    }

    // The summary above the setup card: what the chosen operation does, that the source isn't changed, and that
    // the transfer runs in the background. From search results the source is the projects in the results.
    function renderSummary() {
        var project = config.projectContext ? escapeHtml(config.projectContext) : null;
        var src = project ? '<b>' + project + '</b>' : 'the projects in your search results';
        var to = selectedProject ? '<b>' + escapeHtml(selectedProject) + '</b>' : 'another project';
        var what = {
            Share: 'Share data from ' + src + ' into ' + to + '. Nothing is copied: ' + (project ? project + ' keeps' : 'they keep') +
                   ' the data, and changes made there show in the destination.',
            Clone: 'Clone data from ' + src + ' into ' + to + ' as an independent copy that can be edited separately. ' +
                   'Files are hard-linked, so no extra disk space is used at clone time.',
            Reimport: 'Reimport image sessions from ' + src + ' into ' + to + '. Only DICOM files are transferred, ' +
                      'anonymized on the way in; session metadata is derived from the DICOM tags.'
        }[selectedOp] || '';
        $('#bt-summary').html(what + ' ' + (project ? project + ' itself is' : 'The source projects are') +
            ' not changed, and once submitted the transfer runs in the background.');
    }

    // The help column shows the topics for the chosen operation, and the script topic while the editor shows.
    function syncHelp() {
        var help = XNAT.plugin.batchtransfer.help;
        if (help) { help.sync(selectedOp, selectedOp === 'Reimport' && anonMode === 'script' && perImportAnonSupported()); }
    }

    // Called by the tabs when New transfer is shown again: anything measured while it was hidden is redone.
    XNAT.plugin.batchtransfer.viewShown = function() {
        if (XNAT.plugin.batchtransfer.help) { XNAT.plugin.batchtransfer.help.layout(); }
    };

    // -- Cohort mode (interactive tree | CSV manifest) --
    // Swaps the workspace between the interactive selection tree and the manifest dropzone; the
    // loaded-manifest state itself is rendered by renderCsvResults.
    // A manifest is for Reimport only, and resolving one needs a single source project, which search results
    // spanning several projects don't have. The CSV mode is disabled up front in either case, rather than
    // letting the user pick a file and only then be told. Leaving Reimport while a manifest is shown switches to
    // the tree and keeps the manifest loaded; coming back to Reimport shows it again without resolving it again.
    var csvParked = false;          // the CSV mode was showing when the operation left Reimport

    function configureCohortModes() {
        var $csv = $('.bt-cohort-mode[data-cohort="csv"]');
        if (!csvSourceProject()) {
            $csv.prop('disabled', true).attr('title', 'Manifest CSV upload is not supported for multiple source projects.');
            return;
        }
        var reimport = selectedOp === 'Reimport';
        $csv.prop('disabled', !reimport);
        if (reimport) {
            $csv.removeAttr('title');
        } else {
            $csv.attr('title', 'Manifests are for Reimport. Share and Clone use the selection tree.');
        }
        if (!reimport && cohortMode === 'csv') {
            csvParked = true;
            setCohortMode('tree');
        } else if (reimport && csvParked) {
            csvParked = false;
            setCohortMode('csv');
        }
    }

    function setCohortMode(mode) {
        cohortMode = mode;
        $('.bt-cohort-mode').removeClass('selected');
        $('.bt-cohort-mode[data-cohort="' + mode + '"]').addClass('selected');
        var tree = mode === 'tree';
        $('#bt-cohort-tree').toggle(tree);
        $('#bt-cohort-csv').toggle(!tree);
        if (!tree) { refreshCsvRequiredHint(); }
        // The columns placeholders can bind to depend on the mode, so the verdict has to be redone.
        revalidateScript();
        updateSummary();
    }

    function bindCohortToggle() {
        $(document).on('click', '.bt-cohort-mode', function() {
            if (this.disabled) { return; }
            csvParked = false;
            setCohortMode($(this).data('cohort'));
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
            success: function(data) { caps = data; configureAnonSupport(); renderScriptLimit(); updateAnonBanner(); updateSummary(); },
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
            syncHelp();
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
            syncHelp();
            if (anonMode === 'script' && XNAT.plugin.batchtransfer.help) { XNAT.plugin.batchtransfer.help.highlight('script'); }
            updateAnonBanner();
            updateSummary();
        });
        $(document).on('click', '#bt-anon-validate', function() { validateAnonScript(true); });
        var t = null;
        $(document).on('input', '#bt-anon-script', function() {
            showScriptFileError(null);
            if (scriptFile && !scriptFile.edited) {
                scriptFile.edited = true;
                renderScriptSource();
            }
            clearTimeout(t);
            t = setTimeout(function() {
                validateAnonScript(false);
                if (cohortMode === 'csv' && manifestResult) { renderCsvResults(); }   // the per-row compiled scripts
                updateAnonBanner();
                updateSummary();
            }, 400);
        });
        // Preserve-source-labels toggles drive the "preserved labels not anonymized" action-bar chip and
        // the final destination labels in the manifest table, so re-render both whenever they change.
        $(document).on('change', '#bt-preserve-subject-label, #bt-preserve-session-label', function() {
            if (cohortMode === 'csv' && manifestResult) { renderCsvResults(); }
            updateSummary();
        });
    }

    // -- Loading a script from a file --
    // Upload file, or a file dropped anywhere on the script panel, replaces the editor's text, and Undo puts the
    // previous text back, one load at a time. Any name or extension is accepted. The size is checked against the
    // admin's limit (max_anon_script_bytes) before the file is read, so an oversized file is never loaded; until the
    // capabilities say what that limit is, a fixed ceiling keeps a huge file from being read, and the server checks
    // again at validate and submit. A refused file leaves the editor unchanged. Typed or pasted text isn't checked
    // against the limit here.

    var SCRIPT_FILE_CEILING = 16 * 1024 * 1024;   // while the admin's limit is unknown
    var scriptFile = null;          // { name, size, edited } of the file whose text is in the editor
    var scriptUndo = [];            // { text, file } as they were before each load, newest last
    var scriptLoadSeq = 0;          // counts load attempts, so a slow read can't land after a newer attempt

    function maxScriptBytes() {
        return caps && caps.max_anon_script_bytes ? caps.max_anon_script_bytes : null;
    }

    function bindScriptFile() {
        $(document).on('click', '#bt-script-pick', function() { $('#bt-script-file').val('').click(); });
        $(document).on('change', '#bt-script-file', function() {
            if (this.files && this.files.length) { loadScriptFile(this.files[0]); }
        });
        $(document).on('click', '#bt-script-undo', function() { undoScriptFile(); });
        // A file dropped on the script panel loads; dropped anywhere else (but the manifest dropzone, which takes its
        // own) it is ignored, rather than opened by the browser in place of the screen.
        document.addEventListener('dragover', function(e) {
            if (!hasFiles(e) || inManifestDropzone(e)) { return; }
            e.preventDefault();
            var zone = scriptDropZone(e);
            e.dataTransfer.dropEffect = zone ? 'copy' : 'none';
            $('#bt-anon-editor').toggleClass('bt-drag', !!zone);
        });
        document.addEventListener('dragleave', function(e) {
            var zone = scriptDropZone(e);
            if (zone && !zone.contains(e.relatedTarget)) { zone.classList.remove('bt-drag'); }
        });
        document.addEventListener('drop', function(e) {
            if (!hasFiles(e) || inManifestDropzone(e)) { return; }
            e.preventDefault();
            $('#bt-anon-editor').removeClass('bt-drag');
            if (!scriptDropZone(e)) { return; }
            var files = e.dataTransfer.files;
            if (files.length > 1) {
                scriptLoadSeq++;
                showScriptFileError('Drop one file at a time. The editor was not changed.');
            } else if (files.length) {
                loadScriptFile(files[0]);
            }
        });
    }

    function hasFiles(e) {
        return !!e.dataTransfer && Array.prototype.indexOf.call(e.dataTransfer.types || [], 'Files') >= 0;
    }

    function scriptDropZone(e) {
        var zone = e.target && e.target.closest ? e.target.closest('#bt-anon-editor') : null;
        return zone && zone.offsetParent ? zone : null;
    }

    function inManifestDropzone(e) {
        return !!(e.target && e.target.closest && e.target.closest('#bt-dropzone'));
    }

    function loadScriptFile(file) {
        var seq = ++scriptLoadSeq;
        var name = '<b>' + escapeHtml(file.name) + '</b>';
        var unchanged = ' The editor was not changed.';
        var limit = maxScriptBytes();
        if (limit && file.size > limit) {
            // Sizes that round to the same figure are given in bytes, so the message doesn't contradict itself.
            var size = formatBytes(file.size), max = formatBytes(limit);
            if (size === max) { size = file.size + ' bytes'; max = limit + '-byte'; }
            showScriptFileError(name + ' is ' + size + ', over the ' + max +
                ' limit for anonymization scripts set by your site administrator.' + unchanged);
            return;
        }
        if (!limit && file.size > SCRIPT_FILE_CEILING) {
            showScriptFileError(name + ' is ' + formatBytes(file.size) + ', too large for an anonymization script.' +
                unchanged);
            return;
        }
        if (file.size === 0) {
            showScriptFileError(name + ' is empty.' + unchanged);
            return;
        }
        var reader = new FileReader();
        reader.onload = function() {
            if (seq !== scriptLoadSeq) { return; }
            var text = String(reader.result || '');
            // A NUL byte means binary content, such as a DICOM file.
            if (text.indexOf('\u0000') >= 0) {
                showScriptFileError(name + ' is not a text file; a DicomEdit script is plain text.' + unchanged);
                return;
            }
            var $editor = $('#bt-anon-script');
            scriptUndo.push({ text: $editor.val() || '', file: scriptFile });
            scriptFile = { name: file.name, size: file.size, edited: false };
            $editor.val(text);
            scriptTextChanged();
        };
        reader.onerror = function() {
            if (seq === scriptLoadSeq) { showScriptFileError(name + ' could not be read.' + unchanged); }
        };
        reader.readAsText(file);
    }

    // Puts back the text, and the loaded file it came from, as they were before the latest load.
    function undoScriptFile() {
        if (!scriptUndo.length) { return; }
        scriptLoadSeq++;
        var before = scriptUndo.pop();
        $('#bt-anon-script').val(before.text);
        scriptFile = before.file;
        scriptTextChanged();
    }

    // The editor's text was replaced: validate it like a paste, and redraw what depends on it.
    function scriptTextChanged() {
        showScriptFileError(null);
        renderScriptSource();
        validateAnonScript(true);
        if (cohortMode === 'csv' && manifestResult) { renderCsvResults(); }
        updateAnonBanner();
        updateSummary();
    }

    // Beside Upload file: the loaded file with Undo, or what can be dropped.
    function renderScriptSource() {
        var limit = maxScriptBytes();
        $('#bt-script-source').html(scriptFile
            ? '<span class="bt-file-chip">Loaded from <b>' + escapeHtml(scriptFile.name) + '</b> (' + formatBytes(scriptFile.size) + ')' +
              (scriptFile.edited ? ', edited' : '') + ' <button type="button" class="bt-linkbtn" id="bt-script-undo">Undo</button></span>'
            : 'or drop a file on this panel. Any file type' + (limit ? ', up to ' + formatBytes(limit) : '') + '.');
    }

    // The admin's limit, once the capabilities say what it is: beside Upload file and in the help topic.
    function renderScriptLimit() {
        var limit = maxScriptBytes();
        $('.bt-script-limit').text(limit ? formatBytes(limit) : '');
        $('.bt-script-limit-wrap').prop('hidden', !limit);
        renderScriptSource();
    }

    function showScriptFileError(html) {
        $('#bt-script-err').html(html ? '<span>&#9888;</span><div>' + html + '</div>' : '').prop('hidden', !html);
    }

    // One decimal below 10, whole numbers above; a value that rounds to 1024 moves up a unit.
    function formatBytes(n) {
        if (n < 1024) { return n + ' bytes'; }
        var units = ['KB', 'MB', 'GB'], v = n / 1024, i = 0;
        while (i < units.length - 1 && roundSize(v) >= 1024) { v /= 1024; i++; }
        return roundSize(v) + ' ' + units[i];
    }

    function roundSize(v) {
        return v < 10 ? parseFloat(v.toFixed(1)) : Math.round(v);
    }

    // The ${csv.*}-bindable columns for /validate/script: value + routing columns from the manifest
    // (CSV mode). The selection tree supplies no values, so in interactive mode no placeholder can bind ([]).
    // null while a manifest has yet to load: binding is not checked.
    function anonManifestColumns() {
        if (cohortMode !== 'csv') { return []; }
        if (manifestResult) {
            return (manifestResult.value_columns || []).concat(manifestResult.routing_columns || []);
        }
        return null;
    }

    // Re-checks the script when what its placeholders can bind to has changed. Only Reimport runs a script.
    function revalidateScript() {
        if (anonMode === 'script' && selectedOp === 'Reimport') { validateAnonScript(false); }
    }

    function validateAnonScript(showLoading) {
        var script = $('#bt-anon-script').val() || '';
        if (script.trim().length === 0) {
            anonValidateSeq++;   // a request still in flight no longer applies
            $('#bt-anon-verdict').empty();
            anonVerdictValid = null;
            return;
        }
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
                (v.valid ? ('Script is valid' + (v.binding && (v.placeholders || []).length ? ' and every placeholder is bound.' : '.'))
                         : 'Script has issues - submit is blocked.') + '</div></div>';
        (v.errors || []).forEach(function(e) { h += verdictBad('Parse: ' + escapeHtml(e)); });
        (v.restriction_violations || []).forEach(function(r) { h += verdictBad('Restriction: ' + escapeHtml(r)); });
        if (v.binding && !v.binding.bound) {
            var unbound = v.binding.unbound || [];
            var names = unbound.map(function(u) { return '${csv.' + escapeHtml(u) + '}'; }).join(', ');
            h += verdictBad(cohortMode === 'csv' ? 'Unbound: ' + names
                : names + (unbound.length === 1 ? ' needs' : ' need') + ' a manifest: placeholders are filled from' +
                  ' manifest CSV columns. Upload a manifest CSV, or remove the placeholder' +
                  (unbound.length === 1 ? '.' : 's.'));
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

        // The CSV mode is offered for Reimport only.
        configureCohortModes();
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
            renderSummary();
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
    // One line of state for the active cohort mode; the toggle beside it shows which mode that is.

    function renderBuilderState() {
        var el = document.getElementById('bt-builder-mode');
        if (!el) return;
        el.innerHTML = '<span>' + (cohortMode === 'csv' ? csvBuilderState() : treeBuilderState()) + '</span>';
    }

    // Eligibility is per operation, so the noun follows it: Reimport counts only image sessions
    // (subjects are ineligible), everything else counts items.
    function treeBuilderState() {
        var n = lastTreeCount.eligible;
        var noun = selectedOp === 'Reimport' ? 'session' : 'item';
        return n + ' eligible ' + noun + (n === 1 ? '' : 's');
    }

    function csvBuilderState() {
        if (manifestPending) { return 'resolving the manifest'; }
        if (manifestError) { return 'manifest could not be resolved'; }
        if (!manifestResult) { return 'no file uploaded'; }
        if (!manifestResult.required_present) { return 'missing required columns'; }
        return countText(manifestMatchedCount(), 'session') + ' from ' +
               countText(manifestResult.total_rows || 0, 'row');
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
            return { count: manifestCurrent() ? manifestMatchedCount() : 0, noun: 'sessions' };
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

    // Anonymization-status chips for the action bar. Reimport lists the anonymization scripts that will run;
    // Clone advertises hard-linking; Share shows none. These are the only place Reimport's anonymization
    // status and storage cost appear, so each chip's title carries the full explanation.
    function anonChipsHtml() {
        if (selectedOp === 'Reimport') {
            var scripts = anonScripts();
            var on = scriptsIn(scripts, 'on'), unknown = scriptsIn(scripts, 'unknown');
            var out = '';
            if (!selectedProject || siteAnon === undefined || !projectAnonCache.hasOwnProperty(selectedProject)) {
                // No destination yet, or a check is still running: nothing to report.
            } else if (!on.length && !unknown.length) {
                out = chip('warn', '&#9888; not anonymized', 'Neither the site-wide script nor ' + selectedProject +
                    '\'s script is enabled, and no custom script is set - reimported sessions will not be anonymized.');
            } else if (!on.length) {
                // Unknown, so neither good nor bad: anonymization may or may not run.
                out = chip('muted', '? anonymization not checked',
                    'Could not check whether ' + scriptNames(unknown, ' or ') + ' is enabled.');
            } else {
                // The scripts that run, in order; one whose check failed is marked "?". A custom script that isn't
                // the only one is double anonymization.
                var double = hasCustomScript() && on.length > 1;
                var listed = scripts.filter(function(s) { return s.state !== 'off'; });
                out = chip(double ? 'warn' : 'good', (double ? '&#9888; ' : '&#10003; ') +
                    listed.map(function(s) { return s.key + (s.state === 'unknown' ? '?' : ''); }).join(' + ') +
                    ' script' + (listed.length > 1 ? 's' : ''),
                    'Each session is anonymized by ' + scriptNames(on, ', then ') + '.' +
                    (double ? ' Your custom script is not the only one, and a later script can change what an' +
                              ' earlier one set.' : '') +
                    (unknown.length ? ' Could not check whether ' + scriptNames(unknown, ' or ') + ' is enabled.' : ''));
            }
            // Anonymization is in effect, but preserving the source XNAT labels re-introduces the original
            // subject/session labels - which may carry identifiers and are not touched by anonymization. Warn
            // when both are set.
            var preserved = preservedLabels();
            if (on.length && preserved.length) {
                // Kept short: this is the longest chip, and the action bar is a single row. The
                // reason behind it is in its title and in the Anonymization help topic.
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
            if (manifestPending) { return ['resolving the manifest']; }
            if (manifestError) { return ['the manifest could not be resolved']; }
            if (!manifestResult) { return ['upload a manifest']; }
            if (!manifestCurrent()) { return ['resolving the manifest']; }
            if (!manifestResult.required_present) { return ['manifest missing required columns']; }
            if (manifestMatchedCount() === 0) { return ['no matched rows']; }
            var reasons = [];
            var duplicates = statusRows('duplicate_session');
            if (duplicates.length) { reasons.push('duplicate_session in ' + rowList(duplicates)); }
            // A matched row with a value error is not transferable as written.
            var errors = valueErrorRows();
            if (errors.length) { reasons.push('value errors in ' + rowList(errors)); }
            var clashes = preservedLabelClashRows();
            if (clashes.length) { reasons.push('two sessions would get the same session label in ' + rowList(clashes)); }
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
    // The uploaded file's text is kept and posted, as a named file, to /validate/manifest. Submit sends the
    // resolved entries to /xapi/transfer as items. A manifest is resolved for Reimport, so a subject row covers
    // all of the subject's image sessions. The resolved cohort is shown grouped by subject: a header line per
    // subject, then its sessions. file_count is computed lazily, per session, when a session is expanded.

    var manifestText = null;        // the uploaded file's text, or null when no manifest is loaded
    var manifestFileName = '';      // uploaded file name (status, confirmation, and the posted file's name)
    var manifestResult = null;      // last /validate/manifest response
    var manifestPending = false;    // a /validate/manifest request is in flight
    var manifestError = null;       // why the last request failed, while the file is kept for a retry
    var manifestSeq = 0;            // request counter, so only the newest response is used
    var manifestGroupsCache = null; // { result, groups } built from manifestResult
    var manifestExpanded = {};      // resolved_id -> session detail open?
    var manifestFileCounts = {};    // resolved_id -> reimportable DICOM count (lazy, Reimport only)
    var manifestFileCountErr = {};  // resolved_id -> the count request failed (so the line stops saying "counting")

    // Statuses of rows that are not transferred, in the order the summary lists them.
    var MANIFEST_PROBLEM_CODES = ['subject_not_found', 'session_not_found', 'no_sessions', 'duplicate_session'];
    // The ones a submit skips; duplicate_session blocks the submit instead.
    var MANIFEST_SKIP_CODES = ['subject_not_found', 'session_not_found', 'no_sessions'];

    // -- Manifest pagination --------------------------------------------------------------------------
    // Offset-based pager: prev/next around a "start-end of total" range, plus one "Show all". Pages hold
    // whole subjects, so a subject's sessions are never split across two pages.
    // Only the manifest table pages; the interactive tree renders every row (its selection state lives in
    // the DOM, so rows can't be sliced out of it).
    // The page size is FIXED. Sizing it to the visible area only mattered while the workspace was a
    // bounded scroll box; the page is the only scroller now, so a page taller than the viewport simply
    // scrolls. Nothing here reacts to window size.
    var MANIFEST_PAGE_SIZE = 25;       // subjects per page
    var manifestStart = 0;             // index of the first subject on the current page
    var manifestPageStack = [];        // start indices of prior pages, for Prev
    var manifestShowAll = false;       // "Show all" -> render every subject (the page scrolls)

    function manifestTotal() { return manifestGroups().length; }

    function manifestPagerHtml(start, end, total) {
        // No pager when a single paginated page already shows everything.
        if (!manifestShowAll && start === 0 && end >= total) { return ''; }
        var prevDis = manifestShowAll || manifestPageStack.length === 0;
        var nextDis = manifestShowAll || end >= total;
        return '<div class="bt-pager"><span class="bt-pager-nav">' +
               '<button type="button" class="bt-pager-arrow" id="bt-page-prev"' + (prevDis ? ' disabled' : '') + '>&#8249;</button>' +
               '<span class="bt-pager-range">Subjects ' + (total ? start + 1 : 0) + '-' + end + ' of ' + total + '</span>' +
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
        $(document).on('click', '#bt-csv-replace', function() { resetManifest(); revalidateScript(); });
        $(document).on('click', '#bt-csv-retry', function() { resolveManifest(); });
        $(document).on('click', '.bt-csv-caret, .bt-files-lazy', function() {
            toggleManifestEntry($(this).attr('data-id'));
        });
        // Simple pager: prev/next walk the page by subject offset; Show all toggles pagination off (scrolls).
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

    // The dropzone's column hint, including what a blank source_session_label means.
    function refreshCsvRequiredHint() {
        $('#bt-csv-required').html('Required: <span class="bt-mono">source_subject_label</span>, ' +
            '<span class="bt-mono">source_session_label</span> &middot; optional routing: ' +
            '<span class="bt-mono">destination_subject_label</span>, <span class="bt-mono">destination_session_label</span>' +
            '<br>Leave <span class="bt-mono">source_session_label</span> blank to include all of a subject\'s image sessions.');
    }

    function resetManifest() {
        manifestSeq++;                 // a response still in flight no longer applies
        manifestText = null; manifestFileName = '';
        manifestResult = null;
        manifestPending = false; manifestError = null; manifestGroupsCache = null;
        manifestExpanded = {}; manifestFileCounts = {}; manifestFileCountErr = {};
        manifestStart = 0; manifestPageStack = []; manifestShowAll = false;
        $('#bt-csv-file').val('');
        $('#bt-csv-results').empty();
        $('#bt-dropzone').show();
        updateSummary();
    }

    function uploadManifest(file) {
        if (!csvSourceProject()) {
            xmodal.message('Batch Transfer', 'Manifest CSV upload is not supported for multiple source projects.');
            return;
        }
        var reader = new FileReader();
        reader.onload = function() {
            resetManifest();
            manifestText = String(reader.result || '');
            manifestFileName = file.name || 'manifest.csv';
            resolveManifest();
        };
        reader.onerror = function() {
            $('#bt-csv-results').html('<div class="bt-csv-error">Could not read ' + escapeHtml(file.name || 'the file') + '.</div>');
        };
        reader.readAsText(file);
    }

    // The kept manifest text as a named file part, with the fields /validate/manifest takes.
    function manifestFormData() {
        var fd = new FormData();
        fd.append('manifest', new Blob([manifestText], { type: 'text/csv' }), manifestFileName);
        fd.append('source_project', csvSourceProject());
        fd.append('mode', 'Reimport');
        return fd;
    }

    function resolveManifest() {
        if (manifestText == null) { return; }
        var seq = ++manifestSeq;
        manifestPending = true; manifestError = null;
        $('#bt-dropzone').hide();
        renderCsvResults();
        updateSummary();
        $.ajax({
            type: 'POST',
            url: XNAT.url.rootUrl('/xapi/transfer/validate/manifest'),
            data: manifestFormData(),
            processData: false,
            contentType: false,
            success: function(data) {
                if (seq !== manifestSeq) { return; }
                manifestPending = false;
                manifestResult = data;
                manifestGroupsCache = null;
                manifestStart = 0; manifestPageStack = []; manifestShowAll = false;
                renderCsvResults();
                revalidateScript();   // binding, against the manifest's columns
                updateSummary();
            },
            error: function(xhr) {
                if (seq !== manifestSeq) { return; }
                var message = xhr.status ? responseMessage(xhr) : 'no response from the server';
                // A 4xx rejects the file or the request, so start over. Anything else (no response, a timeout,
                // a 5xx) may pass on a retry, so the file is kept.
                if (xhr.status >= 400 && xhr.status < 500) {
                    resetManifest();
                    $('#bt-csv-results').html('<div class="bt-csv-error">Could not resolve the manifest: ' + escapeHtml(message) + '</div>');
                    revalidateScript();
                    return;
                }
                manifestPending = false;
                manifestResult = null; manifestGroupsCache = null;
                manifestError = message;
                renderCsvResults();
                revalidateScript();
                updateSummary();
            }
        });
    }

    // True when a result is loaded and no newer request is pending.
    function manifestCurrent() {
        return !!manifestResult && !manifestPending;
    }

    function manifestMatchedCount() {
        return (manifestResult && manifestResult.summary) ? (manifestResult.summary.matched || 0) : 0;
    }

    // The distinct row numbers of the entries pred accepts, ascending. A subject row's sessions share one.
    function manifestRowNumbers(pred) {
        var seen = {}, out = [];
        ((manifestResult && manifestResult.rows) || []).forEach(function(e) {
            if (pred(e) && !seen[e.row_number]) { seen[e.row_number] = true; out.push(e.row_number); }
        });
        return out.sort(function(a, b) { return a - b; });
    }

    function statusRows(code) {
        return manifestRowNumbers(function(e) { return e.status === code; });
    }

    function valueErrorRows() {
        return manifestRowNumbers(function(e) { return e.status === 'matched' && (e.value_errors || []).length > 0; });
    }

    // The rows of matched sessions that would arrive with the same session label as another session because
    // Preserve session labels is on: an explicit destination_session_label equal to another session's
    // preserved source label. Two explicit labels that clash are already a duplicate_destination_label value
    // error, so they aren't counted again here.
    function preservedLabelClashRows() {
        if (!$('#bt-preserve-session-label').is(':checked')) { return []; }
        var byLabel = Object.create(null);
        ((manifestResult && manifestResult.rows) || []).forEach(function(e) {
            if (e.status !== 'matched') { return; }
            var label = e.destination_session_label || e.source_session_label;
            if (!label) { return; }
            (byLabel[label] = byLabel[label] || []).push(e);
        });
        var seen = {}, out = [];
        Object.keys(byLabel).forEach(function(label) {
            var entries = byLabel[label];
            var preserved = entries.some(function(e) { return !e.destination_session_label; });
            if (entries.length < 2 || !preserved) { return; }
            entries.forEach(function(e) {
                if (!seen[e.row_number]) { seen[e.row_number] = true; out.push(e.row_number); }
            });
        });
        return out.sort(function(a, b) { return a - b; });
    }

    // "row 9", or "rows 3, 4, 7, 9, 12 and 4 more".
    function rowList(nums) {
        var more = nums.length > 5 ? ' and ' + (nums.length - 5) + ' more' : '';
        return (nums.length === 1 ? 'row ' : 'rows ') + nums.slice(0, 5).join(', ') + more;
    }

    function plural(n, noun) { return noun + (n === 1 ? '' : 's'); }
    function countText(n, noun) { return n + ' ' + plural(n, noun); }

    // The rows a submit will skip, by reason: { total: 2, text: '1 subject_not_found, 1 no_sessions' }.
    function manifestSkipped() {
        var total = 0, parts = [];
        MANIFEST_SKIP_CODES.forEach(function(code) {
            var n = statusRows(code).length;
            if (n) { total += n; parts.push(n + ' ' + code); }
        });
        return { total: total, text: parts.join(', ') };
    }

    // The server's message for a failed request, shortened if long; an HTML error page falls back to the status.
    function responseMessage(xhr) {
        var text = $.trim((xhr && xhr.responseText) || '');
        if (!text || text.charAt(0) === '<') { return 'HTTP ' + (xhr ? xhr.status : 'error'); }
        return text.length > 600 ? text.substring(0, 600) + '...' : text;
    }

    // Subject groups, in order of each subject's first row. When a subject has exactly one subject row, that
    // row's entries (for Reimport, the sessions it expanded to) belong to the group's header; every other entry
    // is a line under it. A subject with two subject rows has both as lines (they are duplicate_session).
    function manifestGroups() {
        if (manifestGroupsCache && manifestGroupsCache.result === manifestResult) { return manifestGroupsCache.groups; }
        var groups = [], byLabel = Object.create(null);
        ((manifestResult && manifestResult.rows) || []).forEach(function(e) {
            var label = e.source_subject_label || '';
            if (!byLabel[label]) { byLabel[label] = { label: label, entries: [] }; groups.push(byLabel[label]); }
            byLabel[label].entries.push(e);
        });
        groups.forEach(function(g) {
            var subjectRows = [];
            g.entries.forEach(function(e) {
                if (e.subject_row && subjectRows.indexOf(e.row_number) < 0) { subjectRows.push(e.row_number); }
            });
            var headRow = subjectRows.length === 1 ? subjectRows[0] : null;
            var onHead = function(e) { return headRow !== null && e.subject_row && e.row_number === headRow; };
            g.headEntries = g.entries.filter(onHead);
            g.lines = g.entries.filter(function(e) { return !onHead(e); });
        });
        manifestGroupsCache = { result: manifestResult, groups: groups };
        return groups;
    }

    function manifestBarHtml() {
        return '<div class="bt-manifest-bar"><span class="bt-csv-file">&#128196; ' + escapeHtml(manifestFileName) +
               '</span><a id="bt-csv-replace" class="bt-csv-replace-link">Replace file</a></div>';
    }

    function renderCsvResults() {
        if (manifestText == null) { $('#bt-csv-results').empty(); return; }
        // The manifest bar (file name + Replace file) shows whenever a manifest is loaded, so it is always clearable.
        var bar = manifestBarHtml();
        var r = manifestResult;
        if (manifestError) {
            $('#bt-csv-results').html(bar + '<div class="bt-csv-error">Could not resolve the manifest: ' +
                escapeHtml(manifestError) + ' <a id="bt-csv-retry" class="bt-csv-retry-link">Retry</a></div>');
            return;
        }
        if (manifestPending || !r) {
            $('#bt-csv-results').html(bar + '<div class="bt-csv-loading">Resolving manifest...</div>');
            return;
        }
        if (!r.required_present) {
            $('#bt-csv-results').html(bar + '<div class="bt-csv-error">Manifest is missing required column(s): ' +
                escapeHtml((r.missing_columns || []).join(', ')) + '.</div>');
            return;
        }
        var html = bar + manifestSummaryHtml();

        // Fixed column set: where each session comes from and what it becomes. Value columns are not shown as
        // columns (a manifest can carry any number of them); a session's values appear in its compiled-script
        // preview, and a bad value reports itself on the row's own error line.
        var rowHead = '<th class="bt-csv-rown" title="The row in the file, numbered as a spreadsheet shows it (the header is row 1)">Row</th>';
        // The destination headers always take two lines, so their width follows the labels under them.
        var cols = '<th class="bt-csv-caret-col"></th>' + rowHead + '<th>Source</th>' +
            '<th class="bt-route-head">Destination<br>subject</th><th class="bt-route-head">Destination<br>session</th>' +
            '<th>Files</th>';

        // Render the current page: MANIFEST_PAGE_SIZE subjects from manifestStart (or every subject on Show all).
        var groups = manifestGroups();
        var total = groups.length;
        var start = manifestShowAll ? 0 : Math.min(Math.max(0, manifestStart), total > 0 ? total - 1 : 0);
        manifestStart = start;
        var end = manifestShowAll ? total : Math.min(start + MANIFEST_PAGE_SIZE, total);
        var body = groups.slice(start, end).map(function(g) { return renderGroup(g); }).join('');

        // Horizontally-scrollable wrapper (not .bt-table-wrap, which clips): the manifest table can be wider
        // than the workspace, so it needs its own x-scroll.
        html += '<div class="bt-cohort-scroll"><table class="bt-table bt-cohort-table"><thead><tr>' +
                cols + '</tr></thead><tbody>' + body + '</tbody></table></div>';
        html += manifestPagerHtml(start, end, total);
        $('#bt-csv-results').html(html);
    }

    // "16 sessions in 7 subjects from 12 rows", then the rows by problem.
    function manifestSummaryHtml() {
        var matched = manifestMatchedCount();
        var rows = manifestResult.total_rows || 0;
        var subjects = manifestGroups().filter(function(g) {
            return g.entries.some(function(e) { return e.status === 'matched'; });
        }).length;
        var lead = '<strong>' + matched + '</strong> ' + plural(matched, 'session') +
                   ' in <strong>' + subjects + '</strong> ' + plural(subjects, 'subject') +
                   ' from <strong>' + rows + '</strong> ' + plural(rows, 'row');
        var parts = MANIFEST_PROBLEM_CODES.map(function(code) {
            var n = statusRows(code).length;
            return n ? '<span class="bt-csv-miss"><strong>' + n + '</strong> ' + code + '</span>' : '';
        });
        var errors = valueErrorRows().length;
        if (errors) {
            parts.push('<span class="bt-csv-miss"><strong>' + errors + '</strong> ' + plural(errors, 'row') + ' with value errors</span>');
        }
        return '<div class="bt-csv-head"><span class="bt-csv-ok">' + lead + '</span>' + parts.join('') + '</div>';
    }

    // One subject: a header line, then a line per manifest row with its row number. A subject's single
    // subject row shows on the header instead, with the sessions it expanded to listed under it.
    function renderGroup(g) {
        var head = g.headEntries[0] || null;
        var headMatched = !!head && head.status === 'matched';
        var listed = g.lines.filter(function(e) { return !e.subject_row && e.status === 'matched'; }).length;
        var scope = '';
        if (headMatched) {
            scope = 'subject row &middot; ' + countText(g.headEntries.length, 'session');
        } else if (!head && listed) {
            scope = countText(listed, 'session');
        }
        var problem = (head && !headMatched) ? head.status : null;
        var headErr = !!head && (head.value_errors || []).length > 0;
        var h = '<tr class="bt-csv-subj' + (problem ? ' bt-csv-missrow' : headErr ? ' bt-csv-rowerr' : '') + '">' +
                '<td class="bt-csv-caret-cell"></td>' +
                '<td class="bt-csv-rown">' + (head ? head.row_number : '') + '</td>' +
                '<td><span class="bt-mono bt-csv-subjlabel bt-csv-label">' + (g.label ? escapeHtml(g.label) : '(blank)') + '</span>' +
                (scope ? '<span class="bt-csv-scope">' + scope + '</span>' : '') +
                '</td>' + statusCells(problem) + '</tr>';
        if (head) { h += noteRow(head); }
        if (headMatched) {
            g.headEntries.forEach(function(e) { h += sessionLine(e, false); });
        }
        g.lines.forEach(function(e) {
            h += (e.status === 'matched') ? sessionLine(e, true) : problemLine(e);
            h += noteRow(e);
        });
        return h;
    }

    // The cells after Source: the two destination columns and Files. A problem shows its status code across them.
    function statusCells(status) {
        if (!status) { return '<td colspan="3"></td>'; }
        return '<td colspan="3" class="bt-csv-reason"><span class="bt-csv-x">&#10007;</span> <strong>' +
               escapeHtml(status) + '</strong></td>';
    }

    // A matched session. showRow is false for the sessions a subject row expanded to, whose row number is on
    // the subject's header line.
    function sessionLine(e, showRow) {
        var id = e.resolved_id || '';
        var hasErr = showRow && (e.value_errors || []).length > 0;
        var h = '<tr class="bt-csv-row' + (hasErr ? ' bt-csv-rowerr' : '') + '">' +
                '<td class="bt-csv-caret-cell"><span class="bt-csv-caret" data-id="' + escapeHtml(id) + '">' +
                (manifestExpanded[id] ? '&#9662;' : '&#9656;') + '</span></td>' +
                '<td class="bt-csv-rown">' + (showRow ? e.row_number : '') + '</td>' +
                '<td class="bt-mono bt-csv-src">' + labelHtml(e.source_session_label) + '</td>' +
                destinationCell(e, 'subject') + destinationCell(e, 'session') + '<td>' + filesCell(id) + '</td>' +
                '</tr>';
        if (manifestExpanded[id]) { h += detailRow(e); }
        return h;
    }

    // A row that won't be transferred: its session label (or what its subject row covers) and its status code.
    function problemLine(e) {
        var what = e.source_session_label
            ? labelHtml(e.source_session_label)
            : (e.subject_row ? '<span class="bt-muted">all sessions</span>' : '');
        return '<tr class="bt-csv-missrow"><td class="bt-csv-caret-cell"></td>' +
               '<td class="bt-csv-rown">' + e.row_number + '</td>' +
               '<td class="bt-mono bt-csv-src">' + what + '</td>' + statusCells(e.status) + '</tr>';
    }

    // An XNAT label in a manifest cell, wrapped so a long one wraps rather than widening the table.
    function labelHtml(label) {
        return '<span class="bt-csv-label">' + escapeHtml(label) + '</span>';
    }

    // A row's value errors (or, when it has none, its warnings), shown once under the row's line.
    function noteRow(e) {
        var errors = e.value_errors || [], warnings = e.value_warnings || [];
        if (!errors.length && !warnings.length) { return ''; }
        return '<tr class="bt-csv-noterow ' + (errors.length ? 'bt-csv-errrow' : 'bt-csv-warnrow') + '">' +
               '<td colspan="2"></td><td colspan="4">' +
               escapeHtml((errors.length ? errors : warnings).join('; ')) + '</td></tr>';
    }

    // A Reimport session's expanded detail: its file count and, with a custom script, the compiled script.
    function detailRow(e) {
        var id = e.resolved_id, fc = manifestFileCounts[id];
        var detail = '<p class="bt-csv-detail-line">Reimportable DICOM files: <strong>' +
            (fc != null ? fc : manifestFileCountErr[id] ? 'unavailable' : 'counting...') + '</strong></p>';
        if (hasCustomScript()) {
            detail += '<p class="bt-csv-detail-line bt-muted">Compiled DicomEdit for this session:</p>' +
                '<pre class="bt-script-preview">' + escapeHtml(compileScriptForRow(e)) + '</pre>';
        }
        return '<tr class="bt-csv-detailrow"><td colspan="2"></td><td colspan="4">' + detail + '</td></tr>';
    }

    // Short labels on purpose: this cell's text sets the Files column width, and the dotted underline already
    // marks it as clickable. The title carries the full explanation.
    function filesCell(id) {
        var fc = manifestFileCounts[id];
        if (fc != null) { return String(fc); }
        if (manifestFileCountErr[id]) {
            return '<span class="bt-muted" title="The file count could not be retrieved. Expand the row to retry.">n/a</span>';
        }
        return '<span class="bt-files-lazy" data-id="' + escapeHtml(id) + '"' +
               ' title="Count the reimportable DICOM files in this session">count</span>';
    }

    // What the session will actually be called in the destination, by the same precedence the server
    // applies at import: an explicit manifest destination_*_label wins, else the preserved source label,
    // else the label is derived from DICOM tags during import and cannot be known here. Showing the
    // resolved value (not just the manifest column) is the point of these two columns.
    function destinationCell(row, which) {
        var subject = which === 'subject';
        var explicit = subject ? row.destination_subject_label : row.destination_session_label;
        var dsubj = subject ? ' bt-csv-dsubj' : '';
        if (explicit) {
            return '<td class="bt-mono bt-route' + dsubj + '">' + labelHtml(explicit) + '</td>';
        }
        var preserved = subject
            ? $('#bt-preserve-subject-label').is(':checked')
            : $('#bt-preserve-session-label').is(':checked');
        if (preserved) {
            return '<td class="bt-mono bt-dest-inherited' + dsubj + '" title="Preserved from the source label">'
                 + labelHtml(subject ? row.source_subject_label : row.source_session_label) + '</td>';
        }
        return '<td class="bt-muted" title="Derived from DICOM tags when the session is imported">'
             + 'from DICOM</td>';
    }

    function toggleManifestEntry(id) {
        if (!id) { return; }
        manifestExpanded[id] = !manifestExpanded[id];
        if (manifestExpanded[id] && manifestFileCounts[id] == null) {
            delete manifestFileCountErr[id];   // re-expanding retries a count that failed
            fetchFileCount(id);
        }
        renderCsvResults();
    }

    // Lazy per-session file_count: re-POST a one-row manifest with file_count=true (only the source labels
    // are needed for the count).
    function fetchFileCount(id) {
        var entry = findManifestEntry(id);
        if (!entry) { return; }
        var csv = 'source_subject_label,source_session_label\n' +
                  csvCell(entry.source_subject_label) + ',' + csvCell(entry.source_session_label) + '\n';
        $.ajax({
            type: 'POST',
            url: XNAT.url.rootUrl('/xapi/transfer/validate/manifest'),
            contentType: 'application/json',
            data: JSON.stringify({ source_project: csvSourceProject(), mode: 'Reimport', file_count: true, manifest_csv: csv }),
            success: function(data) {
                var rows = (data && data.rows) || [];
                if (rows.length && rows[0].file_count != null) {
                    manifestFileCounts[id] = rows[0].file_count;
                } else {
                    manifestFileCountErr[id] = true;
                }
                if (manifestExpanded[id]) { renderCsvResults(); }
            },
            // Without this the line is stuck reading "counting..." forever.
            error: function() {
                manifestFileCountErr[id] = true;
                if (manifestExpanded[id]) { renderCsvResults(); }
            }
        });
    }

    function findManifestEntry(id) {
        var rows = (manifestResult && manifestResult.rows) || [];
        for (var i = 0; i < rows.length; i++) { if (rows[i].resolved_id === id) { return rows[i]; } }
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

    // One Reimport item per matched manifest entry, which is one per session once subject rows are expanded.
    function buildCsvItems() {
        if (!manifestResult) return [];
        var preserveSubjectLabel = $('#bt-preserve-subject-label').is(':checked');
        var preserveSessionLabel = $('#bt-preserve-session-label').is(':checked');
        var items = [];
        (manifestResult.rows || []).forEach(function(row) {
            if (row.status !== 'matched' || !row.resolved_id) return;
            var item = {
                id: row.resolved_id,
                mode: 'Reimport',
                destination_project: selectedProject,
                preserve_subject_label: preserveSubjectLabel,
                preserve_session_label: preserveSessionLabel
            };
            if (row.csv_values) { item.csv_values = row.csv_values; }
            if (row.destination_subject_label) { item.destination_subject_label = row.destination_subject_label; }
            if (row.destination_session_label) { item.destination_session_label = row.destination_session_label; }
            if (row.row_number) { item.row_number = row.row_number; }
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

    function buildConfirmationHtml(count) {
        var isCsv = cohortMode === 'csv';
        var noun = (selectedOp === 'Reimport' ? 'session' : 'item') + (count === 1 ? '' : 's');
        var src = config.projectContext ? escapeHtml(config.projectContext) : 'the source project';
        var dest = escapeHtml(selectedProject);
        var script = hasCustomScript();

        var rows = dlgRow('Cohort', isCsv
            ? escapeHtml(manifestFileName) + ' &middot; <strong>' + count + ' ' + noun + '</strong> from ' +
              countText(manifestResult.total_rows || 0, 'row')
            // lastTreeCount is current whenever the tree is the active cohort, which is the only case
            // this branch runs in, so the counts need no second walk of the table.
            : 'interactive &middot; <strong>' + escapeHtml(formatItemList(lastTreeCount)) + '</strong>');
        // Rows the run will skip are stated before it starts, by reason.
        var skipped = isCsv ? manifestSkipped() : { total: 0 };
        if (skipped.total) {
            rows += dlgRow('Skipped', '<span style="color:#b06000;">' + countText(skipped.total, 'row') +
                ' will be skipped: ' + escapeHtml(skipped.text) + '</span>');
        }
        rows += dlgRow('Operation', selectedOp + ' &middot; ' + src + ' to ' + dest);

        if (selectedOp === 'Reimport') {
            // The scripts that run, in order. A check that failed or hasn't answered yet is named.
            var scripts = anonScripts();
            var on = scriptsIn(scripts, 'on'), unknown = scriptsIn(scripts, 'unknown');
            var anonText;
            if (on.length) {
                var order = scriptNames(on, ', then ');
                anonText = escapeHtml(order.charAt(0).toUpperCase() + order.slice(1)) +
                    (script && on.length > 1 ? ' <strong>(double anonymization)</strong>' : '') +
                    (unknown.length ? '; couldn\'t check whether ' + escapeHtml(scriptNames(unknown, ' or ')) +
                                      ' is enabled' : '');
            } else if (unknown.length) {
                anonText = 'Not checked - couldn\'t check whether ' + escapeHtml(scriptNames(unknown, ' or ')) +
                           ' is enabled';
            } else {
                anonText = '<span style="color:#c5221f;">None - sessions will not be anonymized</span>';
            }
            rows += dlgRow('Anonymization', anonText);
            if (script) { rows += dlgRow('Custom script', 'applied per session' + (isCsv ? ' (values from manifest)' : '')); }
            var preserve = [];
            if ($('#bt-preserve-subject-label').is(':checked')) { preserve.push('subject'); }
            if ($('#bt-preserve-session-label').is(':checked')) { preserve.push('session'); }
            var labelText = preserve.length ? 'preserve source ' + preserve.join(' + ') + ' label(s)' : 'derive from DICOM tags';
            if (isCsv) { labelText += '; a destination_*_label in the manifest takes precedence'; }
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
        var caution = 'Data is written to the destination archive; there is no automatic rollback.';

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
        var count = items.length;
        if (count === 0) {
            xmodal.message('Batch Transfer', csv ? 'No matched sessions to transfer.' : 'Please select at least one item');
            return false;
        }

        if (anonBlocks()) {
            xmodal.message('Batch Transfer', 'The custom anonymization script is not valid. Fix it (Validate) before submitting.');
            return false;
        }

        var noun = (selectedOp === 'Reimport' ? 'session' : 'item') + (count === 1 ? '' : 's');
        xModalConfirm({
            title: selectedOp + ' ' + count + ' ' + noun + '?',
            content: buildConfirmationHtml(count),
            okAction: function() {
                // Guard against a second submission while one is already in flight.
                if (submitInFlight) return;
                submitInFlight = true;
                $('#bt-submit-btn').prop('disabled', true);
                postItems(items);
            }
        });
    };

    function postItems(items) {
        var batchTransfer = {
            requests: items,
            tracking_id: 'batch_transfer_' + Date.now()
        };
        // Batch-level Anon-Script template (Reimport + custom script); per-item ${csv.*} values ride each
        // request's csv_values. Omitted entirely when no custom script is set.
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
                // Opens History with the new run highlighted, so Stop is one click away. New transfer is
                // only hidden, so its selections are still there on the way back.
                if (XNAT.plugin.batchtransfer.history) {
                    XNAT.plugin.batchtransfer.history.showSubmitted(batchTransfer.tracking_id);
                }
            },
            error: function(xhr) {
                xmodal.message('Error', 'The transfer was not submitted: ' + escapeHtml(responseMessage(xhr)));
            },
            complete: submitDone
        });
    }

    // Re-render rather than force-enabling, so the button still honours any outstanding block reason
    // instead of being clickable regardless.
    function submitDone() {
        submitInFlight = false;
        renderActionSummary();
    }

}));

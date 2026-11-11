/*
 * Batch Transfer screen: the help column on New transfer. It holds a topic per section, sticks to the top of the
 * window above the action bar, scrolls on its own, and collapses to a strip. The "?" beside a section scrolls the
 * column so that section's topic is level with it; clicking or tabbing into a section outlines its topic.
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
    var api = XNAT.plugin.batchtransfer.help = getObject(XNAT.plugin.batchtransfer.help || {});

    var STORE_KEY = 'bt-help-hidden';   // per viewer: '1' while the column is collapsed
    var SLIDE_MS = 150;                 // the width transition in batchTransfer.css
    var NO_SLIDE_ROWS = 2000;           // the slide reflows the cohort table every frame, so a large tree skips it
    var EDGE_PX = 60;                   // a section this close to the action bar counts as off screen

    var shown = true;
    var active = null;                  // the topic outlined
    var layoutTimer = null;

    api.init = function() {
        var screen = screenEl();
        if (!screen || !document.getElementById('bt-help-notes')) { return; }
        shown = readShown();
        screen.classList.toggle('bt-help-hidden', !shown);
        screen.classList.toggle('bt-help-noslide', document.querySelectorAll('#bt-data-body tr').length > NO_SLIDE_ROWS);
        paintToggle();
        bind();
        scheduleLayout();
    };

    // Shows the topics that apply to the operation, and the script topic while the script editor shows.
    api.sync = function(op, script) {
        $('#bt-help-notes [data-help-ops]').each(function() {
            this.hidden = (' ' + this.getAttribute('data-help-ops') + ' ').indexOf(' ' + op + ' ') < 0;
        });
        $('#bt-help-notes .bt-help-opname').text(op);
        var scriptTopic = document.querySelector('#bt-help-notes [data-help="script"]');
        if (scriptTopic) { scriptTopic.hidden = !script; }
        if (active && !topic(active)) { active = null; }
        paintActive(false);
        scheduleLayout();
    };

    // Outlines a topic, as when its section is used.
    api.highlight = function(id) {
        setActive(id, true);
    };

    // Redoes the measurements, for instance when New transfer is shown again after History.
    api.layout = function() {
        layout();
    };

    function bind() {
        $(document).on('click', '#bt-help-toggle', function() {
            setShown(!shown);
        });
        $(document).on('click', '#bt-view-transfer .bt-q', function(e) {
            e.preventDefault();
            var id = this.getAttribute('data-help-for');
            setActive(id, true);
            if (shown) {
                scrollToTopic(id, true);
            } else {
                setShown(true, function() { scrollToTopic(id, true); });
            }
        });
        // Using a section outlines its topic without moving the column.
        $(document).on('mousedown focusin', '#bt-view-transfer .bt-workspace [data-sect]', function(e) {
            if ($(e.target).closest('.bt-q').length) { return; }
            setActive(this.getAttribute('data-sect'), false);
        });
        $(window).on('resize.bthelp', scheduleLayout);
        if (window.ResizeObserver) {
            // The action bar grows when its chips wrap, and the workspace when panels open or the table changes.
            var observer = new ResizeObserver(scheduleLayout);
            ['#bt-view-transfer .bt-action-bar', '#bt-view-transfer .bt-workspace'].forEach(function(selector) {
                var el = document.querySelector(selector);
                if (el) { observer.observe(el); }
            });
        }
    }

    function scheduleLayout() {
        clearTimeout(layoutTimer);
        layoutTimer = setTimeout(layout, 50);
    }

    // Keeps the column within the room above the action bar, and pads the topics: above the first, so it starts
    // level with the setup card at the top of the page, and below the last, so any topic can scroll level with its
    // section.
    function layout() {
        var inner = document.querySelector('#bt-view-transfer .bt-help-in');
        var notes = document.getElementById('bt-help-notes');
        if (!inner || !notes || !inner.offsetParent) { return; }   // History is showing
        var bar = document.querySelector('#bt-view-transfer .bt-action-bar');
        var height = Math.max(160, window.innerHeight - (bar ? bar.offsetHeight : 0));
        inner.style.maxHeight = height + 'px';
        if (!shown) { return; }
        var body = document.querySelector('#bt-view-transfer .bt-body');
        var setup = document.querySelector('#bt-view-transfer .bt-setup');
        var head = notes.getBoundingClientRect().top - inner.getBoundingClientRect().top;
        if (body && setup) {
            var above = setup.getBoundingClientRect().top - body.getBoundingClientRect().top - head;
            notes.style.paddingTop = Math.max(0, Math.round(above)) + 'px';
        }
        var last = lastShownTopic();
        notes.style.paddingBottom = Math.max(12, Math.round(height - head - (last ? last.offsetHeight : 0))) + 'px';
    }

    // Scrolls the column (not the page) so the topic is level with its section. A section above the column's
    // visible top, or too close to the action bar, gets its topic at the top of the column's visible part instead.
    // (At the end of a short page the column itself can be partly scrolled off the top of the window.)
    function scrollToTopic(id, smooth) {
        var notes = document.getElementById('bt-help-notes');
        var note = topic(id);
        if (!notes || !note) { return; }
        var box = notes.getBoundingClientRect();
        var bar = document.querySelector('#bt-view-transfer .bt-action-bar');
        var visibleTop = Math.max(box.top, 0);
        var visibleBottom = Math.min(box.bottom, bar ? bar.getBoundingClientRect().top : window.innerHeight);
        var top = sectionTop(id);
        var at = top === null || top < visibleTop || top > visibleBottom - EDGE_PX ? visibleTop : top;
        var target = Math.max(0, note.offsetTop - (at - box.top));
        if (notes.scrollTo) {
            notes.scrollTo({ top: target, behavior: smooth ? 'smooth' : 'auto' });
        } else {
            notes.scrollTop = target;
        }
    }

    // A section's top on screen. The setup card's first row lines up with the card's own top edge.
    function sectionTop(id) {
        var sect = document.querySelector('#bt-view-transfer .bt-workspace [data-sect="' + id + '"]');
        if (!sect || !sect.offsetParent) { return null; }
        var edge = sect.classList.contains('bt-setup-row') && !sect.previousElementSibling ? sect.parentNode : sect;
        return edge.getBoundingClientRect().top;
    }

    // Collapses or opens the column in place, so the width change can slide. The topics' padding depends on the
    // new width, so it is redone once the slide has finished.
    function setShown(show, then) {
        shown = show;
        try { localStorage.setItem(STORE_KEY, show ? '0' : '1'); } catch (e) { /* storage unavailable: not kept */ }
        screenEl().classList.toggle('bt-help-hidden', !show);
        paintToggle();
        setTimeout(function() {
            layout();
            if (then) { then(); }
        }, SLIDE_MS + 30);
    }

    function readShown() {
        try { return localStorage.getItem(STORE_KEY) !== '1'; } catch (e) { return true; }
    }

    function paintToggle() {
        var head = document.getElementById('bt-help-toggle');
        if (!head) { return; }
        head.title = shown ? 'Hide help' : 'Show help';
        head.setAttribute('aria-expanded', String(shown));
    }

    function setActive(id, flash) {
        if (!topic(id)) { return; }
        active = id;
        paintActive(flash);
    }

    function paintActive(flash) {
        $('#bt-help-notes .bt-help-note').each(function() {
            var on = this.getAttribute('data-help') === active;
            this.classList.toggle('on', on);
            if (on && flash) {
                this.classList.remove('bt-help-flash');
                void this.offsetWidth;   // restarts the animation
                this.classList.add('bt-help-flash');
            }
        });
    }

    function topic(id) {
        var el = document.querySelector('#bt-help-notes .bt-help-note[data-help="' + id + '"]');
        return el && !el.hidden ? el : null;
    }

    function lastShownTopic() {
        var all = document.querySelectorAll('#bt-help-notes .bt-help-note');
        for (var i = all.length - 1; i >= 0; i--) {
            if (!all[i].hidden) { return all[i]; }
        }
        return null;
    }

    function screenEl() {
        return document.querySelector('#bt-view-transfer .bt-screen');
    }
}));

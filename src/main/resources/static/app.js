// AccessWindow UI. DOM is built with textContent only (no innerHTML): the CSP enforces Trusted Types.
(function () {
  'use strict';

  var $ = function (selector) { return document.querySelector(selector); };
  var ACTOR_KEY = 'aw-actor';
  var STATUS = {
    on_track: { label: 'ACTIVE', css: 'good' },
    due_soon: { label: 'REVIEW SOON', css: 'soon' },
    overdue: { label: 'PAST DUE', css: 'late' },
    revoked: { label: 'REVOKED', css: 'revoked' }
  };
  var FILTERS = {
    all: function () { return true; },
    review: function (r) { return r.review_status === 'due_soon' || r.review_status === 'overdue'; },
    active: function (r) { return r.status === 'active'; },
    revoked: function (r) { return r.status === 'revoked'; }
  };

  var state = { records: [], policy: null, filter: 'all', loaded: false, current: null };

  function ApiError(message, status, errors) {
    this.message = message;
    this.status = status;
    this.errors = errors || [];
  }

  async function api(url, options) {
    var init = Object.assign({ headers: { Accept: 'application/json' } }, options || {});
    if (init.body) init.headers['Content-Type'] = 'application/json';
    var response;
    try {
      response = await fetch(url, init);
    } catch (e) {
      throw new ApiError('Network error. Check your connection and try again.', 0);
    }
    var type = response.headers.get('Content-Type') || '';
    var data = type.indexOf('json') !== -1 ? await response.json().catch(function () { return null; }) : null;
    if (!response.ok) {
      var message = (data && (data.detail || data.error || data.title)) || 'Request failed (' + response.status + ').';
      throw new ApiError(message, response.status, data && data.errors);
    }
    return data;
  }

  // ---------- formatting ----------
  var dateFormat = new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric', year: 'numeric', timeZone: 'UTC' });
  var instantFormat = new Intl.DateTimeFormat('en-US', { dateStyle: 'medium', timeStyle: 'short' });

  function parseIsoDate(iso) {
    var p = String(iso).split('-').map(Number);
    return new Date(Date.UTC(p[0], p[1] - 1, p[2]));
  }
  function formatDate(iso) { return iso ? dateFormat.format(parseIsoDate(iso)) : '—'; }
  function addDays(iso, days) {
    var d = parseIsoDate(iso);
    d.setUTCDate(d.getUTCDate() + days);
    return d.toISOString().slice(0, 10);
  }
  function minDate(a, b) { return a < b ? a : b; }
  function daysText(record) {
    if (record.status === 'revoked') return 'Revoked';
    var n = record.days_left;
    if (n < -1) return Math.abs(n) + ' days overdue';
    if (n === -1) return '1 day overdue';
    if (n === 0) return 'Due today';
    if (n === 1) return 'Due tomorrow';
    return 'In ' + n + ' days';
  }
  function plural(n, word) { return n + ' ' + word + (n === 1 ? '' : 's'); }

  // ---------- DOM helpers ----------
  function el(tag, props, children) {
    var node = document.createElement(tag);
    Object.keys(props || {}).forEach(function (key) {
      if (key === 'text') node.textContent = props[key];
      else if (key === 'className') node.className = props[key];
      else node.setAttribute(key, props[key]);
    });
    (children || []).forEach(function (child) { if (child) node.appendChild(child); });
    return node;
  }
  function badge(record) {
    var s = STATUS[record.review_status] || STATUS.on_track;
    return el('span', { className: 'badge ' + s.css, text: s.label });
  }

  var toastTimer;
  function toast(message) {
    var node = $('#toast');
    node.textContent = message;
    node.classList.add('visible');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { node.classList.remove('visible'); }, 4000);
  }

  function setBusy(form, busy, label) {
    form.setAttribute('aria-busy', String(busy));
    form.querySelectorAll('button[type=submit]').forEach(function (button) {
      button.disabled = busy;
      if (busy && button === label) {
        button.dataset.label = button.textContent;
        button.textContent = 'Saving…';
      } else if (!busy && button.dataset.label) {
        button.textContent = button.dataset.label;
        delete button.dataset.label;
      }
    });
  }

  function clearErrors(form) {
    form.querySelectorAll('[aria-invalid]').forEach(function (n) { n.removeAttribute('aria-invalid'); });
    form.querySelectorAll('.field-error').forEach(function (n) { n.textContent = ''; });
    var banner = form.querySelector('.form-error');
    banner.hidden = true;
    banner.textContent = '';
  }

  function showErrors(form, prefix, error) {
    var first = null;
    (error.errors || []).forEach(function (e) {
      var input = form.querySelector('[name="' + e.field + '"]');
      var slot = document.getElementById(prefix + e.field);
      if (input && slot) {
        input.setAttribute('aria-invalid', 'true');
        slot.textContent = e.message;
        first = first || input;
      }
    });
    if (!first) {
      var banner = form.querySelector('.form-error');
      banner.textContent = error.message;
      banner.hidden = false;
    }
    if (first) first.focus();
  }

  function requireFields(form, prefix, names) {
    var missing = names.filter(function (name) {
      var input = form.querySelector('[name="' + name + '"]');
      return !input.value.trim() || (input.validity && !input.validity.valid);
    });
    if (!missing.length) return true;
    showErrors(form, prefix, {
      errors: missing.map(function (name) {
        var input = form.querySelector('[name="' + name + '"]');
        var label = form.querySelector('label[for="' + input.id + '"]');
        var text = label ? label.firstChild.textContent.trim() : name;
        return { field: name, message: input.value.trim() ? 'Enter a valid ' + text.toLowerCase() + '.' : text + ' is required.' };
      })
    });
    return false;
  }

  function rememberedActor() {
    try { return localStorage.getItem(ACTOR_KEY) || ''; } catch (e) { return ''; }
  }
  function rememberActor(name) {
    try { if (name) localStorage.setItem(ACTOR_KEY, name); } catch (e) { /* optional convenience */ }
  }

  // ---------- register ----------
  function setRegisterState(message, canRetry) {
    var box = $('#register-state');
    $('#register-message').textContent = message || '';
    $('#retry').hidden = !canRetry;
    box.hidden = !message;
  }

  function visibleRecords() {
    var query = $('#search').value.trim().toLowerCase();
    return state.records.filter(FILTERS[state.filter]).filter(function (r) {
      return !query || [r.email, r.resource, r.owner, r.role, r.reason].join(' ').toLowerCase().indexOf(query) !== -1;
    });
  }

  function row(record) {
    var domain = record.email.indexOf('@') > 0 ? record.email.slice(record.email.indexOf('@')) : '';
    var manage = el('button', {
      type: 'button',
      className: 'action',
      'data-id': String(record.id),
      'aria-label': 'Manage access for ' + record.email + ' to ' + record.resource,
      text: '···'
    });
    var tr = el('tr', { className: record.status === 'revoked' ? 'is-revoked' : '' }, [
      el('td', { className: 'person-cell', 'data-label': 'Person' }, [
        el('div', { className: 'person' }, [
          el('span', { className: 'avatar', 'aria-hidden': 'true', text: record.email.slice(0, 2).toUpperCase() }),
          el('div', {}, [el('div', { className: 'email-main', text: record.email }), el('div', { className: 'sub', text: domain })])
        ])
      ]),
      el('td', { 'data-label': 'Resource', text: record.resource }),
      el('td', { 'data-label': 'Role' }, [el('span', { className: 'role', text: record.role })]),
      el('td', { className: 'owner-cell', 'data-label': 'Owner' }, [
        el('span', { className: 'owner', text: record.owner }),
        el('div', { className: 'sub', text: record.reason })
      ]),
      el('td', { className: 'date', 'data-label': 'Review date' }, [
        el('time', { datetime: record.expires_at, text: formatDate(record.expires_at) }),
        el('div', { className: 'sub', text: daysText(record) })
      ]),
      el('td', { 'data-label': 'Status' }, [badge(record)]),
      el('td', { className: 'actions-cell' }, [manage])
    ]);
    return tr;
  }

  function draw() {
    if (!state.loaded) return;
    var rows = visibleRecords();
    $('#rows').replaceChildren.apply($('#rows'), rows.map(row));
    if (!state.records.length) setRegisterState('No access records yet. Use “Add access” to track the first one.');
    else if (!rows.length) setRegisterState('No access records match this view.');
    else setRegisterState('');
    document.querySelectorAll('.filter').forEach(function (b) {
      b.setAttribute('aria-pressed', String(b.dataset.filter === state.filter));
    });
  }

  function drawSummary(summary, queue) {
    ['total', 'expiring', 'overdue', 'resources'].forEach(function (k) { $('#' + k).textContent = String(summary[k]); });
    var days = state.policy ? state.policy.dueSoonDays : 14;
    $('#expiring-hint').textContent = 'within the next ' + days + ' days';
    $('#review-count').textContent = plural(queue, 'record') + ' in the review queue (overdue or due within ' + days + ' days).';
  }

  async function refresh() {
    setRegisterState(state.loaded ? '' : 'Loading access records…');
    try {
      var results = await Promise.all([api('/api/access'), api('/api/summary'), api('/api/policy')]);
      state.records = results[0];
      state.policy = results[2];
      state.loaded = true;
      $('#today').textContent = formatDate(state.policy.today) + ' (' + state.policy.timeZone + ')';
      drawSummary(results[1], state.records.filter(FILTERS.review).length);
      draw();
    } catch (error) {
      if (!state.loaded) $('#rows').replaceChildren();
      setRegisterState('Could not load access records. ' + error.message, true);
    }
  }

  // ---------- create ----------
  function openCreate() {
    var form = $('#create-form');
    form.reset();
    clearErrors(form);
    var date = $('#f-expires');
    if (state.policy) {
      date.min = state.policy.today;
      date.max = state.policy.maxReviewDate;
      date.value = minDate(addDays(state.policy.today, 90), state.policy.maxReviewDate);
    }
    $('#f-actor').value = rememberedActor();
    $('#create-dialog').showModal();
    $('#f-email').focus();
  }

  async function submitCreate(event) {
    event.preventDefault();
    var form = event.target;
    if (form.getAttribute('aria-busy') === 'true') return;
    clearErrors(form);
    if (!requireFields(form, 'e-', ['email', 'resource', 'owner', 'reason', 'expiresAt'])) return;
    var body = Object.fromEntries(new FormData(form));
    if (!body.actor) delete body.actor;
    setBusy(form, true, form.querySelector('.submit'));
    try {
      var created = await api('/api/access', { method: 'POST', body: JSON.stringify(body) });
      rememberActor(body.actor);
      $('#create-dialog').close();
      toast('Access for ' + created.email + ' to ' + created.resource + ' is now tracked.');
      await refresh();
    } catch (error) {
      showErrors(form, 'e-', error);
    } finally {
      setBusy(form, false);
    }
  }

  // ---------- manage ----------
  async function loadHistory(id) {
    var list = $('#history');
    var status = $('#history-state');
    list.replaceChildren();
    status.textContent = 'Loading…';
    try {
      var events = await api('/api/access/' + id + '/events');
      if (state.current !== id) return;
      list.replaceChildren.apply(list, events.slice().reverse().map(function (e) {
        var what = { created: 'Created', revoked: 'Revoked', renewed: 'Renewed' }[e.type] || e.type;
        if (e.type === 'renewed' && e.newExpiresAt) what += ' until ' + formatDate(e.newExpiresAt);
        return el('li', {}, [
          el('p', { className: 'event-title', text: what + ' by ' + e.actor }),
          el('p', { className: 'sub' }, [el('time', { datetime: e.occurredAt, text: instantFormat.format(new Date(e.occurredAt)) })]),
          e.note ? el('p', { className: 'event-note', text: e.note }) : null
        ]);
      }));
      status.textContent = events.length ? '' : 'No decisions recorded yet.';
    } catch (error) {
      status.textContent = 'Could not load the decision trail. ' + error.message;
    }
  }

  function openManage(id) {
    var record = state.records.find(function (r) { return r.id === id; });
    if (!record) return;
    state.current = id;
    var form = $('#manage-form');
    form.reset();
    clearErrors(form);
    $('#manage-subtitle').textContent = record.email + ' → ' + record.resource;
    $('#m-role').textContent = record.role;
    $('#m-owner').textContent = record.owner;
    $('#m-reason').textContent = record.reason;
    $('#m-date').textContent = formatDate(record.expires_at) + ' · ' + daysText(record);
    $('#m-badge').replaceWith(Object.assign(badge(record), { id: 'm-badge' }));
    var revoked = record.status === 'revoked';
    $('#revoke-button').hidden = revoked;
    $('#renew-button').textContent = revoked ? 'Restore access' : 'Renew access';
    var date = $('#d-expires');
    if (state.policy) {
      date.min = addDays(state.policy.today, 1);
      date.max = state.policy.maxReviewDate;
      date.value = minDate(addDays(state.policy.today, 90), state.policy.maxReviewDate);
    }
    $('#d-actor').value = rememberedActor();
    $('#manage-dialog').showModal();
    ($('#d-actor').value ? $('#d-note') : $('#d-actor')).focus();
    loadHistory(id);
  }

  async function submitDecision(event) {
    event.preventDefault();
    var form = event.target;
    if (form.getAttribute('aria-busy') === 'true' || state.current === null) return;
    var submitter = event.submitter || $('#renew-button');
    var action = submitter.value;
    clearErrors(form);
    var required = action === 'renew' ? ['actor', 'expiresAt', 'note'] : ['actor', 'note'];
    if (!requireFields(form, 'e-d-', required)) return;
    var data = Object.fromEntries(new FormData(form));
    var body = { action: action, actor: data.actor, note: data.note };
    if (action === 'renew') body.expiresAt = data.expiresAt;
    var id = state.current;
    setBusy(form, true, submitter);
    try {
      var result = await api('/api/access/' + id, { method: 'PATCH', body: JSON.stringify(body) });
      rememberActor(data.actor);
      $('#manage-dialog').close();
      toast(action === 'revoke'
        ? 'Revoked ' + result.access.email + ' on ' + result.access.resource + '. Remove it in the source system too.'
        : 'Renewed ' + result.access.email + ' until ' + formatDate(result.access.expires_at) + '.');
      await refresh();
    } catch (error) {
      if (error.status === 409 || error.status === 404) await refresh();
      showErrors(form, 'e-d-', error);
    } finally {
      setBusy(form, false);
    }
  }

  // ---------- wiring ----------
  $('#search').addEventListener('input', draw);
  $('#retry').addEventListener('click', refresh);
  $('#add').addEventListener('click', openCreate);
  $('#create-form').addEventListener('submit', submitCreate);
  $('#manage-form').addEventListener('submit', submitDecision);
  $('#rows').addEventListener('click', function (event) {
    var button = event.target.closest('.action');
    if (button) openManage(Number(button.dataset.id));
  });
  document.querySelectorAll('.filter').forEach(function (button) {
    button.addEventListener('click', function () {
      state.filter = button.dataset.filter;
      draw();
    });
  });
  document.querySelectorAll('dialog').forEach(function (dialog) {
    dialog.querySelector('[data-close]').addEventListener('click', function () { dialog.close(); });
    dialog.addEventListener('close', function () {
      if (dialog.id === 'manage-dialog') state.current = null;
    });
  });

  refresh();
})();

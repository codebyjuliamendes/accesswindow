// Applies the stored theme before first paint; loaded synchronously from <head> (CSP forbids inline scripts).
(function () {
  'use strict';
  var KEY = 'aw-theme';
  var root = document.documentElement;
  var media = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;

  function stored() {
    try {
      var value = window.localStorage.getItem(KEY);
      return value === 'light' || value === 'dark' ? value : null;
    } catch (e) {
      return null;
    }
  }

  function effective() {
    return stored() || (media && media.matches ? 'dark' : 'light');
  }

  var initial = stored();
  if (initial) root.setAttribute('data-theme', initial);

  function sync(button) {
    var dark = effective() === 'dark';
    var label = dark ? 'Switch to light theme' : 'Switch to dark theme';
    button.setAttribute('aria-pressed', String(dark));
    button.setAttribute('aria-label', label);
    button.title = label;
  }

  document.addEventListener('DOMContentLoaded', function () {
    var button = document.getElementById('theme-toggle');
    if (!button) return;
    sync(button);
    button.addEventListener('click', function () {
      var next = effective() === 'dark' ? 'light' : 'dark';
      root.setAttribute('data-theme', next);
      try {
        window.localStorage.setItem(KEY, next);
      } catch (e) {
        /* storage unavailable: theme still applies for this page view */
      }
      sync(button);
    });
    if (media && media.addEventListener) media.addEventListener('change', function () { sync(button); });
  });
})();

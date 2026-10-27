// Opens the dialog named in data-open-dialog (the catalog dialog) with the search field focused; data-close-dialog
// closes the dialog it is in.
function setKeepOpen(dialog, keep) {
  if (keep) {
    dialog.dataset.keepOpen = 'true';
  } else {
    delete dialog.dataset.keepOpen;
  }
}

// A catalog dialog starts and ends without half-finished state of an earlier use.
function resetCatalogDialog(dialog) {
  var search = dialog.querySelector('[data-catalog-search]');

  setKeepOpen(dialog, false);

  if (search) {
    delete search.dataset.pendingEnter;
  }
}

document.addEventListener('close', function (event) {
  if (event.target.classList && event.target.classList.contains('catalog-dialog')) {
    resetCatalogDialog(event.target);
  }
}, true);

document.addEventListener('click', function (event) {
  var opener = event.target.closest('[data-open-dialog]');
  var closer = event.target.closest('[data-close-dialog]');
  var hit = event.target.closest('.catalog-result');

  // Shift+click adds the entry and keeps the dialog open.
  if (hit) {
    setKeepOpen(hit.closest('dialog'), event.shiftKey);
  }

  if (opener) {
    var dialog = document.getElementById(opener.getAttribute('data-open-dialog'));
    var search = dialog.querySelector('[data-catalog-search]');

    resetCatalogDialog(dialog);
    dialog.showModal();

    if (search) {
      search.focus();
      search.select();
    }
  }

  if (closer) {
    closer.closest('dialog').close();
  }
});

// A tax category without a rate (e.g. exempt) fixes the rate of the item at 0 and locks the field.
document.addEventListener('change', function (event) {
  var select = event.target.closest('select.tax-category-select');

  if (!select) {
    return;
  }

  var rated = select.selectedOptions[0].getAttribute('data-rated') === 'true';
  var rate = select.closest('.rate').querySelector('input');

  rate.readOnly = !rated;

  if (!rated) {
    rate.value = '0';
  }

  rate.dispatchEvent(new Event('input', { bubbles: true }));
});

// The tax category list shows "code – explanation" while open and only the code once a value is chosen.
function showTaxCategoryText(select, full) {
  Array.prototype.forEach.call(select.options, function (option) {
    option.textContent = full ? option.getAttribute('data-full') : option.value;
  });
}

document.addEventListener('focusin', function (event) {
  if (event.target.matches('select.tax-category-select')) {
    showTaxCategoryText(event.target, true);
  }
});

document.addEventListener('focusout', function (event) {
  if (event.target.matches('select.tax-category-select')) {
    showTaxCategoryText(event.target, false);
  }
});

document.addEventListener('change', function (event) {
  if (event.target.matches('select.tax-category-select')) {
    event.target.title = event.target.selectedOptions[0].getAttribute('data-full');
    showTaxCategoryText(event.target, false);
  }
});

// A details element with data-remember keeps its open state in the browser. Storage can be blocked, then it stays as
// the page delivers it; a block the page forces open (data-forced, it holds values) is not closed by an old choice.
function rememberedDetails() {
  document.querySelectorAll('details[data-remember]').forEach(function (details) {
    var key = 'faktura.open.' + details.getAttribute('data-remember');

    try {
      if (!details.hasAttribute('data-forced') && window.localStorage.getItem(key) === '1') {
        details.open = true;
      }
    } catch (ignored) {
      // storage not available
    }

    details.addEventListener('toggle', function () {
      try {
        window.localStorage.setItem(key, details.open ? '1' : '0');
      } catch (ignored) {
        // storage not available
      }
    });
  });
}

rememberedDetails();

// A shipping option select copies name, amount, tax category and rate into the shipping fields and recalculates.
document.addEventListener('change', function (event) {
  var select = event.target.closest('select.shipping-option-select');

  if (!select) {
    return;
  }

  var option = select.selectedOptions[0];
  var values = {
    shippingName: option.getAttribute('data-name') || '',
    shippingAmount: option.value ? option.getAttribute('data-amount') : '',
    shippingTaxCategory: option.getAttribute('data-category') || '',
    shippingTaxRate: option.value ? option.getAttribute('data-rate') : ''
  };

  Object.keys(values).forEach(function (id) {
    document.getElementById(id).value = values[id];
  });
  document.getElementById('shippingAmount').dispatchEvent(new Event('input', { bubbles: true }));
});

// A text template select fills the text area named in data-fill-target with the body of the chosen template.
document.addEventListener('change', function (event) {
  var select = event.target.closest('select[data-fill-target]');

  if (!select || !select.value) {
    return;
  }

  var area = document.getElementById(select.getAttribute('data-fill-target'));

  area.value = select.selectedOptions[0].getAttribute('data-body');
  area.dispatchEvent(new Event('input', { bubbles: true }));
  select.value = '';
});

// Formatting toolbar of text areas: wraps the selection in markup or prefixes the selected lines.
document.addEventListener('click', function (event) {
  var button = event.target.closest('[data-format]');

  if (!button) {
    return;
  }

  var area = document.getElementById(button.closest('[data-format-target]').getAttribute('data-format-target'));
  var start = area.selectionStart;
  var end = area.selectionEnd;
  var text = area.value;
  var wraps = { bold: '**', italic: '*', underline: '__' };
  var kind = button.getAttribute('data-format');

  if (wraps[kind]) {
    var marker = wraps[kind];

    area.value = text.slice(0, start) + marker + text.slice(start, end) + marker + text.slice(end);
    area.setSelectionRange(start + marker.length, end + marker.length);
  } else {
    var lineStart = text.lastIndexOf('\n', start - 1) + 1;
    var lineEnd = text.indexOf('\n', end);
    lineEnd = lineEnd === -1 ? text.length : lineEnd;
    var lines = text.slice(lineStart, lineEnd).split('\n').map(function (line) {
      if (kind === 'list') {
        return /^\s*([-*•]|\d+[.)])\s/.test(line) ? line : '- ' + line;
      }

      return kind === 'indent' ? '  ' + line : line.replace(/^ {1,2}/, '');
    });

    area.value = text.slice(0, lineStart) + lines.join('\n') + text.slice(lineEnd);
    area.setSelectionRange(lineStart, lineStart + lines.join('\n').length);
  }

  area.focus();
  area.dispatchEvent(new Event('input', { bubbles: true }));
});

// Finalize dialog of the invoice editor: opens when htmx has loaded it, edits of its dates are copied to the form
// and reload the summary, and the finalize button is unlocked by the confirmation checkbox. While the summary is
// stale (a date was changed and the summary is not reloaded yet) the confirmation is reset and the button locked.
function updateFinalizeButton() {
  var dialog = document.getElementById('finalize-dialog');
  var submit = document.getElementById('rv-submit');
  var reviewed = document.getElementById('rv-reviewed');

  if (!dialog || !submit || !reviewed) {
    return;
  }

  submit.disabled = !reviewed.checked || dialog.querySelector('#review-summary[data-blocked]') !== null;
}

function lockFinalizeButton() {
  var reviewed = document.getElementById('rv-reviewed');

  if (reviewed) {
    reviewed.checked = false;
  }

  updateFinalizeButton();
}

// Copies the values of the dialog fields into the editor form, so what the dialog shows is what is saved and finalized.
function syncDialogFields() {
  Array.prototype.forEach.call(document.querySelectorAll('#finalize-dialog [data-sync]'), function (dialogField) {
    var original = document.getElementById(dialogField.getAttribute('data-sync'));

    if (original.value !== dialogField.value) {
      original.value = dialogField.value;
      original.dispatchEvent(new Event('change', { bubbles: true }));
      original.dispatchEvent(new Event('input', { bubbles: true }));
    }
  });
}

document.addEventListener('htmx:afterSwap', function (event) {
  var target = event.detail.target;

  if (target.id === 'finalize-dialog' && !target.open) {
    // The server may have preset the invoice date to today; the form must carry the same value.
    syncDialogFields();
  }

  if (target.id === 'finalize-dialog' || target.id === 'review-summary') {
    updateFinalizeButton();
  }

  // Dialogs filled by htmx (finalize, dunning, mail): open when content arrives, empty when closed. A closed dialog
  // keeps no content, so its confirmation checkbox never travels with a later submit of a form around it.
  if (target.matches && target.matches('dialog[data-htmx-dialog]')) {
    if (!target.open && target.innerHTML.trim() !== '') {
      target.showModal();
    }

    if (!target.hasAttribute('data-bound')) {
      target.setAttribute('data-bound', 'true');
      target.addEventListener('close', function () {
        target.innerHTML = '';
      });
    }
  }
});

// A mail is sent once: the send button locks as soon as the request starts (the response replaces the dialog).
document.addEventListener('htmx:beforeRequest', function (event) {
  if (event.target && event.target.id === 'mail-form') {
    var submit = document.getElementById('mail-submit');

    if (submit) {
      submit.disabled = true;
    }
  }
});

// A confirmation checkbox in a dialog unlocks the button of the dialog.
document.addEventListener('change', function (event) {
  // Generic: a checkbox with data-dialog-confirm=<id> unlocks the button with that id.
  var unlocks = event.target.getAttribute && event.target.getAttribute('data-dialog-confirm');

  if (unlocks) {
    document.getElementById(unlocks).disabled = !event.target.checked;
  }
});

function showReviewProblem() {
  var summary = document.getElementById('review-summary');

  if (summary) {
    summary.setAttribute('data-blocked', 'true');
    summary.textContent = summary.getAttribute('data-error-text') || '';
  }

  lockFinalizeButton();
}

document.addEventListener('htmx:responseError', function (event) {
  if (event.detail.target.id === 'review-summary') {
    showReviewProblem();
  } else if (event.detail.target.id === 'finalize-dialog') {
    showMessage(document.getElementById('finalize-open').getAttribute('data-error-text'));
  }
});

document.addEventListener('htmx:sendError', function (event) {
  if (event.detail.target.id === 'review-summary') {
    showReviewProblem();
  } else if (event.detail.target.id === 'finalize-dialog') {
    showMessage(document.getElementById('finalize-open').getAttribute('data-error-text'));
  }
});

document.addEventListener('click', function (event) {
  if (event.target.closest('[data-close-dialog]')) {
    var dialog = event.target.closest('dialog');

    dialog.close();
    dialog.innerHTML = '';
  }
});

// A dialog that opens from data-modal-open="<id>" and closes with data-modal-close; closing clears what was typed
// (passwords must not stay in the form).
document.addEventListener('click', function (event) {
  var opener = event.target.closest('[data-modal-open]');
  var closer = event.target.closest('[data-modal-close]');

  if (opener) {
    var dialog = document.getElementById(opener.getAttribute('data-modal-open'));
    var first = dialog.querySelector('input');

    dialog.showModal();

    if (first) {
      first.focus();
    }
  }

  if (closer) {
    closer.closest('dialog').close();
  }
});

document.addEventListener('close', function (event) {
  if (event.target.matches && event.target.matches('dialog[id^="password-dialog-"]')) {
    event.target.querySelector('form').reset();
  }
}, true);

var reviewTimer = null;

document.addEventListener('change', function (event) {
  if (event.target.id === 'rv-reviewed') {
    updateFinalizeButton();

    return;
  }

  var field = event.target.closest('#finalize-dialog [data-sync]');

  if (!field) {
    return;
  }

  var from = document.getElementById('rv-serviceFrom').value;
  var to = document.getElementById('rv-serviceTo').value;
  var hint = document.getElementById('rv-period-hint');
  var pending = (from === '') !== (to === '') || (from !== '' && to !== '' && from > to);

  lockFinalizeButton();
  hint.hidden = !pending;

  // A half-entered service period is not sent: the summary would only flash an error while the user is still typing.
  if (pending) {
    clearTimeout(reviewTimer);

    return;
  }

  syncDialogFields();
  clearTimeout(reviewTimer);
  reviewTimer = setTimeout(function () {
    htmx.ajax('POST', document.getElementById('finalize-dialog').getAttribute('data-review-url'),
      { source: document.getElementById('invoice-form'), target: '#review-summary', swap: 'outerHTML' });
  }, 400);
});

// Confirmations and messages use one dialog instead of window.confirm and window.alert. askConfirm calls onOk when
// the user confirms; showMessage only informs.
function openMessageDialog(text, withCancel, onOk) {
  var dialog = document.getElementById('message-dialog');
  var ok = document.getElementById('message-ok');

  document.getElementById('message-dialog-text').textContent = text;
  document.getElementById('message-cancel').hidden = !withCancel;
  ok.textContent = ok.getAttribute(withCancel ? 'data-ok-text' : 'data-close-text');
  dialog.onclose = function () {
    if (dialog.returnValue === 'ok' && onOk) {
      onOk();
    }
  };
  dialog.returnValue = '';
  dialog.showModal();
}

function askConfirm(text, onOk) {
  openMessageDialog(text, true, onOk);
}

function showMessage(text) {
  openMessageDialog(text, false, null);
}

// A form with data-confirm asks before it is submitted.
document.addEventListener('submit', function (event) {
  var form = event.target;
  var message = form.getAttribute && form.getAttribute('data-confirm');

  if (message && !form.hasAttribute('data-confirmed')) {
    event.preventDefault();
    askConfirm(message, function () {
      form.setAttribute('data-confirmed', 'true');
      form.requestSubmit();
    });
  }
});

// An element with hx-confirm asks in the same dialog before htmx sends the request.
document.addEventListener('htmx:confirm', function (event) {
  if (event.detail.question) {
    event.preventDefault();
    askConfirm(event.detail.question, function () {
      event.detail.issueRequest(true);
    });
  }
});

// A form with data-autosubmit is submitted as soon as one of its checkboxes or selects changes.
document.addEventListener('change', function (event) {
  var form = event.target.closest('form[data-autosubmit]');

  if (form && (event.target.type === 'checkbox' || event.target.tagName === 'SELECT')) {
    form.submit();
  }
});

// Keyboard in the catalog dialog: Enter adds the first result (and never submits the invoice form), the arrow keys
// move between the search field and the results. Escape closes the dialog natively.
document.addEventListener('keydown', function (event) {
  // Alt+K opens the catalog dialog of the page.
  if (event.altKey && event.code === 'KeyK' && !event.ctrlKey && !event.metaKey) {
    var opener = document.querySelector('[data-open-dialog]');

    if (opener && !document.querySelector('dialog[open]')) {
      event.preventDefault();
      opener.click();
    }

    return;
  }

  var search = event.target.closest('[data-catalog-search]');
  var result = event.target.closest('.catalog-result');
  var picker = event.target.closest('.catalog-dialog');

  if (!search && !result) {
    return;
  }

  var results = Array.prototype.slice.call(picker.querySelectorAll('.catalog-result'));

  // Shift+Enter adds and keeps the dialog open, Enter adds and closes it.
  if ((search || result) && event.key === 'Enter') {
    setKeepOpen(picker, event.shiftKey);
  }

  if (search && event.key === 'Enter') {
    event.preventDefault();

    if (search.value !== (search.dataset.searched || '')) {
      // The list does not show the result of the current text yet: search now and add the first hit when it arrives.
      search.dataset.pendingEnter = 'true';
      htmx.trigger(search, 'search');
    } else if (results.length > 0) {
      results[0].click();
    }
  } else if (search && event.key === 'ArrowDown' && results.length > 0) {
    event.preventDefault();
    results[0].focus();
  } else if (result && event.key === 'ArrowDown') {
    event.preventDefault();
    (results[results.indexOf(result) + 1] || result).focus();
  } else if (result && event.key === 'ArrowUp') {
    event.preventDefault();

    var index = results.indexOf(result);

    (index > 0 ? results[index - 1] : picker.querySelector('[data-catalog-search]')).focus();
  }
});

// After a catalog entry was added, the dialog closes and the focus returns to the button that opened it. With
// Shift it stays open, says what was added and is ready for the next search.
document.body.addEventListener('htmx:afterRequest', function (event) {
  var element = event.detail.elt;

  if (!element || !element.classList || !element.classList.contains('catalog-result')) {
    return;
  }

  var dialog = element.closest('dialog');

  // A failed request adds nothing: the dialog stays as it is.
  if (!event.detail.successful) {
    setKeepOpen(dialog, false);

    return;
  }

  if (dialog.dataset.keepOpen !== 'true') {
    dialog.close();

    return;
  }

  setKeepOpen(dialog, false);

  var name = element.querySelector('.catalog-name > span');
  var added = dialog.querySelector('#catalog-added');
  var search = dialog.querySelector('[data-catalog-search]');

  if (added && name) {
    added.textContent = dialog.getAttribute('data-added') + ' ' + name.textContent.trim();
  }

  search.focus();
  search.select();
});

// Every htmx request carries the CSRF token (htmx.ajax too); the layout puts it into meta tags.
document.body.addEventListener('htmx:configRequest', function (event) {
  var token = document.querySelector('meta[name="_csrf"]');
  var header = document.querySelector('meta[name="_csrf_header"]');

  if (token && header) {
    event.detail.headers[header.content] = token.content;
  }
});

// Typing in the catalog search must not recalculate the invoice form: it only searches. (A filter in hx-trigger would
// need eval, which the Content-Security-Policy forbids.)
document.body.addEventListener('htmx:beforeRequest', function (event) {
  var trigger = event.detail.requestConfig && event.detail.requestConfig.triggeringEvent;

  if (event.detail.elt.id === 'invoice-form' && trigger && trigger.target && trigger.target.hasAttribute
      && trigger.target.hasAttribute('data-catalog-search')) {
    event.preventDefault();
  }
});

// The catalog search remembers which text the list shows, so that Enter never adds a hit of an older search.
document.body.addEventListener('htmx:configRequest', function (event) {
  if (event.detail.elt && event.detail.elt.hasAttribute('data-catalog-search')) {
    event.detail.elt.dataset.requested = event.detail.elt.value;
  }
});

document.body.addEventListener('htmx:afterSwap', function (event) {
  var request = event.detail.requestConfig;
  var search = request && request.elt && request.elt.hasAttribute && request.elt.hasAttribute('data-catalog-search')
    ? request.elt
    : null;

  if (!search) {
    return;
  }

  search.dataset.searched = search.dataset.requested || '';
});

// Enter was pressed before the search result arrived: add the first hit now that the new list is in place.
document.body.addEventListener('htmx:afterSettle', function (event) {
  var request = event.detail.requestConfig;
  var search = request && request.elt && request.elt.hasAttribute && request.elt.hasAttribute('data-catalog-search')
    ? request.elt
    : null;

  if (!search || search.dataset.pendingEnter !== 'true') {
    return;
  }

  delete search.dataset.pendingEnter;

  var first = document.querySelector('#catalog-results .catalog-result');

  if (first) {
    first.click();
  }
});

// A button with data-copy="<id of an input>" copies the value of that input to the clipboard and tells the result in
// the element named by data-copy-status (texts in data-copied and data-copy-failed).
document.addEventListener('click', function (event) {
  var button = event.target.closest('[data-copy]');

  if (!button) {
    return;
  }

  var source = document.getElementById(button.getAttribute('data-copy'));
  var status = document.getElementById(button.getAttribute('data-copy-status'));
  var report = function (attribute) {
    if (status) {
      status.textContent = button.getAttribute(attribute);
    }
  };

  source.select();

  if (!navigator.clipboard) {
    report('data-copy-failed');

    return;
  }

  navigator.clipboard.writeText(source.value).then(function () {
    report('data-copied');
  }, function () {
    report('data-copy-failed');
  });
});

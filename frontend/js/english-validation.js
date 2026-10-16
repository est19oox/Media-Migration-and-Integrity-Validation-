'use strict';
let feedbackPending = false;
document.addEventListener(
  'invalid',
  (event) => {
    const input = event.target;
    if (!input.validity || !input.setCustomValidity) return;
    const state = input.validity;
    input.setCustomValidity(
      state.valueMissing
        ? 'Please complete this field.'
        : state.typeMismatch
          ? 'Enter a valid value.'
          : state.patternMismatch
            ? 'Use the requested format.'
            : state.tooShort
              ? 'This value is too short.'
              : state.tooLong
                ? 'This value is too long.'
                : 'Check this value and try again.',
    );
    if (globalThis.mmivFeedback && !feedbackPending) {
      feedbackPending = true;
      const message = input.validationMessage;
      setTimeout(() => {
        feedbackPending = false;
        mmivFeedback.notice('Check this form', message, 'error');
      }, 0);
    }
  },
  true,
);
document.addEventListener(
  'input',
  (event) => {
    if (event.target.setCustomValidity) event.target.setCustomValidity('');
  },
  true,
);

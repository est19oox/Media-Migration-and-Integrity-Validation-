'use strict';
document.addEventListener('invalid',event=>{
  const input=event.target;if(!input.validity||!input.setCustomValidity)return;
  const state=input.validity;
  input.setCustomValidity(state.valueMissing?'Please complete this field.':state.typeMismatch?'Enter a valid value.':state.patternMismatch?'Use the requested format.':state.tooShort?'This value is too short.':state.tooLong?'This value is too long.':'Check this value and try again.');
},true);
document.addEventListener('input',event=>{if(event.target.setCustomValidity)event.target.setCustomValidity('');},true);

// Server-side password policy — Feature #1
// Minimum 8 characters, at least 1 uppercase, 1 lowercase, 1 digit,
// 1 special character. The Android app enforces the same rules, but the
// server is the authority: never rely on client validation alone.

const SPECIAL = /[!@#$%^&*()_+\-=\[\]{};':"\\|,.<>\/?~`]/;

function validatePassword(pw) {
  const errors = [];
  if (typeof pw !== 'string' || pw.length < 8) errors.push('at least 8 characters');
  if (!/[A-Z]/.test(pw || ''))                 errors.push('1 uppercase letter');
  if (!/[a-z]/.test(pw || ''))                 errors.push('1 lowercase letter');
  if (!/[0-9]/.test(pw || ''))                 errors.push('1 number');
  if (!SPECIAL.test(pw || ''))                 errors.push('1 special character');
  if (/\s/.test(pw || ''))                     errors.push('no spaces');

  return {
    ok: errors.length === 0,
    errors,
    message: errors.length
      ? 'Password must contain ' + errors.join(', ') + '.'
      : null,
  };
}

module.exports = { validatePassword };

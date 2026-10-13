#!/usr/bin/env node
// Formats or checks only the files changed in a push range. Files are never passed through a
// shell, except the Maven launcher on Windows, where every argument is validated and quoted.
import { execFileSync } from 'node:child_process';
import { existsSync, lstatSync, mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const ZERO_SHA = '0'.repeat(40);
const SHA = /^[0-9a-f]{40}$/;
const SAFE_PATH = /^[A-Za-z0-9._@+/-]+$/;
const PRETTIER_EXT = new Set(['.html', '.css', '.js', '.json', '.md']);
const DENIED_SEGMENTS = new Set([
  '.git', '.ci', '.terraform', 'node_modules', 'target', 'build', 'dist', 'coverage',
  'generated', 'vendor', 'third_party', 'webjars',
]);
const DENIED_PREFIXES = [
  '.github/', 'infrastructure/ansible/', 'infrastructure/bootstrap/policies/', 'jenkins/',
  'security/', 'scripts/cd/', 'scripts/ci/',
];
const DENIED_NAME =
  /(^\.env(\..*)?$|\.(pem|key|p12|pfx|crt|tfstate|tfvars|tfplan)(\..*)?$|\.min\.js$|^(package-lock|npm-shrinkwrap)\.json$|^(yarn|pnpm)-lock\.ya?ml$)/;
const JAVA_FILE = /^services\/([A-Za-z0-9_-]+)\/src\/(main|test)\/java\/.+\.java$/;

function fail(message) {
  console.error(`::error::${message}`);
  process.exit(1);
}

function git(...args) {
  return execFileSync('git', args, { cwd: ROOT, encoding: 'utf8', maxBuffer: 1 << 28 });
}

function parseArguments(argv) {
  const options = { mode: null, before: null, after: null, report: null };
  for (let index = 0; index < argv.length; index++) {
    const argument = argv[index];
    if (argument === '--check' || argument === '--write') options.mode = argument.slice(2);
    else if (['--before', '--after', '--report'].includes(argument)) options[argument.slice(2)] = argv[++index];
    else fail(`Unknown argument: ${argument}`);
  }
  if (!options.mode) fail('Specify --check or --write.');
  if (!options.after || !SHA.test(options.after)) fail('--after must be a 40-character commit SHA.');
  if (!options.before || !(SHA.test(options.before) || options.before === ZERO_SHA)) {
    fail('--before must be a 40-character commit SHA.');
  }
  return options;
}

function commitExists(sha) {
  try {
    execFileSync('git', ['cat-file', '-e', `${sha}^{commit}`], { cwd: ROOT, stdio: 'ignore' });
    return true;
  } catch {
    return false;
  }
}

// Returns the exact base of the pushed range, or fails without formatting.
function resolveBase(before, after) {
  if (!commitExists(after)) fail(`Pushed commit ${after} is not available locally.`);
  if (before !== ZERO_SHA) {
    if (!commitExists(before)) {
      fail('Previous branch tip is unavailable (force-push or shallow clone); refusing to guess a range.');
    }
    return before;
  }
  for (const ref of ['refs/remotes/origin/main']) {
    try {
      const base = git('merge-base', ref, after).trim();
      if (SHA.test(base)) return base;
    } catch {
      // Try the next reference or fail below.
    }
  }
  return fail('New branch and origin/main merge-base could not be determined; refusing to format.');
}

function changedPaths(base, after) {
  const output = git('diff', '--name-status', '-z', '--no-renames', '--diff-filter=AM', base, after);
  const fields = output.split('\0');
  const paths = [];
  for (let index = 0; index + 1 < fields.length; index += 2) paths.push(fields[index + 1]);
  return paths;
}

function isSafePath(file) {
  return (
    SAFE_PATH.test(file) &&
    !file.startsWith('/') &&
    !file.startsWith('-') &&
    file.split('/').every((segment) => segment !== '' && segment !== '.' && segment !== '..')
  );
}

function isDenied(file) {
  const segments = file.split('/');
  return (
    segments.some((segment) => DENIED_SEGMENTS.has(segment)) ||
    DENIED_PREFIXES.some((prefix) => file.startsWith(prefix)) ||
    DENIED_NAME.test(segments.at(-1)) ||
    file.includes('/database/migrations/')
  );
}

function classify(paths) {
  const groups = { prettier: [], java: new Map(), terraform: [] };
  const skipped = [];
  for (const file of paths) {
    if (!isSafePath(file)) {
      skipped.push(`${JSON.stringify(file)} (unsafe path)`);
      continue;
    }
    const absolute = path.join(ROOT, file);
    if (!existsSync(absolute) || !lstatSync(absolute).isFile()) {
      skipped.push(`${file} (not a regular file)`);
      continue;
    }
    if (isDenied(file)) {
      skipped.push(`${file} (excluded path)`);
      continue;
    }
    const extension = path.extname(file).toLowerCase();
    const java = JAVA_FILE.exec(file);
    if (java) {
      if (!groups.java.has(java[1])) groups.java.set(java[1], []);
      groups.java.get(java[1]).push(file);
    } else if (PRETTIER_EXT.has(extension)) groups.prettier.push(file);
    else if (extension === '.tf' && file.startsWith('infrastructure/')) groups.terraform.push(file);
  }
  return { groups, skipped };
}

function run(command, args, options = {}) {
  console.log(`> ${command} ${args.join(' ')}`.slice(0, 300));
  execFileSync(command, args, { cwd: ROOT, stdio: 'inherit', ...options });
}

function runMaven(pom, extraArguments) {
  const args = ['--batch-mode', '--no-transfer-progress', '--file', pom, ...extraArguments];
  if (process.platform === 'win32') {
    const quoted = args.map((argument) => `"${argument}"`);
    execFileSync('mvn.cmd', quoted, { cwd: ROOT, stdio: 'inherit', shell: true });
  } else {
    run('mvn', args);
  }
}

function regexFor(files) {
  return files
    .map((file) => path.join(ROOT, file).replace(/[^A-Za-z0-9]/g, (character) => `\\${character}`))
    .join(',');
}

function runFormatters(mode, groups) {
  const apply = mode === 'write';
  const prettier = path.join(ROOT, 'node_modules', 'prettier', 'bin', 'prettier.cjs');
  if (groups.prettier.length > 0) {
    if (!existsSync(prettier)) fail("Pinned Prettier is missing. Run 'npm ci'.");
    run(process.execPath, [prettier, apply ? '--write' : '--check', '--', ...groups.prettier]);
  }
  for (const [service, files] of groups.java) {
    const pom = `services/${service}/pom.xml`;
    if (!existsSync(path.join(ROOT, pom))) fail(`Missing Maven project for changed Java file: ${pom}`);
    runMaven(pom, [`-DspotlessFiles=${regexFor(files)}`, apply ? 'spotless:apply' : 'spotless:check']);
  }
  if (groups.terraform.length > 0) {
    run('terraform', ['fmt', apply ? '-write=true' : '-check', '--', ...groups.terraform]);
  }
}

function main() {
  const options = parseArguments(process.argv.slice(2));
  const base = resolveBase(options.before, options.after);
  if (options.mode === 'write') {
    if (git('status', '--porcelain', '--untracked-files=no').trim() !== '') {
      fail('Working tree must be clean before formatting changed files.');
    }
    if (git('rev-parse', 'HEAD').trim() !== options.after) fail('Checked-out HEAD is not the pushed commit.');
  }

  const { groups, skipped } = classify(changedPaths(base, options.after));
  const eligible = [
    ...groups.prettier,
    ...[...groups.java.values()].flat(),
    ...groups.terraform,
  ].sort();
  console.log(`Changed range: ${base}..${options.after}`);
  console.log(`Eligible changed files: ${eligible.length}`);
  for (const file of eligible) console.log(`  ${file}`);
  for (const entry of skipped) console.log(`  skipped: ${entry}`);

  runFormatters(options.mode, groups);

  let formatted = [];
  if (options.mode === 'write' && eligible.length > 0) {
    const modified = git('diff', '--name-only', '-z').split('\0').filter(Boolean);
    const allowed = new Set(eligible);
    const unexpected = modified.filter((file) => !allowed.has(file));
    if (unexpected.length > 0) fail(`Formatters modified files outside the changed set: ${unexpected.join(', ')}`);
    formatted = modified.sort();
    if (formatted.length > 0) execFileSync('git', ['add', '--', ...formatted], { cwd: ROOT });
  }

  if (options.report) {
    mkdirSync(path.dirname(path.resolve(options.report)), { recursive: true });
    writeFileSync(
      options.report,
      JSON.stringify({ base, after: options.after, eligible, formatted, javaServices: [...groups.java.keys()].sort() }, null, 2),
    );
  }
  console.log(`Formatted files staged: ${formatted.length}`);
}

try {
  main();
} catch (error) {
  fail(`Formatting failed: ${error.status === undefined ? error.message : `command exited with ${error.status}`}`);
}

import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { runInNewContext } from 'node:vm';

const source = readFileSync(new URL('./index.ts', import.meta.url), 'utf8');
const start = source.indexOf('// Load .env file');
const end = source.indexOf('\nconst LISTEN_PORT', start);
assert.ok(start >= 0 && end > start, 'Locate the actual configuration-loading block');
const loader = source.slice(start, end);

for (const [name, content, initial, expected] of [
  ['LF lines', 'AUTH_TOKEN=synthetic\nLISTEN_PORT=9091\n', {}, { AUTH_TOKEN: 'synthetic', LISTEN_PORT: '9091' }],
  ['CRLF lines', 'AUTH_TOKEN=synthetic\r\nLISTEN_PORT=9091\r\n', {}, { AUTH_TOKEN: 'synthetic', LISTEN_PORT: '9091' }],
  ['embedded equals', 'PROMPT_TEMPLATE=key=val&flag=true\r\nAUTH_TOKEN=a=b=c\n', {}, { PROMPT_TEMPLATE: 'key=val&flag=true', AUTH_TOKEN: 'a=b=c' }],
  ['environment precedence', 'AUTH_TOKEN=file\r\nLISTEN_PORT=9091\r\n', { AUTH_TOKEN: 'existing' }, { AUTH_TOKEN: 'existing', LISTEN_PORT: '9091' }]
]) {
  test(name, () => {
    const env = { ...initial };
    runInNewContext(loader, {
      process: { env }, ENV_FILE: 'synthetic.env',
      existsSync: () => true, chmodSync: () => {}, readFileSync: () => content
    });
    assert.deepEqual(env, expected);
  });
}

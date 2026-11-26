import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { once } from 'node:events';
import { mkdtemp, rm } from 'node:fs/promises';
import { createServer, request } from 'node:http';
import { tmpdir } from 'node:os';
import { basename, join } from 'node:path';
import { setImmediate, setTimeout as delay } from 'node:timers/promises';
import { createExecRoutes } from '../bridge/dsh-go-bridge/lib/exec.js';

const path = '/dsh-go/v1/exec';
const proof = 'aa'.repeat(32);
const capability = { noTools: true, maxPhysicalRequests: 1 };
const finish = { type: 'finish', reason: { kind: 'stop' } };
const selection = { provider: 'locked', model: 'model' };

function fixture(t, options = {}) {
  const events = [], inputs = [], ids = [], workdirs = [];
  let attached = false, signal;
  let open;
  const gate = new Promise(resolve => { open = resolve; });
  const services = {
    sessions: {
      prepare(id, { meta }) {
        ids.push(id);
        workdirs.push(meta.cwd);
        return { id, append(type, data) { assert.equal(attached, true); events.push({ type, data }); } };
      },
      enter(session) { assert.equal(session.id, ids.at(-1)); attached = true; return () => { attached = false; }; },
      announce() { assert.equal(attached, true); },
      async flush() { assert.equal(attached, true); return options.flush !== false; },
    },
    agentDefaultModel: { currentSelection: () => selection },
    llm: {
      companionAuxiliaryCapability: () => options.capability ?? capability,
      async prepareCompanionAuxiliaryCall(config, s, observer) {
        signal = s;
        observer.guard();
        assert.deepEqual(config, { ...selection, maxTokens: 3000 });
        return { capability, async *stream(request) {
          assert.equal(attached, false);
          assert.deepEqual(Object.keys(request).sort(), ['input', 'system']);
          inputs.push(request);
          if (options.hold && !signal.aborted) await Promise.race([gate, once(signal, 'abort')]);
          yield { type: 'text-delta', text: 'final text' };
          if (options.tool) yield { type: 'block-start', blockType: 'tool-call' };
          yield finish;
        } };
      },
    },
  };
  if (options.workspaceRegistry) services.workspaceRegistry = options.workspaceRegistry;
  if (options.agent) {
    services.agents = { get: id => id === options.agent.session.id ? options.agent : undefined };
    services.sessions.flush = async () => options.flush !== false;
  }
  const digest = options.enabled === false ? undefined : createHash('sha256').update(proof).digest();
  const routes = createExecRoutes({ get(name) {
    assert.ok(Object.hasOwn(services, name), `unexpected service: ${name}`);
    return services[name];
  } }, {
    proofDigest: digest,
    workspaceRoot: options.workspaceRoot,
    readBody: async req => Buffer.from(JSON.stringify(req.body)),
    send: (res, status, data) => {
      if (typeof res.writeHead === 'function') {
        res.writeHead(status);
        // Simulate a lost reply after admission without aborting engine work.
        res.destroy();
      } else {
        res.status = status;
        res.data = structuredClone(data);
      }
    },
  });
  t.after(() => routes.dispose());
  async function call(suffix, body, overrides = {}) {
    const res = { setHeader(name, value) { assert.equal(name, 'Cache-Control'); assert.equal(value, 'no-store'); } };
    const handled = await routes.handle({
      method: 'POST', headers: { 'content-type': 'application/json', 'x-dsh-go-exec-proof': proof },
      socket: { remoteAddress: '127.0.0.1' }, body, ...overrides,
    }, res, path + suffix);
    return { handled, ...res };
  }
  async function waitTerminal(requestId) {
    for (let i = 0; i < 20; ++i) {
      const response = await call('/status', { requestId });
      if (['succeeded', 'failed', 'cancelled'].includes(response.data?.status)) return response.data;
      await setImmediate();
    }
    assert.fail('exec never settled');
  }
  return { routes, events, inputs, ids, workdirs, call, waitTerminal, open, loss: () => signal.aborted };
}

test('workspace preparation reuses the dedicated directory and groups text-only transcripts', async t => {
  const workspaceRoot = await mkdtemp(join(tmpdir(), 'dsh-exec-workspace-'));
  t.after(() => rm(workspaceRoot, { recursive: true, force: true }));
  const attached = [];
  const workspace = { id: 'remote-workspace', path: null, async attachSession(id) { attached.push(id); } };
  const f = fixture(t, {
    workspaceRoot,
    workspaceRegistry: {
      async create(path, title) {
        assert.equal(basename(path), 'dsh-exec');
        assert.equal(title, 'DSH 远控');
        if (workspace.path !== null) assert.equal(workspace.path, path);
        workspace.path = path;
        return workspace;
      },
      get(id) { return id === workspace.id ? workspace : undefined; },
    },
  });
  for (let i = 0; i < 2; i++) {
    const { status, data } = await f.call('/workspace', {});
    assert.equal(status, 200);
    assert.deepEqual(data, { workspaceId: workspace.id });
    const requestId = randomUUID();
    const start = await f.call('', { requestId, workspaceId: data.workspaceId, prompt: 'hello' });
    assert.equal(start.status, 202);
    assert.equal((await f.waitTerminal(requestId)).status, 'succeeded');
    assert.equal(attached.at(-1), start.data.sessionId);
  }
  assert.deepEqual(f.workdirs, [workspace.path, workspace.path]);
  assert.equal(new Set(attached).size, 2);
  assert.equal((await f.call('', { requestId: randomUUID(), workspaceId: 'other', prompt: 'text' })).status, 400);
});

function normalAgent({ approval = 'ask', hold = false, extraUser = false, reason = 'completed' } = {}) {
  const events = [];
  const session = {
    id: `dsh-go-exec-${randomUUID()}`,
    get seq() { return events.length; },
    eventAt: seq => events[seq],
  };
  let sandboxMode = 'danger-full-access', approvalPolicy = 'never', submissions = 0, cancellations = 0;
  const services = {
    permissionPresets: {
      resolve: () => ({ sandbox: 'workspace-write', approval }),
      set() { sandboxMode = 'workspace-write'; approvalPolicy = approval; },
    },
    sandboxPolicy: { resolve: () => ({ mode: sandboxMode }) },
    approval: { effectivePolicy: () => approvalPolicy },
  };
  return {
    session,
    status: 'idle',
    phase: { kind: 'idle' },
    inbox: { nextTurn: [], nextStep: [], claimed: [] },
    ctx: { get: name => services[name] },
    get submissions() { return submissions; },
    get cancellations() { return cancellations; },
    append: (type, data) => events.push({ type, data: { turn: 1, ...data } }),
    followup(message) {
      assert.equal(sandboxMode, 'workspace-write');
      assert.equal(approvalPolicy, 'ask');
      assert.equal(message.role, 'user');
      assert.match(message.id, /^[a-f0-9-]{36}$/);
      submissions++;
      events.push({ type: 'turn/start', data: { turn: 1 } }, { type: 'user/message', data: message });
      events.push({ type: 'user/message', data: { role: 'user', source: { kind: 'runtime-context' } } });
      if (extraUser) events.push({ type: 'user/message', data: { role: 'user', source: { kind: 'user' } } });
      if (!hold) events.push(
        { type: 'assistant/message', data: { turn: 1, message: { content: [{ type: 'text', text: 'session reply' }] } } },
        { type: 'turn/end', data: { turn: 1, reason: { kind: reason } } },
      );
    },
    cancel(_reason, options) { assert.equal(options.keepInbox, false); cancellations++; },
  };
}

test('session exec submits only after verifying and saving its restricted permissions', async t => {
  const agent = normalAgent();
  const f = fixture(t, { agent });
  const requestId = randomUUID();
  assert.equal((await f.call('', { requestId, sessionId: agent.session.id, prompt: 'Use tools if needed' })).status, 202);
  assert.deepEqual(await f.waitTerminal(requestId),
    { requestId, sessionId: agent.session.id, status: 'succeeded', text: 'session reply' });
  assert.equal(agent.submissions, 1);
  assert.equal(f.inputs.length, 0);
});

test('session continuation requires a matching prior request and an idle agent', async t => {
  const agent = normalAgent();
  const f = fixture(t, { agent });
  const requestId = randomUUID();
  const sessionId = agent.session.id;
  assert.equal((await f.call('', { requestId, sessionId, prompt: 'Start' })).status, 202);
  await f.waitTerminal(requestId);
  const resume = { sessionId, resumeRequestId: requestId, prompt: 'Correct the previous result' };
  assert.equal((await f.call('', { ...resume, requestId: randomUUID(), resumeRequestId: randomUUID() })).status, 409);
  agent.inbox.nextTurn.push({});
  const busyId = randomUUID();
  assert.equal((await f.call('', { ...resume, requestId: busyId })).status, 202);
  assert.equal((await f.waitTerminal(busyId)).errorCode, 'session_busy');
  agent.inbox.nextTurn.pop();
  const resumedId = randomUUID();
  assert.equal((await f.call('', { ...resume, requestId: resumedId, timeoutSeconds: 1800 })).status, 202);
  assert.equal((await f.waitTerminal(resumedId)).status, 'succeeded');
  assert.equal(agent.submissions, 2);
});

test('session exec reports only the category of the current tool until its result arrives', async t => {
  const agent = normalAgent({ hold: true });
  const f = fixture(t, { agent });
  const requestId = randomUUID();
  const sessionId = agent.session.id;
  async function activity(expected) {
    for (let i = 0; i < 30; i++) {
      const { data } = await f.call('/status', { requestId });
      assert.equal(JSON.stringify(data).includes('private-argument'), false);
      if (data.activeTool === expected) return;
      await delay(20);
    }
    assert.fail(`active tool never became ${expected}`);
  }
  await f.call('', { requestId, sessionId, prompt: 'Use a tool' });
  agent.append('tool/call', { callId: 'shell-call', name: 'bash', arguments: 'private-argument' });
  agent.append('tool/call', { callId: 'screen-call', name: 'mcp__phone__screen' });
  await activity('shell');
  agent.append('tool/result', { message: { toolCallId: 'shell-call' } });
  await activity('screen');
  agent.append('tool/result', { message: { toolCallId: 'screen-call' } });
  await activity(undefined);
});

test('session exec withholds mixed-user replies and reports genuine turn failures separately', async t => {
  for (const [agent, outcome] of [
    [normalAgent({ extraUser: true }), { errorCode: 'session-turn-mixed-input' }],
    [normalAgent({ reason: 'blocked' }), { errorCode: 'session-turn-failed', turnEndReason: 'blocked' }],
  ]) {
    const f = fixture(t, { agent });
    const requestId = randomUUID();
    const sessionId = agent.session.id;
    await f.call('', { requestId, sessionId, prompt: 'Respond once' });
    assert.deepEqual(await f.waitTerminal(requestId), { requestId, sessionId, status: 'failed', ...outcome });
  }
});

test('exec never submits if the negotiated preset auto-approves or permissions could not be saved', async t => {
  for (const [agent, flush] of [[normalAgent({ approval: 'never' }), true], [normalAgent(), false]]) {
    const f = fixture(t, { agent, flush });
    const requestId = randomUUID();
    await f.call('', { requestId, sessionId: agent.session.id, prompt: 'Do not dispatch' });
    assert.equal((await f.waitTerminal(requestId)).status, 'failed');
    assert.equal(agent.submissions, 0);
  }
});

test('revoking exec cancels an admitted session turn and removes its queued input', async t => {
  const agent = normalAgent({ hold: true });
  const f = fixture(t, { agent });
  const requestId = randomUUID();
  await f.call('', { requestId, sessionId: agent.session.id, prompt: 'Await cancellation' });
  assert.equal(agent.submissions, 1);
  await f.call('/cancel', { requestId });
  assert.equal((await f.waitTerminal(requestId)).status, 'cancelled');
  assert.equal(agent.cancellations, 1);
});

test('exec requires launch enablement, loopback and distinct proof for every route', async t => {
  const off = fixture(t, { enabled: false });
  const on = fixture(t);
  const requestId = randomUUID();
  assert.equal((await off.call('', { requestId, prompt: 'text' })).handled, false);
  for (const suffix of ['', '/status', '/cancel', '/workspace']) {
    assert.equal((await on.call(suffix, { requestId }, { socket: { remoteAddress: '192.0.2.1' } })).status, 403);
    assert.equal((await on.call(suffix, { requestId }, { headers: { authorization: `Bearer ${proof}` } })).status, 403);
  }
  assert.equal((await on.call('', { requestId, prompt: 'text', sessionId: 'reuse' })).status, 400);
  assert.equal((await on.call('', { requestId, prompt: 'text', tools: ['delegate'] })).status, 400);
  assert.equal(on.ids.length, 0);
});

test('fresh sessions dispatch one text-only prompt and publish only after model completion', async t => {
  const f = fixture(t, { hold: true });
  const requestId = randomUUID();
  const start = await f.call('', { requestId, prompt: 'Run the phone tool' });
  assert.equal(start.status, 202);
  assert.equal(start.data.status, 'running');
  assert.equal(f.events.length, 0);
  assert.equal((await f.call('', { requestId, prompt: 'replay' })).status, 409);
  assert.equal(f.loss(), false);
  f.open();
  const result = await f.waitTerminal(requestId);
  assert.deepEqual(result, { requestId, sessionId: start.data.sessionId, status: 'succeeded', text: 'final text' });
  assert.equal(f.inputs.length, 1);
  assert.equal(f.inputs[0].input, 'Run the phone tool');
  assert.deepEqual(f.events.map(event => event.type), ['turn/start', 'step/start', 'user/message', 'assistant/message', 'step/end', 'turn/end']);
  assert.equal((await f.call('', { requestId, prompt: 'replay' })).status, 409);
  const second = await f.call('', { requestId: randomUUID(), prompt: 'new' });
  assert.notEqual(second.data.sessionId, start.data.sessionId);
  await f.waitTerminal(second.data.requestId);
});

test('explicit cancel interrupts a detached run and persists cancellation without assistant output', async t => {
  const f = fixture(t, { hold: true });
  const requestId = randomUUID();
  await f.call('', { requestId, prompt: 'one' });
  assert.equal(f.loss(), false);
  assert.equal((await f.call('/cancel', { requestId })).data.status, 'cancelling');
  assert.equal(f.loss(), true);
  const result = await f.waitTerminal(requestId);
  assert.equal(result.status, 'cancelled');
  assert.equal(result.text, undefined);
  assert.equal(f.events.some(event => event.type === 'assistant/message'), false);
  assert.equal((await f.call('/cancel', { requestId })).data.status, 'cancelled');
});

test('unsolicited tool output fails closed and no ambiguous-retry provider is dispatched', async t => {
  const generic = fixture(t, { capability: { noTools: true, maxPhysicalRequests: null } });
  const requestId = randomUUID();
  assert.equal((await generic.call('', { requestId, prompt: 'one' })).status, 503);
  assert.equal(generic.inputs.length, 0);
  const f = fixture(t, { tool: true });
  await f.call('', { requestId, prompt: 'one' });
  const result = await f.waitTerminal(requestId);
  assert.equal(result.status, 'failed');
  assert.equal(result.text, undefined);
  assert.equal(f.events.some(event => event.type.startsWith('tool/')), false);
  assert.equal(f.inputs.length, 1);
});

test('losing the start HTTP reply detaches rather than cancelling', async t => {
  const f = fixture(t, { hold: true });
  const requestId = randomUUID();
  const server = createServer((req, res) => {
    req.body = { requestId, prompt: 'detach' };
    return f.routes.handle(req, res, path);
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(() => server.close());
  const client = request(`http://127.0.0.1:${server.address().port}${path}`, {
    method: 'POST', headers: { 'content-type': 'application/json', 'x-dsh-go-exec-proof': proof },
  });
  const lost = new Promise(resolve => client.once('error', resolve));
  client.end();
  assert.equal((await lost).code, 'ECONNRESET');
  assert.equal(f.loss(), false);
  f.open();
  assert.equal((await f.waitTerminal(requestId)).status, 'succeeded');
});

test('cancel arriving before start tombstones the ID and prevents dispatch', async t => {
  const f = fixture(t);
  const requestId = randomUUID();
  assert.equal((await f.call('/status', { requestId })).status, 404);
  const cancelled = { requestId, sessionId: null, status: 'cancelled' };
  assert.deepEqual((await f.call('/cancel', { requestId })).data, cancelled);
  assert.equal((await f.call('', { requestId, prompt: 'one' })).status, 409);
  assert.deepEqual((await f.call('/status', { requestId })).data, cancelled);
  assert.equal(f.ids.length, 0);
  assert.equal(f.inputs.length, 0);
});

test('transcript flush failure does not expose a successful result', async t => {
  const f = fixture(t, { flush: false });
  const requestId = randomUUID();
  await f.call('', { requestId, prompt: 'one' });
  const result = await f.waitTerminal(requestId);
  assert.equal(result.status, 'failed');
  assert.equal(result.errorCode, 'session-persistence-failed');
  assert.equal(result.text, undefined);
});

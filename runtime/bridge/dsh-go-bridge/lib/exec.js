import { createHash, randomUUID, timingSafeEqual } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';

const PATH = '/dsh-go/v1/exec';
const HEADER = 'x-dsh-go-exec-proof';
const HEX = /^[0-9a-f]{64}$/;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const LOOPBACK = new Set(['127.0.0.1', '::1', '::ffff:127.0.0.1']);
const digest = value => createHash('sha256').update(value).digest();
const MAX_BODY = 64 * 1024;
const MAX_TEXT = 64 * 1024;
const MAX_REQUESTS = 4096;
const RESULT_TTL = 10 * 60_000;
const SYSTEM = 'Respond to the prompt in text. No tools are available.';

// Native Android launch opt-in; Linux is reported both under PRoot and direct
// launch. Do not pass the secret to subsequent child processes. This is not
// protection against untrusted code that can inspect the engine process itself.
let launchProof = (() => {
  const proof = process.env.DSH_GO_EXEC_PROOF;
  delete process.env.DSH_GO_EXEC_PROOF;
  return process.platform === 'linux' && HEX.test(proof ?? '') ? digest(proof) : undefined;
})();

async function runApprovedSession(ctx, record, prompt, userMessage) {
  const { requestId, sessionId } = record.result;
  const localStop = record.abort.signal;
  const signal = AbortSignal.any([localStop, AbortSignal.timeout(5 * 60_000)]);
  let agent;
  let cancelFailed = false;
  const cancel = () => {
    try { agent.cancel({ kind: 'user' }, { keepInbox: false }); }
    catch { cancelFailed = true; }
  };
  let started = false;
  try {
    const sessions = ctx.get('sessions');
    agent = ctx.get('agents')?.get(sessionId);
    const session = agent?.session;
    const scope = agent?.loopCtx ?? agent?.ctx;
    const permissions = scope?.get('permissionPresets') ?? ctx.get('permissionPresets');
    const sandbox = scope?.get('sandboxPolicy');
    const approval = scope?.get('approval');
    const blank = () => !session.events.some(event =>
      event.type === 'user/message' || event.type === 'turn/start' || event.type === 'agent/inbox/spliced');
    const current = () => ctx.get('agents')?.get(sessionId) === agent && agent.session === session;
    if (typeof agent?.followup !== 'function' || typeof agent.cancel !== 'function' ||
        typeof sessions?.flush !== 'function' || !Array.isArray(session?.events) ||
        typeof permissions?.set !== 'function' || typeof sandbox?.resolve !== 'function' ||
        typeof approval?.effectivePolicy !== 'function' ||
        !blank())
      return { status: 'failed', errorCode: 'session-unavailable' };
    const message = await userMessage({
      content: [{ type: 'text', text: prompt }],
      source: { kind: 'user', rpcId: requestId },
    });
    signal.throwIfAborted();
    if (!current() || !blank())
      return { status: 'failed', errorCode: 'session-unavailable' };
    const spec = permissions.resolve('workspace-write');
    if (spec.sandbox !== 'workspace-write' || spec.approval !== 'ask')
      return { status: 'failed', errorCode: 'session-permission-unavailable' };
    permissions.set(session, 'workspace-write');
    if (await sessions.flush(session) !== true)
      return { status: 'failed', errorCode: 'session-persistence-failed' };
    signal.throwIfAborted();
    if (!current() ||
        sandbox.resolve({ session }).mode !== 'workspace-write' || approval.effectivePolicy(session) !== 'ask' ||
        !blank())
      return { status: 'failed', errorCode: 'session-permission-unavailable' };
    let cursor = session.events.length, turn, ours = false, other = false, text = '';
    signal.addEventListener('abort', cancel, { once: true });
    started = true;
    agent.followup(message);
    while (current()) {
      signal.throwIfAborted();
      const events = session.events;
      for (; cursor < events.length; cursor++) {
        const { type, data } = events[cursor];
        if (type === 'turn/start') turn = data.turn;
        if (type === 'user/message') {
          if (data.source?.rpcId === requestId) ours = true;
          else other = true;
        }
        if (type === 'assistant/message' && ours && !other && data.turn === turn) {
          const reply = data.message?.content?.filter(part => part.type === 'text').map(part => part.text).join('');
          text = reply && Buffer.byteLength(reply) <= MAX_TEXT ? reply : '';
        }
        if (type === 'turn/end' && ours && data.turn === turn) {
          signal.removeEventListener('abort', cancel);
          record.finalizing = true;
          if (await sessions.flush(session) !== true)
            return { status: 'failed', errorCode: 'session-persistence-failed' };
          if (data.reason?.kind === 'aborted') return { status: 'cancelled' };
          return data.reason?.kind === 'completed' && !other
            ? { status: 'succeeded', text } : { status: 'failed', errorCode: 'session-turn-failed' };
        }
        if (type === 'turn/end') { ours = false; other = false; text = ''; }
      }
      await delay(150, undefined, { signal });
    }
    return { status: 'failed', errorCode: 'session-unavailable' };
  } catch {
    if (started && !signal.aborted) cancel();
    if (cancelFailed) return { status: 'failed', errorCode: 'session-cancel-unconfirmed' };
    return localStop.aborted ? { status: 'cancelled' } :
      { status: 'failed', errorCode: signal.aborted ? 'model-timeout' : 'session-exec-failed' };
  } finally {
    signal.removeEventListener('abort', cancel);
  }
}

function appendTranscript(sessions, session, prompt, selection, result) {
  return (async () => {
    const detach = sessions.enter(session);
    try {
      sessions.announce(session);
      const content = text => [{ type: 'text', text }];
      const turn = 1;
      const step = 1;
      session.append('turn/start', { turn });
      session.append('step/start', { turn, step });
      session.append('user/message', {
        id: randomUUID(), role: 'user', source: { kind: 'dsh-go-exec' }, content: content(prompt),
      }, { surfaceOp: 'append' });
      if (result.status === 'succeeded') session.append('assistant/message', {
        turn, step, stream: [], message: {
          id: randomUUID(), role: 'assistant', content: content(result.text),
          source: { kind: 'model', provider: selection.provider, model: selection.model },
        },
      }, { surfaceOp: 'append' });
      session.append('step/end', { turn, step });
      const reason = result.status === 'succeeded' ? { kind: 'completed' }
        : result.status === 'cancelled' ? { kind: 'aborted', reason: { kind: 'user' } }
          : { kind: 'error', error: { code: 'UNKNOWN', message: result.errorCode } };
      session.append('turn/end', { turn, reason });
      if (await sessions.flush(session) !== true) throw new Error('exec transcript persistence failed');
    } finally {
      await detach();
    }
  })();
}

export function takeExecLaunchProof() {
  const proof = launchProof;
  launchProof = undefined;
  return proof;
}

const createSessionMessage = async spec => (await import('@deepseek-ai/dsh-llm')).createUserMessage(spec);

export function createExecRoutes(ctx, { readBody, send, proofDigest, userMessage = createSessionMessage }) {
  const records = new Map();
  let active, closed = false;
  const respond = (res, status, data) => {
    if (!res.destroyed) {
      res.setHeader('Cache-Control', 'no-store');
      send(res, status, data);
    }
  };
  const reject = (res, status, code) => respond(res, status, { error: { code, message: code } });
  async function execute(record, sessions, session, llm, selection, prompt) {
    const explicitSignal = record.abort.signal;
    const signal = AbortSignal.any([explicitSignal, AbortSignal.timeout(5 * 60_000)]);
    let text = '', bytes = 0, finish;
    let result;
    try {
      signal.throwIfAborted();
      const call = await llm.prepareCompanionAuxiliaryCall({
        provider: selection.provider, model: selection.model, maxTokens: 3000,
      }, signal, { guard: () => signal.throwIfAborted(), observe() {} });
      // No generic adapters: their transport retry behavior is not known.
      if (call.capability?.noTools !== true || call.capability.maxPhysicalRequests !== 1) throw new Error('unsupported provider');
      for await (const chunk of call.stream({ system: SYSTEM, input: prompt })) {
        signal.throwIfAborted();
        if (finish || chunk.type === 'tool-call-delta' || chunk.blockType === 'tool-call') throw new Error('invalid model output');
        if (chunk.type === 'text-delta' || chunk.type === 'reasoning-delta') {
          if (typeof chunk.text !== 'string') throw new Error('invalid model output');
          bytes += Buffer.byteLength(chunk.text);
          if (bytes > MAX_TEXT) throw new Error('model output limit');
          if (chunk.type === 'text-delta') text += chunk.text;
        }
        if (chunk.type === 'finish') finish = chunk.reason;
      }
      signal.throwIfAborted();
      if (finish?.kind !== 'stop' || !text.trim()) throw new Error('no complete text');
      result = { status: 'succeeded', text };
    } catch {
      result = explicitSignal.aborted ? { status: 'cancelled' }
        : { status: 'failed', errorCode: signal.aborted ? 'model-timeout' : 'model-failed' };
    }
    // Publish the actual DSH session only AFTER model work is over. This run
    // never opens an Agent, session controller, tool registry or delegate.
    record.finalizing = true;
    if (!closed) {
      try {
        await appendTranscript(sessions, session, prompt, selection, result);
      } catch {
        result = { status: 'failed', errorCode: 'session-persistence-failed' };
      }
    }
    Object.assign(record.result, result);
    const expiry = setTimeout(() => records.set(record.result.requestId, null), RESULT_TTL);
    expiry.unref();
    record.expiry = expiry;
  }
  return {
    async handle(req, res, path) {
      if (path !== PATH && path !== `${PATH}/status` && path !== `${PATH}/cancel`) return false;
      if (closed || !proofDigest) return false;
      const presented = req.headers[HEADER];
      if (!LOOPBACK.has(req.socket.remoteAddress) || typeof presented !== 'string' || !HEX.test(presented) ||
          !timingSafeEqual(digest(presented), proofDigest)) {
        reject(res, 403, 'native-proof-required');
      } else if (req.method !== 'POST') {
        reject(res, 405, 'method-not-allowed');
      } else if (String(req.headers['content-type']).split(';', 1)[0].trim().toLowerCase() !== 'application/json') {
        reject(res, 415, 'unsupported-media-type');
      } else {
        let body;
        try { body = JSON.parse((await readBody(req, MAX_BODY)).toString('utf8')); }
        catch (error) {
          reject(res, error?.status === 413 ? 413 : 400, error?.status === 413 ? 'too-large' : 'bad-request');
          return true;
        }
        // If the incoming body was lost before admission there is nothing to
        // detach. Once admitted, only explicit cancellation owns its signal.
        if (res.destroyed) return true;
        const start = path === PATH;
        if (!body || !UUID.test(typeof body.requestId === 'string' ? body.requestId : '') ||
            Object.keys(body).some(key => key !== 'requestId' && !(start && (key === 'prompt' || key === 'sessionId'))) ||
            start && body.sessionId !== undefined &&
              (typeof body.sessionId !== 'string' || !body.sessionId.startsWith('dsh-go-exec-') ||
                !UUID.test(body.sessionId.slice('dsh-go-exec-'.length))) ||
            start && (typeof body.prompt !== 'string' || !body.prompt.trim())) {
          reject(res, 400, 'bad-request');
        } else if (closed) {
          reject(res, 503, 'exec-unavailable');
        } else if (!start) {
          const record = records.get(body.requestId);
          const cancelling = path.endsWith('/cancel');
          if (record === undefined && cancelling) {
            // The cancel can arrive before the start has finished reading its body.
            // Reserve the ID so the delayed start cannot dispatch afterward.
            if (records.size >= MAX_REQUESTS) reject(res, 503, 'exec-request-capacity');
            else {
              const result = { requestId: body.requestId, sessionId: null, status: 'cancelled' };
              records.set(body.requestId, { result });
              respond(res, 200, result);
            }
          } else if (!record) reject(res, record === null ? 410 : 404, record === null ? 'result-expired' : 'request-not-found');
          else {
            if (cancelling && record.result.status === 'running' && !record.finalizing) {
              record.result.status = 'cancelling';
              record.abort.abort();
            }
            respond(res, 200, record.result);
          }
        } else if (records.has(body.requestId)) {
          reject(res, 409, 'request-already-used');
        } else if (active || records.size >= MAX_REQUESTS) {
          reject(res, 503, active ? 'exec-busy' : 'exec-request-capacity');
        } else {
          try {
            if (body.sessionId !== undefined) {
              const record = { abort: new AbortController(),
                result: { requestId: body.requestId, sessionId: body.sessionId, status: 'running' } };
              records.set(body.requestId, record);
              active = record;
              record.done = runApprovedSession(ctx, record, body.prompt, userMessage).then(result => {
                Object.assign(record.result, result);
                record.expiry = setTimeout(() => records.set(body.requestId, null), RESULT_TTL);
                record.expiry.unref();
              }).finally(() => { active = undefined; });
              respond(res, 202, record.result);
              return true;
            }
            const sessions = ctx.get('sessions');
            const llm = ctx.get('llm');
            const selection = ctx.get('agentDefaultModel')?.currentSelection();
            const capability = selection && llm?.companionAuxiliaryCapability?.(selection.provider);
            if (!selection?.provider || !selection.model || capability?.noTools !== true || capability.maxPhysicalRequests !== 1 ||
                typeof llm.prepareCompanionAuxiliaryCall !== 'function' ||
                ['prepare', 'enter', 'announce', 'flush'].some(method => typeof sessions?.[method] !== 'function')) {
              reject(res, 503, 'exec-unavailable');
              return true;
            }
            const sessionId = `dsh-go-exec-${randomUUID()}`;
            const session = sessions.prepare(sessionId, { meta: { cwd: process.cwd() } });
            const record = { abort: new AbortController(), result: { requestId: body.requestId, sessionId, status: 'running' } };
            records.set(body.requestId, record);
            active = record;
            record.done = execute(record, sessions, session, llm, { provider: selection.provider, model: selection.model }, body.prompt)
              .finally(() => { active = undefined; });
            respond(res, 202, record.result);
          } catch {
            reject(res, 503, 'exec-unavailable');
          }
        }
      }
      return true;
    },
    async dispose() {
      closed = true;
      active?.abort.abort();
      await active?.done;
      for (const record of records.values()) clearTimeout(record?.expiry);
      records.clear();
    },
  };
}

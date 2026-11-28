import { createHash, randomUUID, timingSafeEqual } from 'node:crypto';
import { mkdir, realpath } from 'node:fs/promises';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';

const PATH = '/dsh-go/v1/exec';
const WORKSPACE_PATH = PATH + '/workspace';
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

function toolActivity(name) {
  switch (name) {
    case 'bash': case 'pwsh': case 'shell': case 'mcp__phone__android_shell': return 'shell';
    case 'read': case 'read_file': return 'read';
    case 'write': case 'write_file': case 'edit': case 'edit_file': case 'str_replace_editor': return 'write';
    case 'glob': case 'grep': case 'web_search': return 'search';
    case 'delegate': case 'spawn_agent': return 'delegate';
    case 'mcp__phone__screen': case 'mcp__phone__collect': return 'screen';
    case 'mcp__phone__open': return 'open';
    case 'mcp__phone__tap': case 'mcp__phone__tap_xy': return 'tap';
    case 'mcp__phone__type': return 'input';
    case 'mcp__phone__scroll': return 'scroll';
    case 'mcp__phone__handoff': return 'handoff';
    default: return 'tool';
  }
}

// Native Android launch opt-in; Linux is reported both under PRoot and direct
// launch. Do not pass the secret to subsequent child processes. This is not
// protection against untrusted code that can inspect the engine process itself.
let launchProof = (() => {
  const proof = process.env.DSH_GO_EXEC_PROOF;
  delete process.env.DSH_GO_EXEC_PROOF;
  return process.platform === 'linux' && HEX.test(proof ?? '') ? digest(proof) : undefined;
})();

async function runApprovedSession(ctx, record, prompt) {
  const { requestId, sessionId } = record.result;
  const localStop = record.abort.signal;
  const signal = AbortSignal.any([localStop, AbortSignal.timeout(record.timeoutMs)]);
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
    const blank = () => {
      for (let seq = 0; seq < session.seq; seq++) {
        const type = session.eventAt(seq)?.type;
        if (type === 'user/message' || type === 'turn/start' || type === 'agent/inbox/spliced') return false;
      }
      return true;
    };
    const current = () => ctx.get('agents')?.get(sessionId) === agent && agent.session === session;
    const idle = () => agent.status === 'idle' && agent.phase?.kind === 'idle' &&
      agent.inbox?.nextTurn.length === 0 && agent.inbox.nextStep.length === 0 && agent.inbox.claimed.length === 0;
    if (typeof agent?.followup !== 'function' || typeof agent.cancel !== 'function' ||
        typeof sessions?.flush !== 'function' || typeof session?.eventAt !== 'function' ||
        !Number.isSafeInteger(session.seq) || session.seq < 0)
      return { status: 'failed', errorCode: 'session-unavailable' };
    signal.throwIfAborted();
    if (!current() || (!record.resume && !blank()))
      return { status: 'failed', errorCode: 'session-unavailable' };
    if (record.resume && !idle()) return { status: 'failed', errorCode: 'session_busy' };
    // The regular create-session RPC owns permission defaults. Followups keep
    // the session's existing policy and let the engine enforce it on each tool.
    if (!record.resume && await sessions.flush(session) !== true)
      return { status: 'failed', errorCode: 'session-persistence-failed' };
    signal.throwIfAborted();
    if (!current() || (!record.resume && !blank()))
      return { status: 'failed', errorCode: 'session-unavailable' };
    let cursor = session.seq, turn, ours = false, other = false, text = '';
    const activeTools = new Map();
    signal.addEventListener('abort', cancel, { once: true });
    started = true;
    agent.followup({
      id: randomUUID(), role: 'user', content: [{ type: 'text', text: prompt }],
      source: { kind: 'user', rpcId: requestId },
    });
    while (current()) {
      signal.throwIfAborted();
      for (; cursor < session.seq; cursor++) {
        const { type, data } = session.eventAt(cursor);
        if (type === 'turn/start') turn = data.turn;
        // The engine also logs its runtime context and notices as user-role messages.
        if (type === 'user/message' && data.source?.kind === 'user') {
          if (data.source?.rpcId === requestId) ours = true;
          else other = true;
        }
        if (ours && !other && data.turn === turn) {
          if (type === 'tool/call') {
            activeTools.set(data.callId, toolActivity(data.name));
            record.result.toolCalls = (record.result.toolCalls ?? 0) + 1;
          }
          if (type === 'tool/result') {
            const id = data.message?.toolCallId;
            if (data.message?.isError === true && activeTools.has(id))
              record.result.lastFailedTool = activeTools.get(id);
            activeTools.delete(id);
          }
        }
        if (type === 'assistant/message' && ours && !other && data.turn === turn) {
          const reply = data.message?.content?.filter(part => part.type === 'text').map(part => part.text).join('');
          text = reply && Buffer.byteLength(reply) <= MAX_TEXT ? reply : '';
        }
        if (type === 'turn/end' && ours && data.turn === turn) {
          delete record.result.activeTool;
          signal.removeEventListener('abort', cancel);
          record.finalizing = true;
          if (await sessions.flush(session) !== true)
            return { status: 'failed', errorCode: 'session-persistence-failed' };
          const reason = data.reason?.kind;
          if (reason === 'aborted') return { status: 'cancelled' };
          if (other) return { status: 'failed', errorCode: 'session-turn-mixed-input' };
          if (reason === 'completed') return { status: 'succeeded', text };
          const turnEndReason = ['blocked', 'error', 'max-tokens', 'interrupted', 'forked'].includes(reason)
            ? reason : 'unknown';
          return { status: 'failed', errorCode: 'session-turn-failed', turnEndReason };
        }
        if (type === 'turn/end') { ours = false; other = false; text = ''; }
      }
      const activeTool = ours && !other ? activeTools.values().next().value : undefined;
      if (activeTool === undefined) delete record.result.activeTool;
      else record.result.activeTool = activeTool;
      await delay(150, undefined, { signal });
    }
    return { status: 'failed', errorCode: 'session-unavailable' };
  } catch {
    if (started && !signal.aborted) cancel();
    if (cancelFailed) return { status: 'failed', errorCode: 'session-cancel-unconfirmed' };
    return localStop.aborted ? { status: 'cancelled' } :
      signal.aborted ? { status: 'timed_out', errorCode: 'model-timeout' } :
      { status: 'failed', errorCode: 'session-exec-failed' };
  } finally {
    delete record.result.activeTool;
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

export function createExecRoutes(ctx, { readBody, send, proofDigest, workspaceRoot = join(homedir(), 'workspace') }) {
  const records = new Map();
  let active, closed = false;
  let execWorkspaceId;
  async function ensureWorkspace() {
    const registry = ctx.get('workspaceRegistry');
    if (typeof registry?.create !== 'function') throw new Error('Workspace registry unavailable');
    await mkdir(workspaceRoot, { recursive: true });
    const directory = join(await realpath(workspaceRoot), 'dsh-exec');
    await mkdir(directory, { recursive: true });
    if (await realpath(directory) !== directory) throw new Error('Remote workspace directory moved');
    const workspace = await registry.create(directory, 'DSH 远控');
    if (typeof workspace?.id !== 'string' || workspace.path !== directory ||
        typeof workspace.attachSession !== 'function') throw new Error('Remote workspace unavailable');
    execWorkspaceId = workspace.id;
    return workspace.id;
  }
  const respond = (res, status, data) => {
    if (!res.destroyed) {
      res.setHeader('Cache-Control', 'no-store');
      send(res, status, data);
    }
  };
  const reject = (res, status, code) => respond(res, status, { error: { code, message: code } });
  async function execute(record, sessions, session, llm, selection, prompt, workspace) {
    const explicitSignal = record.abort.signal;
    const signal = AbortSignal.any([explicitSignal, AbortSignal.timeout(record.timeoutMs)]);
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
        : signal.aborted ? { status: 'timed_out', errorCode: 'model-timeout' }
        : { status: 'failed', errorCode: 'model-failed' };
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
      if (workspace && result.errorCode !== 'session-persistence-failed') {
        try { await workspace.attachSession(record.result.sessionId); }
        catch { result = { status: 'failed', errorCode: 'workspace-attach-failed' }; }
      }
    }
    Object.assign(record.result, result);
    const expiry = setTimeout(() => records.set(record.result.requestId, null), RESULT_TTL);
    expiry.unref();
    record.expiry = expiry;
  }
  return {
    async handle(req, res, path) {
      if (path !== PATH && path !== WORKSPACE_PATH && path !== `${PATH}/status` && path !== `${PATH}/cancel`) return false;
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
        if (path === WORKSPACE_PATH) {
          if (closed) reject(res, 503, 'exec-unavailable');
          else if (!body || typeof body !== 'object' || Array.isArray(body) || Object.keys(body).length !== 0)
            reject(res, 400, 'bad-request');
          else {
            try { respond(res, 200, { workspaceId: await ensureWorkspace() }); }
            catch { reject(res, 503, 'workspace-unavailable'); }
          }
          return true;
        }
        const start = path === PATH;
        if (!body || !UUID.test(typeof body.requestId === 'string' ? body.requestId : '') ||
            Object.keys(body).some(key => key !== 'requestId' &&
              !(start && ['prompt', 'sessionId', 'workspaceId', 'timeoutSeconds', 'resumeRequestId'].includes(key))) ||
            start && body.resumeRequestId !== undefined &&
              (typeof body.resumeRequestId !== 'string' || !UUID.test(body.resumeRequestId) || body.sessionId === undefined) ||
            start && body.timeoutSeconds !== undefined &&
              (!Number.isInteger(body.timeoutSeconds) || body.timeoutSeconds < 1 || body.timeoutSeconds > 3600) ||
            start && body.workspaceId !== undefined &&
              (typeof body.workspaceId !== 'string' || body.workspaceId !== execWorkspaceId || body.sessionId !== undefined) ||
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
            const timeoutMs = (body.timeoutSeconds ?? 900) * 1000;
            if (body.sessionId !== undefined) {
              const prior = records.get(body.resumeRequestId);
              if (body.resumeRequestId !== undefined &&
                  (!prior || prior.result.sessionId !== body.sessionId || !prior.done || !prior.expiry)) {
                reject(res, 409, 'resume-unavailable');
                return true;
              }
              const record = { abort: new AbortController(), timeoutMs, resume: prior !== undefined,
                result: { requestId: body.requestId, sessionId: body.sessionId, status: 'running' } };
              records.set(body.requestId, record);
              active = record;
              record.done = runApprovedSession(ctx, record, body.prompt).then(result => {
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
            const workspace = body.workspaceId === undefined ? undefined : ctx.get('workspaceRegistry')?.get(body.workspaceId);
            if (body.workspaceId !== undefined && !workspace) {
              reject(res, 503, 'workspace-unavailable');
              return true;
            }
            const sessionId = `dsh-go-exec-${randomUUID()}`;
            const session = sessions.prepare(sessionId, { meta: { cwd: workspace?.path ?? process.cwd() } });
            const record = { abort: new AbortController(), timeoutMs,
              result: { requestId: body.requestId, sessionId, status: 'running' } };
            records.set(body.requestId, record);
            active = record;
            record.done = execute(record, sessions, session, llm, { provider: selection.provider, model: selection.model }, body.prompt, workspace)
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

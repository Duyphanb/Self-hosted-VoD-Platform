type Listener = EventListenerOrEventListenerObject;

class FakeTarget {
  private readonly listeners = new Map<string, Set<Listener>>();

  addEventListener(type: string, listener: Listener | null): void {
    if (!listener) return;
    const registered = this.listeners.get(type) ?? new Set<Listener>();
    registered.add(listener);
    this.listeners.set(type, registered);
  }

  removeEventListener(type: string, listener: Listener | null): void {
    if (listener) this.listeners.get(type)?.delete(listener);
  }

  hasListeners(): boolean {
    return [...this.listeners.values()].some((listeners) => listeners.size > 0);
  }

  fire(type: string, init: ProgressEventInit = {}): void {
    const event = new ProgressEvent(type, init);
    for (const listener of [...(this.listeners.get(type) ?? [])]) {
      if (typeof listener === 'function') listener(event);
      else listener.handleEvent(event);
    }
  }
}

/** Controllable browser transport; never logs request bodies or headers. */
export class FakeXhr extends FakeTarget {
  static instances: FakeXhr[] = [];
  readonly upload = new FakeTarget();
  method = '';
  url = '';
  headers: Record<string, string> = {};
  body: unknown;
  status = 0;
  responseText = '';
  readyState = 0;
  timeout = 0;
  withCredentials = false;
  sendCount = 0;
  abortCount = 0;
  uploadListenersAtSend = false;
  deferAbort = false;
  private uploadComplete = false;
  private done = false;

  constructor() {
    super();
    FakeXhr.instances.push(this);
  }

  open(method: string, url: string): void {
    this.method = method;
    this.url = url;
    this.readyState = 1;
  }

  setRequestHeader(name: string, value: string): void {
    this.headers[name.toLowerCase()] = value;
  }

  send(body: unknown): void {
    this.sendCount += 1;
    this.body = body;
    this.uploadListenersAtSend = this.upload.hasListeners();
  }

  progress(loaded: number, total: number, lengthComputable = true): void {
    if (this.uploadListenersAtSend) this.upload.fire('progress', { loaded, total, lengthComputable });
  }

  finishBody(total: number, { load = true } = {}): void {
    this.progress(total, total);
    this.uploadComplete = true;
    if (load) this.uploadLoad(total);
  }

  uploadLoad(total: number): void {
    if (this.uploadListenersAtSend) {
      this.uploadComplete = true;
      this.upload.fire('load', { loaded: total, total, lengthComputable: true });
      this.upload.fire('loadend');
    }
  }

  respond(status: number, body = ''): void {
    this.status = status;
    this.responseText = body;
    this.readyState = 4;
    this.done = true;
    this.fire('readystatechange');
    this.fire('load');
    this.fire('loadend');
  }

  fail(kind: 'error' | 'timeout' | 'abort'): void {
    this.readyState = 4;
    this.done = true;
    this.status = 0;
    this.fire('readystatechange');
    if (!this.uploadComplete && this.uploadListenersAtSend) {
      this.upload.fire(kind);
      this.upload.fire('loadend');
    }
    this.fire(kind);
    this.fire('loadend');
  }

  abort(): void {
    this.abortCount += 1;
    if (this.sendCount > 0 && !this.done && !this.deferAbort) this.fail('abort');
  }
}

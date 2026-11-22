/**
 * A controllable XMLHttpRequest for the real hls.js XHR loader: tests stub the global constructor, let the
 * loader open and send it, then answer it. It never logs headers.
 */
export class FakeLoaderXhr {
  static instances: FakeLoaderXhr[] = [];

  readyState = 0;
  status = 0;
  statusText = '';
  response: unknown = null;
  responseText = '';
  responseType: XMLHttpRequestResponseType = '';
  responseURL = '';
  withCredentials = false;
  method = '';
  url = '';
  sent = false;
  aborted = false;
  readonly headers: Record<string, string> = {};
  onreadystatechange: (() => void) | null = null;
  onprogress: ((event: ProgressEvent) => void) | null = null;

  constructor() {
    FakeLoaderXhr.instances.push(this);
  }

  static reset(): void {
    FakeLoaderXhr.instances = [];
  }

  /** The requests that the loader actually sent, in order. */
  static sentRequests(): FakeLoaderXhr[] {
    return FakeLoaderXhr.instances.filter((xhr) => xhr.sent);
  }

  static latestSent(): FakeLoaderXhr {
    const latest = FakeLoaderXhr.sentRequests().at(-1);
    if (!latest) {
      throw new Error('No request was sent');
    }
    return latest;
  }

  open(method: string, url: string): void {
    this.method = method;
    this.url = url;
    this.readyState = 1;
  }

  setRequestHeader(name: string, value: string): void {
    this.headers[name.toLowerCase()] = value;
  }

  send(): void {
    this.sent = true;
  }

  abort(): void {
    this.aborted = true;
  }

  getAllResponseHeaders(): string {
    return '';
  }

  getResponseHeader(): string | null {
    return null;
  }

  /** Complete the request with `status` and a text body, as the browser does. */
  respond(status: number, body = ''): void {
    this.status = status;
    this.readyState = 4;
    this.responseURL = this.url;
    this.responseText = body;
    this.response = body;
    this.onreadystatechange?.();
  }
}

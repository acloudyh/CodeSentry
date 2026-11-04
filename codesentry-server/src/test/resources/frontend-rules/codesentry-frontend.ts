function riskyTs(target: Window, token: string): void {
  // ruleid: codesentry.frontend.postmessage-wildcard
  target.postMessage({ token }, "*");
  const redirect: string = window.location.search;
  // ruleid: codesentry.frontend.browser-input-redirect
  window.location.replace(redirect);
  // ruleid: codesentry.frontend.sensitive-browser-storage
  window.localStorage.setItem("refreshToken", token);
  // ruleid: codesentry.frontend.insecure-http-request
  axios.post("http://example.test/signin", { token });
}
function safeTs(target: Window): void {
  // ok: codesentry.frontend.postmessage-wildcard
  target.postMessage({ ready: true }, "https://example.test");
  // ok: codesentry.frontend.browser-input-redirect
  window.location.replace("/home");
  // ok: codesentry.frontend.sensitive-browser-storage
  window.localStorage.setItem("locale", "zh-CN");
  // ok: codesentry.frontend.insecure-http-request
  axios.get("https://example.test/api");
}

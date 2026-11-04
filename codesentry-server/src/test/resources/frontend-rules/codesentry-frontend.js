function risk(target, credential) {
  // ruleid: codesentry.frontend.postmessage-wildcard
  target.postMessage({ account: "demo" }, "*");
  // ruleid: codesentry.frontend.postmessage-wildcard
  target.postMessage({ account: "demo" }, { targetOrigin: "*" });
  // ruleid: codesentry.frontend.browser-input-redirect
  window.location.assign(window.location.hash.slice(1));
  // ruleid: codesentry.frontend.sensitive-browser-storage
  localStorage.setItem("access_token", credential);
  // ruleid: codesentry.frontend.insecure-http-request
  fetch("http://example.test/api", { method: "POST" });
}
function safe(target) {
  // ok: codesentry.frontend.postmessage-wildcard
  target.postMessage({ status: "ready" }, "https://trusted.example.test");
  // ok: codesentry.frontend.browser-input-redirect
  window.location.assign("/dashboard");
  // ok: codesentry.frontend.sensitive-browser-storage
  localStorage.setItem("theme", "blue");
  // ok: codesentry.frontend.insecure-http-request
  fetch("https://example.test/api");
  // ok: codesentry.frontend.insecure-http-request
  fetch("http://localhost:8080/api");
  // ok: codesentry.frontend.insecure-http-request
  fetch("http://127.0.0.1:8080/api");
  // ok: codesentry.frontend.insecure-http-request
  fetch("http://[::1]:8080/api");
}

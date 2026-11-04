export function RiskyPage({ target, token }) {
  function send() {
    // ruleid: codesentry.frontend.postmessage-wildcard
    target.postMessage({ token }, "*");
  }
  // ok: codesentry.frontend.sensitive-browser-storage
  localStorage.setItem("theme", "light");
  return <button onClick={send}>Send</button>;
}

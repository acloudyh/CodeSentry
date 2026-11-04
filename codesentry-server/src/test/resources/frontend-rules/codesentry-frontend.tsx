export function RiskyPanel({ token }: { token: string }) {
  // ruleid: codesentry.frontend.sensitive-browser-storage
  localStorage.setItem("authToken", token);
  // ok: codesentry.frontend.insecure-http-request
  fetch("https://example.test/profile");
  return <section>Profile</section>;
}

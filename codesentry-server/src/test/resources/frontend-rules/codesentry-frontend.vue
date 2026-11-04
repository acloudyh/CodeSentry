<template>
  <!-- ruleid: codesentry.frontend.vue-route-html -->
  <div v-html="$route.query.content"></div>
  <!-- ruleid: codesentry.frontend.vue-route-html -->
  <div v-html='route.params.html'></div>
  <!-- ok: codesentry.frontend.vue-route-html -->
  <div v-html="DOMPurify.sanitize($route.query.content)"></div>
  <!-- ok: codesentry.frontend.vue-route-html -->
  <div>{{ $route.query.content }}</div>
</template>
<script setup lang="ts">
function risky(target, token) {
  // ruleid: codesentry.frontend.vue-route-redirect
  window.location.assign(route.query.next);
  // ruleid: codesentry.frontend.vue-postmessage-wildcard
  target.postMessage({ token }, "*");
  // ruleid: codesentry.frontend.vue-sensitive-browser-storage
  localStorage.setItem("access_token", token);
}
function safe(target) {
  // ok: codesentry.frontend.vue-route-redirect
  window.location.assign("/dashboard");
  // ok: codesentry.frontend.vue-postmessage-wildcard
  target.postMessage({ status: "ready" }, "https://trusted.example.test");
  // ok: codesentry.frontend.vue-sensitive-browser-storage
  localStorage.setItem("theme", "blue");
}
</script>

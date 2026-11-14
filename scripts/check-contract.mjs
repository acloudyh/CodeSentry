#!/usr/bin/env node
import fs from "node:fs";
import { execFileSync } from "node:child_process";

const SPEC_PATH = process.env.SPEC_PATH || "openapi/openapi.json";
const BASE_REF = process.argv[2] || "origin/main";
const METHODS = ["get", "post", "put", "patch", "delete", "head", "options"];

const problems = [];
const notes = [];

function major(version) {
  const n = Number.parseInt(String(version || "0").split(".")[0], 10);
  return Number.isNaN(n) ? 0 : n;
}

function deref(doc, node, depth = 0) {
  if (!node || typeof node !== "object" || depth > 10) return node;
  if (typeof node.$ref === "string" && node.$ref.startsWith("#/")) {
    let current = doc;
    for (const key of node.$ref.slice(2).split("/")) current = current?.[key];
    return deref(doc, current, depth + 1);
  }
  return node;
}

function staticChecks(doc) {
  if (!/^3\.1\.\d+$/.test(String(doc.openapi || ""))) {
    problems.push("[API-02] openapi 字段必须为 3.1.x");
  }
  if (!doc.info?.version) problems.push("[API-09] info.version 缺失");
  if (!doc.components?.schemas?.Problem) {
    problems.push("[API-05] 缺少 components.schemas.Problem 统一错误结构");
  }

  const paths = doc.paths || {};
  if (Object.keys(paths).length === 0) problems.push("[API-02] paths 为空");

  for (const [url, item] of Object.entries(paths)) {
    if (!/^\/v\d+\//.test(url)) problems.push(`[API-03] 路径缺少 /vN 版本前缀: ${url}`);
    if (url.includes("_")) problems.push(`[API-03] 路径应使用 kebab-case，不得含下划线: ${url}`);
    if (url.endsWith("/") && url !== "/") problems.push(`[API-03] 路径不得以斜杠结尾: ${url}`);
    if (url.split("/").filter(Boolean).length > 4) {
      problems.push(`[API-03] 路径嵌套超过 2 层: ${url}`);
    }

    for (const [method, rawOp] of Object.entries(item)) {
      if (!METHODS.includes(method)) continue;
      const op = rawOp || {};
      const label = `${method.toUpperCase()} ${url}`;
      if (!op.operationId) problems.push(`[API-02] ${label} 缺少 operationId`);
      if (!op.summary && !op.description) notes.push(`${label} 建议补充 summary / description`);
      if (!op.responses || Object.keys(op.responses).length === 0) {
        problems.push(`[API-02] ${label} 缺少 responses`);
        continue;
      }

      if (Object.prototype.hasOwnProperty.call(op.responses, "201")) {
        const created = deref(doc, op.responses["201"]);
        if (!created?.headers?.Location) problems.push(`[API-07] ${label} 的 201 响应缺少 Location 头`);
      }
      if (Object.prototype.hasOwnProperty.call(op.responses, "204")) {
        const empty = deref(doc, op.responses["204"]);
        if (empty?.content) problems.push(`[API-07] ${label} 的 204 响应不得携带 body`);
      }

      for (const [code, rawResp] of Object.entries(op.responses)) {
        if (!/^[45]\d\d$/.test(code)) continue;
        const resp = deref(doc, rawResp);
        const schema = resp?.content?.["application/json"]?.schema;
        if (!schema) {
          problems.push(`[API-05] ${label} 的 ${code} 响应缺少 application/json 错误 schema`);
          continue;
        }
        const resolved = deref(doc, schema);
        if (resolved && resolved !== doc.components?.schemas?.Problem && !schema.$ref?.endsWith("/Problem")) {
          notes.push(`${label} 的 ${code} 响应建议统一引用 Problem schema`);
        }
        if (code === "429" && !resp?.headers?.["Retry-After"]) {
          problems.push(`[API-07] ${label} 的 429 响应缺少 Retry-After 头`);
        }
      }
    }
  }
}

function findBreakingChanges(base, head) {
  const breaking = [];
  const basePaths = base.paths || {};
  const headPaths = head.paths || {};

  for (const [url, item] of Object.entries(basePaths)) {
    if (!headPaths[url]) {
      breaking.push(`删除路径 ${url}`);
      continue;
    }
    for (const method of Object.keys(item)) {
      if (METHODS.includes(method) && !headPaths[url][method]) {
        breaking.push(`删除操作 ${method.toUpperCase()} ${url}`);
      }
    }
  }

  const baseSchemas = base.components?.schemas || {};
  const headSchemas = head.components?.schemas || {};
  for (const [name, schema] of Object.entries(baseSchemas)) {
    if (!headSchemas[name]) {
      breaking.push(`删除 schema ${name}`);
      continue;
    }
    const before = schema.properties || {};
    const after = headSchemas[name].properties || {};
    for (const [field, def] of Object.entries(before)) {
      if (!after[field]) {
        breaking.push(`${name}.${field} 被删除`);
        continue;
      }
      if (def.type && after[field].type && String(def.type) !== String(after[field].type)) {
        breaking.push(`${name}.${field} 类型变更 ${def.type} -> ${after[field].type}`);
      }
    }
    const beforeRequired = schema.required || [];
    for (const field of headSchemas[name].required || []) {
      if (!beforeRequired.includes(field)) breaking.push(`${name}.${field} 新增为必填`);
    }
  }
  return breaking;
}

let head;
try {
  head = JSON.parse(fs.readFileSync(SPEC_PATH, "utf8"));
} catch (error) {
  console.error(`[contract] FAIL 无法读取 ${SPEC_PATH}: ${error.message}`);
  process.exit(1);
}

staticChecks(head);

try {
  const raw = execFileSync("git", ["show", `${BASE_REF}:${SPEC_PATH}`], {
    encoding: "utf8",
    stdio: ["ignore", "pipe", "ignore"],
  });
  const base = JSON.parse(raw);
  const breaking = findBreakingChanges(base, head);
  const from = base.info?.version || "0.0.0";
  const to = head.info?.version || "0.0.0";
  if (breaking.length > 0 && major(to) <= major(from)) {
    problems.push(
      `[API-09] 检测到破坏性变更但主版本未提升 (${from} -> ${to}): ${breaking.join("; ")}`,
    );
  } else if (breaking.length > 0) {
    notes.push(`破坏性变更已升主版本 (${from} -> ${to}): ${breaking.join("; ")}`);
  } else {
    notes.push(`基线比较通过 (${BASE_REF}, ${from} -> ${to})，未发现破坏性变更`);
  }
} catch {
  notes.push(`未找到基线 ${BASE_REF}:${SPEC_PATH}，跳过破坏性变更比较（仅执行静态校验）`);
}

if (problems.length > 0) {
  for (const item of problems) console.error(`[contract] FAIL ${item}`);
  for (const item of notes) console.log(`[contract] NOTE ${item}`);
  console.error(`[contract] ${problems.length} 项不合规，禁止合并。`);
  process.exit(1);
}

console.log("[contract] OK 契约静态校验通过。");
for (const item of notes) console.log(`[contract] NOTE ${item}`);

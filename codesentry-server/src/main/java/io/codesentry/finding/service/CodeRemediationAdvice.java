package io.codesentry.finding.service;

import io.codesentry.finding.model.FindingView;
import java.util.Locale;
import java.util.Set;

/** 按规则提供本地可执行的代码整改步骤；不推断漏洞已可利用，也不调用模型。 */
public final class CodeRemediationAdvice {
  private static final Set<String> PLACEHOLDERS =
      Set.of(
          "请结合代码上下文复核并按安全编码规范整改。",
          "按阿里巴巴 Java 开发规范调整代码，并结合项目兼容性进行人工确认。",
          "检查该字节码问题的数据来源和调用链；安全类问题优先升级依赖或修复自研实现。");

  private CodeRemediationAdvice() {}

  /** 保留已有明确建议；扫描器未提供建议时，按精确规则线索及 CWE 选择处理与验证步骤。 */
  public static String forFinding(FindingView finding) {
    String original = finding.getRecommendation() == null ? "" : finding.getRecommendation().trim();
    if (!original.isEmpty() && !PLACEHOLDERS.contains(original)) return original;
    String rule = (finding.getRuleId() == null ? "" : finding.getRuleId()).toLowerCase(Locale.ROOT);
    String cwe = finding.getCwe() == null ? "" : finding.getCwe();
    if (rule.contains("non-literal-regexp") || "CWE-1333".equals(cwe))
      return "处理：检查 new RegExp 或正则构造器的模式来源。固定用途改用常量正则；搜索字面文本时优先使用 includes/indexOf，必须拼入正则时先转义正则元字符，并限制输入长度。确需开放正则语法时使用复杂度受限的实现或隔离执行，避免嵌套量词造成阻塞。\n验证：覆盖正常匹配、特殊字符及长输入，确认不会把用户文本作为任意正则执行，也不会阻塞请求；修改后复扫。";
    if (rule.contains("detected-jwt") || "CWE-798".equals(cwe))
      return "处理：确认命中的 JWT/凭据是否仍有效。有效则在签发端撤销或使其失效，从源码、静态资源和发布包移除，改为登录后动态取得短期令牌；签名密钥仅放在服务端密钥存储。前端不能通过环境变量打包来隐藏密钥。\n验证：旧令牌无法再访问接口，未登录与越权请求被拒绝，新登录流程正常，重新构建后复扫发布产物。示例值或已失效数据由人工核实后标注。";
    if (rule.contains("permissive-cors") || "CWE-942".equals(cwe))
      return "处理：将允许的 Origin 改为明确的协议、域名和端口白名单，不回显任意 Origin；仅开放业务需要的方法与请求头。需要 Cookie 等凭据时仅允许可信来源，禁止通配来源，并与网关 CORS 配置保持一致。\n验证：用可信和非可信 Origin 分别测试预检与实际请求，非可信来源不能读取响应；涉及会话的接口同时检查认证和 CSRF 防护，修改后复扫。";
    if (rule.contains("prototype-pollution") || "CWE-1321".equals(cwe))
      return "处理：对动态赋值、递归合并及路径设置的键使用业务字段白名单，拒绝 __proto__、prototype、constructor 等危险路径；只遍历对象自有属性，字典可用 Object.create(null) 或 Map。避免将外部对象直接深合并到共享配置，检查相关合并库的修复版本。\n验证：用含嵌套危险键的输入测试，确认 Object.prototype 和其他对象属性未变化，正常配置合并仍可用，修改后复扫。";
    if (rule.contains("eval") || "CWE-95".equals(cwe))
      return "处理：追踪 eval、Function 及字符串形式定时器的输入来源，改为普通函数调用、显式分支或白名单函数映射；解析数据使用 JSON.parse 后进行结构校验，禁止把外部文本当作代码执行。\n验证：外部输入不能触发任意表达式执行；覆盖原有业务分支并复扫。构建工具生成的代码需回到对应源文件或构建配置修正。";
    if (rule.contains("plaintext-http") || rule.contains("insecure-websocket"))
      return "处理：将业务 HTTP/WS 连接改为 HTTPS/WSS，并配置有效证书；核查生产环境地址及代理协议，禁止向明文端点发送登录信息、令牌或业务敏感数据。仅说明文档中的示例链接需人工区分。\n验证：浏览器请求使用加密协议，无混合内容或证书错误，连接与断线重连正常，修改环境配置后重新构建并复扫。";
    if (rule.contains("sql-injection") || "CWE-89".equals(cwe))
      return "处理：将拼接 SQL 改为参数化查询或预编译语句；排序字段、表名等不能绑定的标识符使用固定白名单映射。禁止将用户输入直接拼接到 SQL，并限制数据库账号权限。\n验证：含引号和 SQL 语法的输入只能作为数据处理，正常查询与分页保持可用，完成接口回归后复扫。";
    if (rule.contains("xss") || "CWE-79".equals(cwe))
      return "处理：将不可信数据以文本节点或框架默认转义方式输出，移除直接写入 HTML 的路径；确需富文本时采用有明确允许列表的 HTML 净化器，并按 HTML、属性、URL 等上下文分别处理。\n验证：含标签、事件属性和危险 URL 的输入不会执行脚本，正常展示可用，修改后复扫。";
    return "处理：定位本条规则所列文件与调用点，追踪输入来源和敏感操作，对外部输入增加类型、长度及业务允许列表校验，并在操作前检查身份和资源权限；安全配置改为显式值并移除无用入口。\n验证：增加覆盖该调用点的正常、边界与未授权输入用例，完成回归后按原规则复扫。当前规则尚无专用修复模板，需人工确定具体改动，不能仅凭扫描命中认定可利用。";
  }
}

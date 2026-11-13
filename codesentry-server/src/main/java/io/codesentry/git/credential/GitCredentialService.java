package io.codesentry.git.credential;

import io.codesentry.ai.secret.AiToolSecretCipher;
import io.codesentry.common.error.BusinessException;
import io.codesentry.project.model.ScanProject;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import java.net.URI;
import java.util.*;

/** 集中维护按 Git 服务地址限定的 HTTP 账号；仅在执行 Git 前解密，列表与仓库响应不含秘密。 */
@Service
public class GitCredentialService {
    private final JdbcTemplate db;
    private final AiToolSecretCipher cipher;
    /** 注入本地数据库与持久化主密钥，复用既有 AES-GCM 密钥管理。 */
    public GitCredentialService(JdbcTemplate db,AiToolSecretCipher cipher) { this.db=db;this.cipher=cipher; }

    /** 仅返回可选账号元数据、引用数和更新时间，永不返回密码或密文。 */
    public List<Map<String,Object>> list() {
        return db.queryForList("SELECT c.id,c.name,c.server_url serverUrl,c.username,c.updated_at updatedAt,(SELECT COUNT(*) FROM scan_project p WHERE p.enabled=1 AND p.git_credential_id=c.id) usageCount FROM git_credential c ORDER BY c.id DESC");
    }

    /** 新增/更新账号；编辑时密码留空保持原值，地址被引用时不能更换。 */
    @Transactional
    public Map<String,Object> save(Long id,GitCredentialRequest request) {
        String server=origin(request.serverUrl);
        if(id==null) {
            if(!StringUtils.hasText(request.password)) throw bad("请填写 Git 密码或访问令牌");
            db.update("INSERT INTO git_credential(name,server_url,username,password_ciphertext,created_at,updated_at) VALUES(?,?,?,?,NOW(3),NOW(3))",request.name.trim(),server,request.username.trim(),cipher.encrypt(request.password));
            id=db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        } else {
            Map<String,Object> old=require(id,true);
            if(!server.equals(old.get("server_url")) && references(id)>0) throw conflict("该账号已被仓库引用，请先解除引用再修改 Git 服务地址");
            String encrypted=StringUtils.hasText(request.password)?cipher.encrypt(request.password):(String)old.get("password_ciphertext");
            db.update("UPDATE git_credential SET name=?,server_url=?,username=?,password_ciphertext=?,updated_at=NOW(3) WHERE id=?",request.name.trim(),server,request.username.trim(),encrypted,id);
        }
        Long saved=id;
        return list().stream().filter(row->((Number)row.get("id")).longValue()==saved).findFirst().orElseThrow();
    }

    /** 删除前锁定配置并核对有效仓库引用；停用资产不再保留无法使用的账号关联。 */
    @Transactional
    public void delete(Long id) {
        require(id,true);
        if(references(id)>0) throw conflict("该账号仍被仓库引用，请先在仓库编辑中更换认证配置");
        db.update("UPDATE scan_project SET git_credential_id=NULL WHERE git_credential_id=?",id);
        db.update("DELETE FROM git_credential WHERE id=?",id);
    }

    /** 仓库保存时锁定所选配置，串行化账号删除，并校验仓库与账号属于同一服务地址。 */
    public void checkSelection(Long id,String remote) { scope(require(id,true),remote); }

    /** 执行前读取最新账号，轮换密码无需逐个编辑仓库；只解密到临时内存对象。 */
    public ScanProject resolve(Long id,String remote) {
        Map<String,Object> row=require(id,false);scope(row,remote);
        ScanProject result=new ScanProject();result.setGitUsername((String)row.get("username"));
        result.setGitPasswordCiphertext((String)row.get("password_ciphertext"));return result;
    }

    /** 对 HTTP(S) 的协议、主机、端口做精确匹配，禁止向其他地址发送已维护的账密。 */
    private void scope(Map<String,Object> row,String remote) {
        if(!origin(remote).equals(row.get("server_url"))) throw bad("所选 Git 账号不适用于该仓库地址，请选择相同 Git 服务的账号");
    }

    /** 规范化服务地址；不接收地址中的用户名、密码或令牌，也不关闭 TLS 证书校验。 */
    public static String origin(String value) {
        try {
            URI u=URI.create(value.trim());String scheme=u.getScheme()==null?"":u.getScheme().toLowerCase(Locale.ROOT);
            if(!Set.of("http","https").contains(scheme) || u.getHost()==null || u.getUserInfo()!=null || u.getRawQuery()!=null || u.getRawFragment()!=null) throw new IllegalArgumentException();
            int port=u.getPort();if(port==(scheme.equals("https")?443:80)) port=-1;
            return new URI(scheme,null,u.getHost().toLowerCase(Locale.ROOT),port,null,null,null).toString();
        } catch(Exception error) { throw bad("请填写不包含账密的 HTTP/HTTPS Git 服务地址"); }
    }
    /** 内部读取完整配置；仅变更和引用建立需要行锁，秘密不能作为响应返回。 */
    private Map<String,Object> require(Long id,boolean lock) {
        List<Map<String,Object>> rows=db.queryForList("SELECT * FROM git_credential WHERE id=?"+(lock?" FOR UPDATE":""),id);
        if(rows.isEmpty()) throw new BusinessException(HttpStatus.NOT_FOUND,"Git 账号配置不存在，请刷新后重新选择");return rows.get(0);
    }
    /** 统计仍可扫描的仓库引用，作为删除和更换服务地址的约束。 */
    private long references(Long id) { return db.queryForObject("SELECT COUNT(*) FROM scan_project WHERE enabled=1 AND git_credential_id=?",Long.class,id); }
    private static BusinessException bad(String text) { return new BusinessException(HttpStatus.BAD_REQUEST,text); }
    private static BusinessException conflict(String text) { return new BusinessException(HttpStatus.CONFLICT,text); }
}

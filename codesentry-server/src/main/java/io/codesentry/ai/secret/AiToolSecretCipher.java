package io.codesentry.ai.secret;

import io.codesentry.config.CodeSentryProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;

@Component
public class AiToolSecretCipher {
    private static final String VERSION = "v1";
    private final Path keyFile;
    private final SecureRandom random = new SecureRandom();

    public AiToolSecretCipher(CodeSentryProperties properties) {
        this.keyFile = properties.getStorage().rootPath().resolve("secrets/ai-tools-master.key");
    }

    public synchronized String encrypt(String plainText) {
        try {
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
            return VERSION + "." + Base64.getEncoder().encodeToString(nonce) + "."
                    + Base64.getEncoder().encodeToString(cipher.doFinal(plainText.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法加密工具密钥", exception);
        }
    }

    public synchronized String decrypt(String encrypted) {
        try {
            String[] parts = encrypted.split("\\.", 3);
            if (parts.length != 3 || !VERSION.equals(parts[0])) throw new IllegalStateException("工具密钥格式无效");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.getDecoder().decode(parts[1])));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[2])), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("无法解密工具密钥，请确认密钥文件未被删除", exception);
        }
    }

    private SecretKey key() throws Exception {
        Files.createDirectories(keyFile.getParent());
        if (!Files.exists(keyFile)) {
            KeyGenerator generator = KeyGenerator.getInstance("AES"); generator.init(256);
            byte[] encoded = generator.generateKey().getEncoded();
            Files.writeString(keyFile, Base64.getEncoder().encodeToString(encoded),
                    java.nio.file.StandardOpenOption.CREATE_NEW);
            try {
                Files.setPosixFilePermissions(keyFile, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) { }
        }
        byte[] encoded = Base64.getDecoder().decode(Files.readString(keyFile).trim());
        return new SecretKeySpec(encoded, "AES");
    }
}

package com.example.campusmarket.testsupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Objects;

/**
 * 测试 JVM 启动时一次性生成的 2048 位 RSA 临时密钥。
 *
 * <p>密钥只存在于进程内存；{@link #writePemPair(Path)} 仅在需要时把密钥写入调用方指定的临时目录。
 * 仓库内不保存密钥材料，生产代码也无法引用本类（本类只存在于 tests classifier 的测试 JAR 中）。
 */
public final class TestRsaKeys {

    private static final int KEY_SIZE = 2048;
    private static final String PEM_LABEL_PRIVATE = "PRIVATE KEY";
    private static final String PEM_LABEL_PUBLIC = "PUBLIC KEY";
    private static final String ARMOR_BEGIN = "-----BEGIN ";
    private static final String ARMOR_END = "-----END ";
    private static final String ARMOR_SUFFIX = "-----";

    private static final KeyPair KEY_PAIR = generate();

    private TestRsaKeys() {
    }

    /** 返回仅供测试签名使用的临时 RSA 私钥。 */
    public static RSAPrivateKey privateKey() {
        return (RSAPrivateKey) KEY_PAIR.getPrivate();
    }

    /** 返回与临时私钥匹配的 RSA 公钥。 */
    public static RSAPublicKey publicKey() {
        return (RSAPublicKey) KEY_PAIR.getPublic();
    }

    /**
     * 将当前临时密钥对写成 PEM（私钥 PKCS#8、公钥 X.509）。
     *
     * @param directory 目标目录，不能为空；目录不存在时创建
     */
    public static void writePemPair(Path directory) {
        Objects.requireNonNull(directory, "directory");
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("test-private-key.pem"),
                    pem(privateKey().getEncoded(), PEM_LABEL_PRIVATE), StandardCharsets.US_ASCII);
            Files.writeString(directory.resolve("test-public-key.pem"),
                    pem(publicKey().getEncoded(), PEM_LABEL_PUBLIC), StandardCharsets.US_ASCII);
        } catch (IOException ex) {
            throw new IllegalStateException("无法写入测试密钥到临时目录", ex);
        }
    }

    private static String pem(byte[] der, String label) {
        String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
        return ARMOR_BEGIN + label + ARMOR_SUFFIX + "\n"
                + body + "\n"
                + ARMOR_END + label + ARMOR_SUFFIX + "\n";
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(KEY_SIZE, new SecureRandom());
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("当前 JVM 不支持 RSA", ex);
        }
    }
}

package com.tradepass.module.contract.service.payment;

import javax.crypto.*;
import javax.crypto.spec.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAKey;
import java.security.spec.*;
import java.security.cert.CertificateFactory;
import java.time.Clock;
import java.util.*;

/** WeChat Pay v3 RSA authentication and authenticated notification decryption. */
public final class WechatPayCrypto {
    private WechatPayCrypto() { }
    public static PrivateKey privateKey(String pem) {
        try {
            PrivateKey key=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decodePem(pem,"PRIVATE KEY")));
            requireRsa(key); return key;
        } catch(Exception e) { throw new IllegalArgumentException("商户私钥必须为有效RSA PKCS#8 PEM"); }
    }
    public static PublicKey publicKey(String pem) {
        try {
            PublicKey key=pem.contains("BEGIN CERTIFICATE")
                    ? CertificateFactory.getInstance("X.509").generateCertificate(
                        new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8))).getPublicKey()
                    : KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decodePem(pem,"PUBLIC KEY")));
            requireRsa(key); return key;
        } catch(Exception e) { throw new IllegalArgumentException("微信验签材料必须为有效RSA公钥或平台证书PEM"); }
    }
    private static void requireRsa(Key key) {
        if (!(key instanceof RSAKey rsa) || rsa.getModulus().bitLength()<2048) throw new IllegalArgumentException("RSA密钥位数不足");
    }
    private static byte[] decodePem(String value,String type) {
        if (!value.contains("-----BEGIN "+type+"-----")) throw new IllegalArgumentException("PEM类型无效");
        return Base64.getDecoder().decode(value.replace("-----BEGIN "+type+"-----","")
                .replace("-----END "+type+"-----","").replaceAll("\\s",""));
    }
    public static String sign(String message,String privatePem) {
        try {
            Signature signer=Signature.getInstance("SHA256withRSA"); signer.initSign(privateKey(privatePem));
            signer.update(message.getBytes(StandardCharsets.UTF_8)); return Base64.getEncoder().encodeToString(signer.sign());
        } catch(Exception e) { throw new IllegalArgumentException("支付签名失败"); }
    }
    public static void verify(Map<String,String> headers,String body,WechatPaySettings settings,Clock clock) {
        try {
            String serial=header(headers,"Wechatpay-Serial"),timestamp=header(headers,"Wechatpay-Timestamp");
            String nonce=header(headers,"Wechatpay-Nonce"),signature=header(headers,"Wechatpay-Signature");
            if (!serial.equals(settings.verificationKeyId()) || !timestamp.matches("[0-9]{1,16}")
                    || nonce.isBlank() || nonce.length()>128 || nonce.contains("\n") || nonce.contains("\r")
                    || signature.startsWith("WECHATPAY/SIGNTEST/"))
                throw new IllegalArgumentException();
            long time=Long.parseLong(timestamp),now=clock.instant().getEpochSecond();
            if (time<now-300 || time>now+300) throw new IllegalArgumentException();
            Signature verifier=Signature.getInstance("SHA256withRSA"); verifier.initVerify(publicKey(settings.verificationKeyPem()));
            verifier.update((timestamp+"\n"+nonce+"\n"+body+"\n").getBytes(StandardCharsets.UTF_8));
            if (!verifier.verify(Base64.getDecoder().decode(signature))) throw new IllegalArgumentException();
        } catch(Exception e) { throw new IllegalArgumentException("微信支付签名验证失败"); }
    }
    public static String decrypt(String ciphertext,String nonce,String associatedData,String key) {
        try {
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"AES"),
                    new GCMParameterSpec(128,nonce.getBytes(StandardCharsets.UTF_8)));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Base64.getDecoder().decode(ciphertext)),StandardCharsets.UTF_8);
        } catch(Exception e) { throw new IllegalArgumentException("微信支付通知解密失败"); }
    }
    private static String header(Map<String,String> headers,String name) {
        return headers.entrySet().stream().filter(e->e.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue).findFirst().orElse("");
    }
}

package com.ufi_axis_widget.util.source

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.ufi_axis_widget.util.DebugLogger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * UFI-AXIS 的设备身份密钥（Android Keystore 里的 EC P-256 密钥对）。
 *
 * core 从 2026-08-28 起把「设备身份」定义为一对不可导出的密钥：
 * 配对时上报公钥（SPKI DER base64），指纹由 core 自己算，客户端自报的指纹一律不采信；
 * 之后每个 `/api` 请求都要用私钥对 `METHOD\nURI\nTS\nNONCE` 签名。
 * 所以这把密钥必须留在 Keystore 里 —— 导得出来的私钥等于没有设备身份。
 *
 * 全应用共用一把：core 侧一个指纹代表「这台手机」，按配置档各生成一把只会把
 * 设备的已配对列表刷满，还会撞上 `pairing_max_devices`。
 */
object UfiAxisDeviceKey {

    private const val TAG = "UfiAxisDeviceKey"
    private const val ALIAS = "ufi_axis_device_key"
    private const val PROVIDER = "AndroidKeyStore"

    private val random = SecureRandom()

    /** 公钥的 X.509 SPKI DER base64，作为 `device_pubkey` 上报 */
    fun publicKeySpkiBase64(): String? = runCatching {
        val pub = entry()?.certificate?.publicKey ?: return null
        Base64.encodeToString(pub.encoded, Base64.NO_WRAP)
    }.getOrElse {
        DebugLogger.logApiErr(TAG, "取公钥失败: ${it.message}")
        null
    }

    /** 用设备私钥对 [canonical] 原文做 ECDSA-SHA256 签名，返回 DER 的 base64 */
    fun sign(canonical: String): String? = runCatching {
        val privateKey = entry()?.privateKey ?: return null
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(canonical.toByteArray(Charsets.UTF_8))
            sign()
        }
        Base64.encodeToString(der, Base64.NO_WRAP)
    }.getOrElse {
        DebugLogger.logApiErr(TAG, "签名失败: ${it.message}")
        null
    }

    /** 每请求一次性 nonce，core 在 ±5 分钟窗口内去重 */
    fun newNonce(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    }

    /** 丢弃当前密钥。core 侧删除了本机记录时用得上：下次配对就是一台全新设备 */
    fun reset() {
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    /**
     * 密钥生成互斥锁。
     *
     * [entry] 是「查不到就生成」，配对流程取公钥与每请求签名分别在不同线程调用它。
     * 首次使用时两条链路同时进来会各生成一次密钥对、后者覆盖前者，
     * 于是上报给 core 的公钥和实际签名用的私钥对不上，请求一律被判无效。
     */
    private val keyLock = Any()

    private fun entry(): KeyStore.PrivateKeyEntry? = synchronized(keyLock) {
        val ks = keyStore()
        (ks.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry)?.let { return@synchronized it }
        generate()
        keyStore().getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry
    }

    private fun generate() {
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply {
            initialize(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    // 不设 setUserAuthenticationRequired：小组件在锁屏下也要采集
                    .build()
            )
        }.generateKeyPair()
        DebugLogger.logSys(TAG, "已生成 UFI-AXIS 设备密钥")
    }
}

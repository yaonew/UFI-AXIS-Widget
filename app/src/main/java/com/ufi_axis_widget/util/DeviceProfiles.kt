package com.ufi_axis_widget.util

import android.content.Context
import android.content.SharedPreferences
import com.ufi_axis_widget.util.source.GoformDataSource
import com.ufi_axis_widget.widget.BaseWifiWidget
import com.ufi_axis_widget.worker.WifiWorker

/**
 * 多设备配置档。
 *
 * 设计要点：
 * - 档 id 是字符串，默认档固定为 [DEFAULT_ID]，当前档记在**全局**键 `active_profile`
 * - 只有「属于设备」的键才按档隔离（见 [SCOPED_KEYS]），主题/刷新间隔/通知阈值等
 *   跨设备共享的键一律不动
 * - **默认档不加前缀**：老版本升级上来的数据天然就是默认档的数据，
 *   不做任何迁移，零风险。和 `WidgetPrefs` 的回退套路一致
 *
 * 隔离通过 [ProfileScopedPreferences] 这层键名映射实现，因此 `SPUtil` 里
 * 上百处 `getSp(ctx).getXxx("裸键")` 一处都不用改 —— 只要它们都走 [SPUtil.getSp]。
 */
object DeviceProfiles {

    const val DEFAULT_ID = "default"

    private const val TAG = "DeviceProfiles"
    private const val PREFS_NAME = "wifi_data"
    private const val KEY_ACTIVE = "active_profile"
    private const val KEY_IDS = "profile_ids"

    /**
     * 属于配置档的键（每台设备一份）。
     *
     * 判断标准是「换一台设备这个值就必须跟着换」：地址、认证、API 路径、
     * 设备名、热点白名单、套餐额度与账期。
     */
    private val SCOPED_KEYS = setOf(
        "data_source",
        "device_address", "device_protocol", "goform_port",
        // 地址与协议按数据源分槽（UFI-TOOLS 沿用无后缀的老键），换设备时三套都得跟着换
        "device_address_goform", "device_protocol_goform",
        "device_address_ufi_axis", "device_protocol_ufi_axis",
        "raw_token", "auth_token", "goform_password", "secret_key",
        // UFI-AXIS：token 是 core 按「这台设备 × 本机密钥」发的，换设备必须换
        "ufi_axis_port", "ufi_axis_token", "ufi_axis_fingerprint", "ufi_axis_pair_password",
        "at_command_path", "device_info_path", "goform_command_path",
        "need_token_path", "version_info_path",
        "device_display_name",
        "wifi_lock_enabled", "wifi_lock_ssids",
        "traffic_quota_bytes", "traffic_billing_day"
    )

    /** 前缀匹配的配置档键：单日结清累加器是一组动态键名 */
    private val SCOPED_PREFIXES = listOf("traffic_derive_acc")

    fun isScoped(key: String): Boolean =
        key in SCOPED_KEYS || SCOPED_PREFIXES.any { key.startsWith(it) }

    /** 存档后的真实键名。默认档与全局键都原样返回 */
    fun storedKey(profileId: String, key: String): String =
        if (profileId == DEFAULT_ID || !isScoped(key)) key else "p_${profileId}_$key"

    // ── 底层 prefs（不带任何映射，只有本类和 SPUtil.getSp 该用它）──

    private fun rawSp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile private var cachedId: String? = null
    @Volatile private var cachedPrefs: SharedPreferences? = null

    /**
     * 当前档视角下的 prefs，[SPUtil.getSp] 的实现。
     *
     * 默认档直接返回裸 prefs：绝大多数用户只有一台设备，不该为一个可选功能
     * 给每次 SP 读写都套一层包装。
     */
    fun prefs(ctx: Context): SharedPreferences {
        val base = rawSp(ctx)
        val id = base.getString(KEY_ACTIVE, DEFAULT_ID) ?: DEFAULT_ID
        if (id == DEFAULT_ID) return base
        cachedPrefs?.let { if (cachedId == id) return it }
        val wrapped = ProfileScopedPreferences(base, id)
        cachedId = id
        cachedPrefs = wrapped
        return wrapped
    }

    // ── 档位增删查改 ──

    fun activeId(ctx: Context): String =
        rawSp(ctx).getString(KEY_ACTIVE, DEFAULT_ID) ?: DEFAULT_ID

    /** 全部档 id，默认档永远排在第一位 */
    fun ids(ctx: Context): List<String> {
        val extra = rawSp(ctx).getString(KEY_IDS, "").orEmpty()
            .split(',').map { it.trim() }.filter { it.isNotEmpty() && it != DEFAULT_ID }
        return listOf(DEFAULT_ID) + extra.distinct()
    }

    fun displayName(ctx: Context, id: String): String {
        val custom = rawSp(ctx).getString("profile_name_$id", "").orEmpty()
        if (custom.isNotEmpty()) return custom
        return if (id == DEFAULT_ID) "默认设备" else "设备 $id"
    }

    fun rename(ctx: Context, id: String, name: String) {
        rawSp(ctx).edit().putString("profile_name_$id", name.trim()).apply()
    }

    /**
     * 新建一个档。
     *
     * @param copyFromActive true 时把当前档的设备配置整份复制过去（改地址就能用），
     *   false 时新档从空开始，各项走默认值
     * @return 新档 id
     */
    fun create(ctx: Context, name: String, copyFromActive: Boolean): String {
        val existing = ids(ctx)
        var index = existing.size
        var id: String
        do {
            id = "p$index"
            index++
        } while (id in existing)

        val base = rawSp(ctx)
        base.edit()
            .putString(KEY_IDS, (existing.filter { it != DEFAULT_ID } + id).joinToString(","))
            .putString("profile_name_$id", name.trim())
            .apply()
        if (copyFromActive) copyScoped(ctx, activeId(ctx), id)
        return id
    }

    /**
     * 删除档。默认档不允许删除；删的正好是当前档时自动切回默认档。
     */
    fun delete(ctx: Context, id: String) {
        if (id == DEFAULT_ID) return
        val base = rawSp(ctx)
        val editor = base.edit()
        val prefix = "p_${id}_"
        for (key in base.all.keys) {
            if (key.startsWith(prefix)) editor.remove(key)
        }
        editor.remove("profile_name_$id")
        editor.putString(
            KEY_IDS,
            ids(ctx).filter { it != DEFAULT_ID && it != id }.joinToString(",")
        )
        editor.apply()
        if (activeId(ctx) == id) activate(ctx, DEFAULT_ID)
    }

    /**
     * 切换当前档，并做完整清理。
     *
     * 这五步漏一条就会串数据 —— 顺序也有讲究：先写 active 再清理，
     * 清理里写的键才会落到**新档**上。
     */
    fun activate(ctx: Context, id: String) {
        rawSp(ctx).edit().putString(KEY_ACTIVE, id).apply()
        cachedId = null
        cachedPrefs = null

        // 1. 旧设备的 session cookie 必须丢弃，否则新设备会拿着别人的凭据请求
        GoformDataSource.invalidateSession()
        // 2. 失败状态属于「上一台设备离线」，不能带到新设备上
        SPUtil.setReconnecting(ctx, false)
        SPUtil.resetWorkerFailureState(ctx)
        // 3. 新档的月累计基线一定是过期的，不清会把差值算成一个巨大的跳变
        TrafficRecordManager.resetDeriveAccumulator(ctx)
        // 4. 展示值与设备身份缓存都属于上一台设备
        SPUtil.clearDeviceRuntimeCache(ctx)
        BaseWifiWidget.renderAllWidgets(ctx, force = true)
        // 5. 数据源可能变了，重排周期任务（间隔是全局的，UPDATE 即可）
        WifiWorker.schedulePeriodic(ctx, SPUtil.getRefreshInterval(ctx), keepExisting = false)

        DebugLogger.logSys(TAG, "切换配置档 → $id (${displayName(ctx, id)})")
    }

    /**
     * 某个档的一行概要：数据源 + 地址，供配置档列表展示。
     *
     * 直接读底层 prefs 并自己拼前缀 —— 这是唯一需要「跨档读值」的场合，
     * 走 [prefs] 只能看到当前档。
     */
    fun summaryOf(ctx: Context, id: String): String {
        val base = rawSp(ctx)
        val sourceId = base.getString(storedKey(id, "data_source"), "").orEmpty()
        val source = DataSourceType.entries.firstOrNull { it.id == sourceId }
        val sourceName = source?.displayName ?: "未设置数据源"
        // 地址按源分槽，这里也得按该档自己的源去取，否则显示的是别的源的地址
        val suffix = when (source) {
            DataSourceType.GOFORM -> "_goform"
            DataSourceType.UFI_AXIS -> "_ufi_axis"
            else -> ""
        }
        val address = base.getString(storedKey(id, "device_address$suffix"), null)
            ?.takeIf { it.isNotBlank() }
            ?: base.getString(storedKey(id, "device_address"), "").orEmpty()
        return if (address.isEmpty()) "$sourceName · 未填地址" else "$sourceName · $address"
    }

    /**
     * 切到下一个参与循环的档（小组件三击用）。
     *
     * 循环名单来自 [SPUtil.getWidgetCycleProfiles]，为空或有效项不足 2 个时
     * 回退成「全部档」—— 这样新建档后不用回设置页勾一遍就能直接参与循环。
     *
     * @return 切换后的档 id；可循环的档不足 2 个时返回 null（不做任何改动）
     */
    fun cycleNext(ctx: Context): String? {
        val all = ids(ctx)
        val picked = SPUtil.getWidgetCycleProfiles(ctx).filter { it in all }
        val list = if (picked.size >= 2) all.filter { it in picked } else all
        if (list.size < 2) return null
        // 当前档不在名单里时 indexOf 返回 -1，(−1+1)%n = 0 → 从名单第一个开始
        val next = list[(list.indexOf(activeId(ctx)) + 1) % list.size]
        activate(ctx, next)
        return next
    }

    /** 把 [from] 档的配置档键整份复制到 [to] 档 */
    private fun copyScoped(ctx: Context, from: String, to: String) {
        val base = rawSp(ctx)
        val editor = base.edit()
        for ((spKey, value) in base.all) {
            val bare = bareKeyOf(spKey, from) ?: continue
            val target = storedKey(to, bare)
            when (value) {
                is String -> editor.putString(target, value)
                is Int -> editor.putInt(target, value)
                is Long -> editor.putLong(target, value)
                is Float -> editor.putFloat(target, value)
                is Boolean -> editor.putBoolean(target, value)
                is Set<*> -> editor.putStringSet(target, value.filterIsInstance<String>().toSet())
            }
        }
        editor.apply()
    }

    /** 存档键 → 裸键；不属于 [id] 档的配置键返回 null */
    private fun bareKeyOf(storedKey: String, id: String): String? {
        if (id == DEFAULT_ID) return storedKey.takeIf { isScoped(it) }
        val prefix = "p_${id}_"
        if (!storedKey.startsWith(prefix)) return null
        return storedKey.removePrefix(prefix).takeIf { isScoped(it) }
    }
}

/**
 * 键名映射层：把配置档键读写重定向到 `p_<id>_` 前缀，全局键原样透传。
 *
 * 只在非默认档下才会被套上（见 [DeviceProfiles.prefs]）。
 * `getAll` 故意返回底层全量（含所有档的键）—— 本项目里没有按它做业务判断的地方，
 * 强行过滤反而会让调试时看不到真实存储状态。
 */
private class ProfileScopedPreferences(
    private val base: SharedPreferences,
    private val profileId: String
) : SharedPreferences {

    private fun k(key: String) = DeviceProfiles.storedKey(profileId, key)

    override fun getAll(): MutableMap<String, *> = base.all
    override fun getString(key: String, defValue: String?): String? = base.getString(k(key), defValue)
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        base.getStringSet(k(key), defValues)
    override fun getInt(key: String, defValue: Int): Int = base.getInt(k(key), defValue)
    override fun getLong(key: String, defValue: Long): Long = base.getLong(k(key), defValue)
    override fun getFloat(key: String, defValue: Float): Float = base.getFloat(k(key), defValue)
    override fun getBoolean(key: String, defValue: Boolean): Boolean = base.getBoolean(k(key), defValue)
    override fun contains(key: String): Boolean = base.contains(k(key))
    override fun edit(): SharedPreferences.Editor = ScopedEditor(base.edit())

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) = base.registerOnSharedPreferenceChangeListener(listener)

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) = base.unregisterOnSharedPreferenceChangeListener(listener)

    private inner class ScopedEditor(
        private val delegate: SharedPreferences.Editor
    ) : SharedPreferences.Editor {
        override fun putString(key: String, value: String?): SharedPreferences.Editor =
            also { delegate.putString(k(key), value) }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor =
            also { delegate.putStringSet(k(key), values) }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor =
            also { delegate.putInt(k(key), value) }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor =
            also { delegate.putLong(k(key), value) }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor =
            also { delegate.putFloat(k(key), value) }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
            also { delegate.putBoolean(k(key), value) }
        override fun remove(key: String): SharedPreferences.Editor =
            also { delegate.remove(k(key)) }
        // clear 不映射：清空是整库语义，按档清空请走 DeviceProfiles.delete
        override fun clear(): SharedPreferences.Editor = also { delegate.clear() }
        override fun commit(): Boolean = delegate.commit()
        override fun apply() = delegate.apply()
    }
}

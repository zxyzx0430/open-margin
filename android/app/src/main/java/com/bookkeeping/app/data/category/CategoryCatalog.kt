package com.bookkeeping.app.data.category

import android.content.Context
import android.content.SharedPreferences
import com.bookkeeping.app.data.local.entity.TransactionCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class CategoryDefinition(
    val id: String,
    val name: String,
    val iconKey: String,
    val colorArgb: Long,
    val isBuiltIn: Boolean,
    val isLocked: Boolean = false,
    val isDeleted: Boolean = false,
)

data class CategoryIconPreset(
    val key: String,
    val label: String,
)

object CategoryPresets {
    val ICONS: List<CategoryIconPreset> = listOf(
        CategoryIconPreset("restaurant", "餐饮"),
        CategoryIconPreset("cafe", "饮品"),
        CategoryIconPreset("subway", "交通"),
        CategoryIconPreset("car", "出行"),
        CategoryIconPreset("flight", "旅行"),
        CategoryIconPreset("shopping", "购物"),
        CategoryIconPreset("esports", "娱乐"),
        CategoryIconPreset("home", "住房"),
        CategoryIconPreset("bolt", "水电"),
        CategoryIconPreset("medical", "医疗"),
        CategoryIconPreset("school", "教育"),
        CategoryIconPreset("fitness", "运动"),
        CategoryIconPreset("pets", "宠物"),
        CategoryIconPreset("phone", "数码"),
        CategoryIconPreset("payments", "工资"),
        CategoryIconPreset("star", "奖金"),
        CategoryIconPreset("savings", "理财"),
        CategoryIconPreset("health", "保险"),
        CategoryIconPreset("gift", "红包"),
        CategoryIconPreset("more", "其他"),
    )

    val COLORS: List<Long> = listOf(
        0xFFE8590C,
        0xFF74B816,
        0xFFFAB005,
        0xFF2F9E44,
        0xFF0CA678,
        0xFF1098AD,
        0xFF1C7ED6,
        0xFF4263EB,
        0xFF7048E8,
        0xFFAE3EC9,
        0xFFE64980,
        0xFFF03E3E,
        0xFF8C6D3F,
    )

    fun builtInIconKey(category: TransactionCategory): String = when (category) {
        TransactionCategory.FOOD -> "restaurant"
        TransactionCategory.TRANSPORT -> "subway"
        TransactionCategory.SHOPPING -> "shopping"
        TransactionCategory.ENTERTAINMENT -> "esports"
        TransactionCategory.HOUSING -> "home"
        TransactionCategory.UTILITIES -> "bolt"
        TransactionCategory.HEALTH -> "medical"
        TransactionCategory.EDUCATION -> "school"
        TransactionCategory.SALARY -> "payments"
        TransactionCategory.BONUS -> "star"
        TransactionCategory.INVESTMENT -> "savings"
        TransactionCategory.INSURANCE -> "health"
        TransactionCategory.GIFT -> "gift"
        TransactionCategory.TRANSFER -> "swap"
        TransactionCategory.OTHER -> "more"
    }

    fun builtInColorArgb(category: TransactionCategory): Long = when (category) {
        TransactionCategory.FOOD -> 0xFFE8590C
        TransactionCategory.EDUCATION -> 0xFF74B816
        TransactionCategory.BONUS -> 0xFFFAB005
        TransactionCategory.SALARY -> 0xFF2F9E44
        TransactionCategory.UTILITIES -> 0xFF0CA678
        TransactionCategory.INVESTMENT -> 0xFF1098AD
        TransactionCategory.TRANSPORT -> 0xFF1C7ED6
        TransactionCategory.INSURANCE -> 0xFF4263EB
        TransactionCategory.HOUSING -> 0xFF7048E8
        TransactionCategory.SHOPPING -> 0xFFAE3EC9
        TransactionCategory.ENTERTAINMENT -> 0xFFE64980
        TransactionCategory.GIFT -> 0xFFF03E3E
        TransactionCategory.HEALTH -> 0xFF8C6D3F
        TransactionCategory.TRANSFER -> 0xFF343A40
        TransactionCategory.OTHER -> 0xFF868E96
    }
}

/**
 * Pure JVM-testable store for built-in + custom categories.
 *
 * - Built-in `OTHER` and `TRANSFER` are locked and can never be deleted.
 * - Custom categories use their trimmed name as `id` so Room/sync_v2/MCP/CSV
 *   remain immediately human-readable across devices without backend schema changes.
 */
class CategoryCatalogStore(
    private val loadRaw: () -> String? = { null },
    private val saveRaw: (String) -> Unit = {},
) {
    private val lock = Any()
    private val _categories = MutableStateFlow(buildAll(loadState()))
    val categories: StateFlow<List<CategoryDefinition>> = _categories.asStateFlow()

    private data class PersistedState(
        val custom: List<CategoryDefinition> = emptyList(),
        val deletedBuiltIns: Set<String> = emptySet(),
    )

    fun activeCategories(): List<CategoryDefinition> =
        _categories.value.filter { !it.isDeleted }

    fun deletedBuiltInCategories(): List<CategoryDefinition> =
        _categories.value.filter { it.isBuiltIn && it.isDeleted }

    fun find(categoryKey: String): CategoryDefinition? =
        _categories.value.firstOrNull { it.id == categoryKey || it.name == categoryKey }

    fun displayName(categoryKey: String): String {
        find(categoryKey)?.let { return it.name }
        return TransactionCategory.entries.firstOrNull { it.name == categoryKey }?.displayName
            ?: categoryKey
    }

    fun addCustomCategory(
        rawName: String,
        iconKey: String,
        colorArgb: Long,
    ): Result<CategoryDefinition> = synchronized(lock) {
        val name = rawName.trim()
        if (name.isEmpty()) return Result.failure(IllegalArgumentException("分类名称不能为空"))
        if (name.length > 20) return Result.failure(IllegalArgumentException("分类名称最多 20 个字"))
        val state = loadState()
        val current = buildAll(state)
        if (current.any { !it.isDeleted && (it.name == name || it.id == name) }) {
            return Result.failure(IllegalArgumentException("已存在同名分类"))
        }
        val created = CategoryDefinition(
            id = name,
            name = name,
            iconKey = iconKey,
            colorArgb = colorArgb,
            isBuiltIn = false,
            isLocked = false,
            isDeleted = false,
        )
        val next = state.copy(custom = state.custom.filterNot { it.id == name } + created)
        persist(next)
        Result.success(created)
    }

    fun updateCustomCategory(
        id: String,
        rawNewName: String,
        iconKey: String,
        colorArgb: Long,
    ): Result<CategoryDefinition> = synchronized(lock) {
        val newName = rawNewName.trim()
        if (newName.isEmpty()) return Result.failure(IllegalArgumentException("分类名称不能为空"))
        if (newName.length > 20) return Result.failure(IllegalArgumentException("分类名称最多 20 个字"))
        val state = loadState()
        val existing = state.custom.firstOrNull { it.id == id }
            ?: return Result.failure(IllegalArgumentException("仅支持修改自定义分类"))
        val current = buildAll(state)
        if (current.any { !it.isDeleted && it.id != id && (it.name == newName || it.id == newName) }) {
            return Result.failure(IllegalArgumentException("已存在同名分类"))
        }
        val updated = existing.copy(
            id = newName,
            name = newName,
            iconKey = iconKey,
            colorArgb = colorArgb,
        )
        val next = state.copy(custom = state.custom.map { if (it.id == id) updated else it })
        persist(next)
        Result.success(updated)
    }

    fun deleteCategory(id: String): Result<Unit> = synchronized(lock) {
        val state = loadState()
        val target = buildAll(state).firstOrNull { it.id == id }
            ?: return Result.failure(IllegalArgumentException("分类不存在"))
        if (target.isLocked) {
            return Result.failure(IllegalArgumentException("系统保底分类不可删除"))
        }
        val next = if (target.isBuiltIn) {
            state.copy(deletedBuiltIns = state.deletedBuiltIns + target.id)
        } else {
            state.copy(custom = state.custom.filterNot { it.id == target.id })
        }
        persist(next)
        Result.success(Unit)
    }

    fun restoreBuiltIn(id: String): Boolean = synchronized(lock) {
        val state = loadState()
        if (id !in state.deletedBuiltIns) return false
        persist(state.copy(deletedBuiltIns = state.deletedBuiltIns - id))
        true
    }

    fun exportJson(): String = synchronized(lock) {
        encodeState(loadState())
    }

    fun importMerge(rawJson: String, overwrite: Boolean): Int = synchronized(lock) {
        val incoming = decodeState(rawJson)
        val current = loadState()
        val mergedCustom = if (overwrite) {
            incoming.custom
        } else {
            val map = linkedMapOf<String, CategoryDefinition>()
            current.custom.forEach { map[it.id] = it }
            incoming.custom.forEach { map.putIfAbsent(it.id, it) }
            map.values.toList()
        }
        val mergedDeleted = if (overwrite) {
            incoming.deletedBuiltIns
        } else {
            current.deletedBuiltIns
        }
        val next = PersistedState(custom = mergedCustom, deletedBuiltIns = mergedDeleted)
        persist(next)
        incoming.custom.size
    }

    private fun persist(state: PersistedState) {
        saveRaw(encodeState(state))
        _categories.value = buildAll(state)
    }

    private fun loadState(): PersistedState {
        val raw = loadRaw()?.trim().orEmpty()
        if (raw.isEmpty()) return PersistedState()
        return runCatching { decodeState(raw) }.getOrDefault(PersistedState())
    }

    private fun buildAll(state: PersistedState): List<CategoryDefinition> {
        val builtIns = TransactionCategory.entries.map { cat ->
            val locked = cat == TransactionCategory.OTHER || cat == TransactionCategory.TRANSFER
            CategoryDefinition(
                id = cat.name,
                name = cat.displayName,
                iconKey = CategoryPresets.builtInIconKey(cat),
                colorArgb = CategoryPresets.builtInColorArgb(cat),
                isBuiltIn = true,
                isLocked = locked,
                isDeleted = !locked && cat.name in state.deletedBuiltIns,
            )
        }
        return builtIns + state.custom
    }

    private fun encodeState(state: PersistedState): String {
        val root = JSONObject()
        val customArr = JSONArray()
        state.custom.forEach { item ->
            customArr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("name", item.name)
                    put("iconKey", item.iconKey)
                    put("colorArgb", item.colorArgb)
                },
            )
        }
        val deletedArr = JSONArray()
        state.deletedBuiltIns.sorted().forEach { deletedArr.put(it) }
        root.put("custom", customArr)
        root.put("deletedBuiltIns", deletedArr)
        return root.toString()
    }

    private fun decodeState(raw: String): PersistedState {
        val root = JSONObject(raw)
        val customArr = root.optJSONArray("custom") ?: JSONArray()
        val custom = buildList {
            for (i in 0 until customArr.length()) {
                val obj = customArr.optJSONObject(i) ?: continue
                val name = obj.optString("name").trim()
                if (name.isEmpty()) continue
                add(
                    CategoryDefinition(
                        id = obj.optString("id").trim().ifEmpty { name },
                        name = name,
                        iconKey = obj.optString("iconKey").ifBlank { "more" },
                        colorArgb = obj.optLong("colorArgb", 0xFF24705C),
                        isBuiltIn = false,
                        isLocked = false,
                        isDeleted = false,
                    ),
                )
            }
        }
        val deletedArr = root.optJSONArray("deletedBuiltIns") ?: JSONArray()
        val deleted = buildSet {
            for (i in 0 until deletedArr.length()) {
                val id = deletedArr.optString(i).trim()
                if (id.isNotEmpty() && id != TransactionCategory.OTHER.name && id != TransactionCategory.TRANSFER.name) {
                    add(id)
                }
            }
        }
        return PersistedState(custom = custom, deletedBuiltIns = deleted)
    }
}

object CategoryCatalog {
    private const val PREFS_NAME = "category_catalog"
    private const val KEY_JSON = "catalog_json"

    @Volatile
    private var store: CategoryCatalogStore? = null

    fun get(context: Context): CategoryCatalogStore {
        store?.let { return it }
        return synchronized(this) {
            store ?: createStore(context.applicationContext).also { store = it }
        }
    }

    private fun createStore(appContext: Context): CategoryCatalogStore {
        val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return CategoryCatalogStore(
            loadRaw = { prefs.getString(KEY_JSON, null) },
            saveRaw = { json -> prefs.edit().putString(KEY_JSON, json).apply() },
        )
    }

    fun peek(): CategoryCatalogStore? = store
}

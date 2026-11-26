package com.bookkeeping.app.data.category

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryCatalogStoreTest {

    @Test
    fun addRenameDeleteAndRestoreWorkAsExpected() {
        var stored: String? = null
        val store = CategoryCatalogStore(
            loadRaw = { stored },
            saveRaw = { stored = it },
        )

        val added = store.addCustomCategory("宠物", "pets", 0xFFE8590C).getOrThrow()
        assertEquals("宠物", added.id)
        assertEquals("宠物", store.displayName("宠物"))
        assertTrue(store.activeCategories().any { it.id == "宠物" })

        val duplicate = store.addCustomCategory(" 宠物 ", "pets", 0xFFE8590C)
        assertTrue(duplicate.isFailure)

        val updated = store.updateCustomCategory("宠物", "养猫", "pets", 0xFF2F9E44).getOrThrow()
        assertEquals("养猫", updated.id)
        assertFalse(store.activeCategories().any { it.id == "宠物" })

        assertTrue(store.deleteCategory("FOOD").isSuccess)
        assertFalse(store.activeCategories().any { it.id == "FOOD" })
        assertTrue(store.deletedBuiltInCategories().any { it.id == "FOOD" })
        assertTrue(store.restoreBuiltIn("FOOD"))
        assertTrue(store.activeCategories().any { it.id == "FOOD" })

        assertTrue(store.deleteCategory("OTHER").isFailure)
        assertTrue(store.deleteCategory("TRANSFER").isFailure)
    }

    @Test
    fun exportAndImportMergePreservesCustomCategories() {
        var srcRaw: String? = null
        val src = CategoryCatalogStore(loadRaw = { srcRaw }, saveRaw = { srcRaw = it })
        src.addCustomCategory("咖啡", "cafe", 0xFF8C6D3F).getOrThrow()
        val exported = src.exportJson()

        var dstRaw: String? = null
        val dst = CategoryCatalogStore(loadRaw = { dstRaw }, saveRaw = { dstRaw = it })
        dst.addCustomCategory("数码", "phone", 0xFF1C7ED6).getOrThrow()

        val imported = dst.importMerge(exported, overwrite = false)
        assertEquals(1, imported)
        assertTrue(dst.activeCategories().any { it.id == "咖啡" })
        assertTrue(dst.activeCategories().any { it.id == "数码" })
    }
}

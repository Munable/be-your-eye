package app.beyoureyes.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectClassCatalogTest {
    private val catalog = ObjectClassCatalog(
        listOf(
            ObjectClassDefinition("apple", "苹果", "apple", emptySet()),
            ObjectClassDefinition("car", "汽车", "car", setOf("车")),
            ObjectClassDefinition("cat", "猫", "cat", setOf("小猫")),
            ObjectClassDefinition("train", "火车", "train", setOf("列车")),
        ),
    )

    @Test
    fun `lookup accepts exact signed aliases after normalization`() {
        assertEquals("cat", (catalog.lookup("  CAT  ") as ObjectClassLookup.Matched).definition.targetId)
        assertEquals("cat", (catalog.lookup("猫") as ObjectClassLookup.Matched).definition.targetId)
        assertEquals("car", (catalog.lookup("车") as ObjectClassLookup.Matched).definition.targetId)
        assertEquals("car", (catalog.lookup("ＣＡＲ") as ObjectClassLookup.Matched).definition.targetId)
    }

    @Test
    fun `lookup accepts one exact signed alias inside a natural request`() {
        assertEquals(
            "apple",
            (catalog.lookup("当画面中出现苹果的时候通知我") as ObjectClassLookup.Matched)
                .definition.targetId,
        )
        assertEquals(
            "cat",
            (catalog.lookup("Notify me when a CAT is visible.") as ObjectClassLookup.Matched)
                .definition.targetId,
        )
    }

    @Test
    fun `lookup never guesses misspellings or one of multiple targets`() {
        assertTrue(catalog.lookup("catt") is ObjectClassLookup.NotFound)
        assertTrue(catalog.lookup("苹果和猫出现时通知我") is ObjectClassLookup.NotFound)
        assertTrue(catalog.lookup("scattered objects") is ObjectClassLookup.NotFound)
    }

    @Test
    fun `single CJK alias is fail closed inside a longer sentence`() {
        assertTrue(catalog.lookup("停车时提醒我") is ObjectClassLookup.NotFound)
        assertTrue(catalog.lookup("猫出现时提醒我") is ObjectClassLookup.NotFound)
    }

    @Test
    fun `longer signed alias suppresses only its contained shorter alias`() {
        assertEquals(
            "train",
            (catalog.lookup("火车出现时通知我") as ObjectClassLookup.Matched).definition.targetId,
        )
        assertTrue(catalog.lookup("火车和汽车出现时通知我") is ObjectClassLookup.NotFound)
    }

    @Test
    fun `conflicting signed aliases fail closed`() {
        assertThrows(IllegalArgumentException::class.java) {
            ObjectClassCatalog(
                listOf(
                    ObjectClassDefinition("apple", "苹果", "apple", emptySet()),
                    ObjectClassDefinition("other", "其他", "other", setOf("apple")),
                ),
            )
        }
    }
}

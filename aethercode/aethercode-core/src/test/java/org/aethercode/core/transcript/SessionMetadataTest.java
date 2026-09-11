package org.aethercode.core.transcript;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionMetadataTest {

    @Test
    void constructor_setsId() {
        SessionMetadata sm = new SessionMetadata("sess-1");
        assertEquals("sess-1", sm.sessionId());
    }

    @Test
    void constructor_nullIdGeneratesOne() {
        SessionMetadata sm = new SessionMetadata(null);
        assertTrue(sm.sessionId() != null && !sm.sessionId().isEmpty());
    }

    @Test
    void title_setter() {
        SessionMetadata sm = new SessionMetadata("s");
        sm.title("My Session");
        assertEquals("My Session", sm.title());
    }

    @Test
    void title_nullBecomesEmpty() {
        SessionMetadata sm = new SessionMetadata("s");
        sm.title(null);
        assertEquals("", sm.title());
    }

    @Test
    void addTag_addsUnique() {
        SessionMetadata sm = new SessionMetadata("s");
        sm.addTag("a").addTag("a").addTag("b");
        assertEquals(2, sm.tags().size());
        assertTrue(sm.tags().contains("a"));
        assertTrue(sm.tags().contains("b"));
    }

    @Test
    void addTag_rejectsBlank() {
        SessionMetadata sm = new SessionMetadata("s");
        sm.addTag("").addTag(null);
        assertTrue(sm.tags().isEmpty());
    }

    @Test
    void removeTag() {
        SessionMetadata sm = new SessionMetadata("s");
        sm.addTag("a");
        sm.removeTag("a");
        assertTrue(sm.tags().isEmpty());
    }

    @Test
    void removeTag_returnsThis() {
        SessionMetadata sm = new SessionMetadata("s");
        sm.addTag("a");
        assertEquals(sm, sm.removeTag("a"));
    }

    @Test
    void attributes_setAndGet() {
        SessionMetadata sm = new SessionMetadata("s");
        sm.setAttribute("k", "v");
        sm.setAttribute("n", 42);
        assertEquals("v", sm.getAttribute("k"));
        assertEquals(42, sm.getAttribute("n"));
    }

    @Test
    void attributesMap_isImmutable() {
        SessionMetadata sm = new SessionMetadata("s");
        sm.setAttribute("k", "v");
        assertThrows(UnsupportedOperationException.class, () -> sm.attributes().put("x", "y"));
    }

    @Test
    void tagsList_isImmutable() {
        SessionMetadata sm = new SessionMetadata("s");
        sm.addTag("a");
        assertThrows(UnsupportedOperationException.class, () -> sm.tags().add("b"));
    }

    @Test
    void setMessageCount() {
        SessionMetadata sm = new SessionMetadata("s");
        sm.setMessageCount(42);
        assertEquals(42, sm.messageCount());
    }

    @Test
    void toJson_includesAllFields() {
        SessionMetadata sm = new SessionMetadata("s");
        sm.title("test").addTag("a").setAttribute("k", "v");
        String json = sm.toJson();
        assertTrue(json.contains("\"title\":\"test\""));
        assertTrue(json.contains("\"a\""));
        assertTrue(json.contains("\"k\":\"v\""));
    }

    @Test
    void fromJson_roundTrips() {
        SessionMetadata sm = new SessionMetadata("sess-1");
        sm.title("hello").addTag("tag1").setAttribute("key", "value").setMessageCount(7);
        String json = sm.toJson();
        SessionMetadata back = SessionMetadata.fromJson(json);
        assertEquals(sm.sessionId(), back.sessionId());
        assertEquals(sm.title(), back.title());
        assertEquals(sm.tags(), back.tags());
        assertEquals(sm.messageCount(), back.messageCount());
        assertEquals("value", back.getAttribute("key"));
    }

    @Test
    void fromJson_invalidJsonThrows() {
        assertThrows(RuntimeException.class, () -> SessionMetadata.fromJson("{not valid"));
    }

    @Test
    void fromJson_handlesMissingFields() {
        SessionMetadata sm = SessionMetadata.fromJson("{\"sessionId\":\"x\"}");
        assertEquals("x", sm.sessionId());
        assertEquals("", sm.title());
        assertTrue(sm.tags().isEmpty());
    }

    @Test
    void equalsAndHashCode() {
        SessionMetadata a = new SessionMetadata("s").title("t").addTag("x");
        SessionMetadata b = new SessionMetadata("s").title("t").addTag("x");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        SessionMetadata c = new SessionMetadata("s").title("other");
        assertNotEquals(a, c);
    }

    @Test
    void toMap_includesEverything() {
        SessionMetadata sm = new SessionMetadata("s");
        sm.title("t").addTag("a").setAttribute("k", "v");
        var m = sm.toMap();
        assertEquals("s", m.get("sessionId"));
        assertEquals("t", m.get("title"));
        assertTrue(m.containsKey("tags"));
        assertTrue(m.containsKey("attributes"));
    }

    @Test
    void createdAt_defaultIsNow() {
        SessionMetadata sm = new SessionMetadata("s");
        assertNotNull(sm.createdAt());
    }

    @Test
    void updatedAt_changesOnModification() throws Exception {
        SessionMetadata sm = new SessionMetadata("s");
        var t1 = sm.updatedAt();
        Thread.sleep(5);
        sm.title("new");
        assertTrue(sm.updatedAt().isAfter(t1));
    }
}

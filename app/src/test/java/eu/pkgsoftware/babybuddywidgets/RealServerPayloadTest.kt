package eu.pkgsoftware.babybuddywidgets

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.Child
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.PaginatedEntries
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.Timer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Responses copied from a real Baby Buddy server. Retrofit's JacksonConverterFactory parses them with a
 * plain ObjectMapper, so a null for a non-null Kotlin parameter fails the whole list - which the UI
 * reports as "Connecting to server" forever.
 */
class RealServerPayloadTest {
    private val mapper = ObjectMapper()

    @Test
    fun unnamedQuickTimerListParses() {
        val json = """{"count":1,"next":null,"previous":null,"results":[{"id":10,"child":1,"name":null,"start":"2026-10-22T20:24:24Z","duration":"01:57:14.983444","user":1}]}"""
        val result = mapper.readValue(json, object : TypeReference<PaginatedEntries<Timer>>() {})
        assertEquals(1, result.entries.size)
        assertEquals(10, result.entries[0].id)
        assertNull(result.entries[0].name)
    }

    @Test
    fun namedTimerListParses() {
        val json = """{"count":1,"next":null,"previous":null,"results":[{"id":11,"child":1,"name":"Sleep-BBapp:2","start":"2026-10-22T20:24:24.123456Z","duration":"00:00:03.756094","user":1}]}"""
        val result = mapper.readValue(json, object : TypeReference<PaginatedEntries<Timer>>() {})
        assertEquals("Sleep-BBapp:2", result.entries[0].name)
    }

    @Test
    fun emptyTimerListParses() {
        val json = """{"count":0,"next":null,"previous":null,"results":[]}"""
        val result = mapper.readValue(json, object : TypeReference<PaginatedEntries<Timer>>() {})
        assertEquals(0, result.entries.size)
    }

    @Test
    fun childListParses() {
        val json = """{"count":1,"next":null,"previous":null,"results":[{"id":1,"first_name":"Ronan","last_name":"","birth_date":"2024-04-10","birth_time":null,"slug":"ronan","picture":null}]}"""
        val result = mapper.readValue(json, object : TypeReference<PaginatedEntries<Child>>() {})
        assertEquals("ronan", result.entries[0].slug)
    }
}

package eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import eu.pkgsoftware.babybuddywidgets.DateTimeDeserializer
import eu.pkgsoftware.babybuddywidgets.DateTimeSerializer
import java.util.Date

@UIPath("timers")
@APIPath("timers")
@JsonIgnoreProperties(ignoreUnknown = true)
data class Timer(
	@JsonProperty("id", required = true) val id: Int,
	@JsonProperty("child", required = true) val childId: Int,
	// Quick timers started from the web UI have no name (null); a non-null type here made parsing the
	// whole timer list fail, which the UI reported as "Connecting to server" forever.
	@JsonProperty("name", required = false) val name: String?,
	@JsonProperty("start", required = true)
	@JsonDeserialize(using = DateTimeDeserializer::class)
	@JsonSerialize(using = DateTimeSerializer::class)
	val start: Date,
	@JsonProperty("duration", required = true) val duration: String,
	@JsonProperty("user", required = true) val userId: Int,
)

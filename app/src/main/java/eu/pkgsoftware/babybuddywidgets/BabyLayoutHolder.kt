package eu.pkgsoftware.babybuddywidgets

import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.squareup.phrase.Phrase
import eu.pkgsoftware.babybuddywidgets.activitycomponents.TimerControl
import eu.pkgsoftware.babybuddywidgets.compat.BabyBuddyV2TimerAdapter
import eu.pkgsoftware.babybuddywidgets.compat.IMPLEMENTED_ACTIVITIES
import eu.pkgsoftware.babybuddywidgets.databinding.BabyManagerBinding
import eu.pkgsoftware.babybuddywidgets.debugging.GlobalDebugObject
import eu.pkgsoftware.babybuddywidgets.history.ChildEventHistoryLoader
import eu.pkgsoftware.babybuddywidgets.history.ShowErrorPill
import eu.pkgsoftware.babybuddywidgets.networking.BabyBuddyClient
import eu.pkgsoftware.babybuddywidgets.networking.RequestCodeFailure
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.InterruptedException
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.PaginatedResult
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.Child
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TimeEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.Timer
import eu.pkgsoftware.babybuddywidgets.timers.FragmentCallbacks
import eu.pkgsoftware.babybuddywidgets.timers.LoggingButtonController
import eu.pkgsoftware.babybuddywidgets.timers.TimerControlInterface
import eu.pkgsoftware.babybuddywidgets.timers.TimersUpdatedCallback
import eu.pkgsoftware.babybuddywidgets.timers.TranslatedException
import eu.pkgsoftware.babybuddywidgets.utils.Promise
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

class BabyLayoutHolder(
    private val baseFragment: BaseFragment,
    private val binding: BabyManagerBinding
) : RecyclerView.ViewHolder(
    binding.getRoot()
), TimerControlInterface {
    private val client: BabyBuddyClient

    var child: Child? = null

    private var childHistoryLoader: ChildEventHistoryLoader? = null

    private var cachedTimers: Array<BabyBuddyClient.Timer>? = null
    private val updateTimersCallbacks: MutableList<TimersUpdatedCallback> =
        ArrayList(10)

    private var pendingTimerModificationCalls = 0
    private var deselected = true
    private var timerPollJob: kotlinx.coroutines.Job? = null

    private var loggingButtonController: LoggingButtonController? = null

    init {
        client = baseFragment.mainActivity.client

        binding.mainScrollView.setOnScrollChangeListener(View.OnScrollChangeListener { v: View?, scrollX: Int, scrollY: Int, oldScrollX: Int, oldScrollY: Int ->
            childHistoryLoader?.updateTop()
        })
    }

    fun updateChild(c: Child) {
        if (c == child && !deselected) {
            return
        }

        Log.d(
            "BabyLayoutHolder",
            "Selecting view for child ${c.slug} (current child: ${child?.slug})"
        )

        var childChanged = false
        if (child !== c) {
            childChanged = true
            close()
            this.child = c
        }

        resetChildHistoryLoader()
        closeButtonController()

        child?.let { child ->
            deselected = false

            timerPollJob?.let {
                if (childChanged) {
                    it.cancel()
                }
            }
            if (!(timerPollJob?.isActive ?: false)) {
                timerPollJob = baseFragment.mainActivity.scope.launch {
                    updateTimerLoop(child)
                }
            }

            childHistoryLoader = ChildEventHistoryLoader(
                baseFragment,
                binding.innerTimeline,
                child.id,
                VisibilityCheck(binding.mainScrollView),
                binding.timelineProgressSpinner,
                object : ShowErrorPill {
                    override fun showErrorPill(entryType: String, exception: Exception?) {
                        val tActivity = baseFragment.translateActivityName(entryType!!)
                        val msg = Phrase.from(
                            baseFragment.getResources(),
                            R.string.history_loading_timeline_entry_failed
                        )
                            .put("activity", tActivity)
                            .format().toString()
                        baseFragment.mainActivity.binding.globalErrorBubble.flashMessage(msg)
                    }
                }
            )

            loggingButtonController = LoggingButtonController(
                baseFragment,
                binding,
                object : FragmentCallbacks {
                    override fun insertControls(view: View) {
                        if (view.getParent() != null) {
                            return
                        }
                        binding.loggingEditors.addView(view)
                    }

                    override fun removeControls(view: View) {
                        if (view.getParent() == null) {
                            return
                        }
                        binding.loggingEditors.removeView(view)
                    }

                    override fun updateTimeline(newEntry: TimeEntry?) {
                        if (childHistoryLoader != null) {
                            if (newEntry != null) {
                                childHistoryLoader!!.addEntryToTop(newEntry)
                            }
                            childHistoryLoader!!.forceRefresh()
                        }
                    }
                },
                child,
                this
            )
        }
    }

    suspend fun updateTimerLoop(childArg: Child?) {
        if (childArg == null) {
            return
        }
        while (!deselected) {
            var newTimersResult: PaginatedResult<Timer>? = null
            try {
                newTimersResult = client.v2client.getEntries(Timer::class, childId = childArg.id)
            } catch (e: InterruptedException) {
                GlobalDebugObject.log("BabyBuddyHolder timer retrieval interrupted")
            } catch (e: RequestCodeFailure) {
                GlobalDebugObject.log("BabyBuddyHolder timer retrieval failed with code ${e.code}")
            } catch (e: HttpException) {
                GlobalDebugObject.log("BabyBuddyHolder timer retrieval failed with HTTP exception ${e.message}")
            } catch (e: IOException) {
                // Generic I/O / network errors (DNS, connection refused, etc.)
                GlobalDebugObject.log("BabyBuddyHolder timer retrieval failed with IO exception: ${e.message}")
            }

            val disconnectInterface = baseFragment.disconnectInterface
            if (newTimersResult != null) {
                val oldTimers = mutableListOf<BabyBuddyClient.Timer>()
                for (t in newTimersResult.entries) {
                    val oldTimer = BabyBuddyClient.Timer();
                    oldTimer.id = t.id
                    oldTimer.child_id = t.childId
                    oldTimer.name = t.name
                    oldTimer.start = t.start
                    oldTimer.active = true
                    oldTimer.user_id = t.userId
                    oldTimers.add(oldTimer)
                }
                disconnectInterface.setDisconnected("child list", false)
                updateTimerList(oldTimers.toTypedArray())
            } else {
                disconnectInterface.setDisconnected("child list", true)
            }
            kotlinx.coroutines.delay(1000.milliseconds)
        }
    }

    fun updateTimerList(timers: Array<BabyBuddyClient.Timer>) {
        if (pendingTimerModificationCalls > 0) {
            // Buffer timer-list updates while a timer-modifying operation is running
            // (prevents confusing UI updates)
            return
        }

        if (child == null) {
            cachedTimers = arrayOf()
            renderOtherTimers(arrayOf())
            callTimerUpdateCallback()
            return
        }

        for (t in timers) {
            child?.let { child ->
                if (t.child_id != child.id) {
                    return
                }
            } ?: return
        }

        cachedTimers = timers
        renderOtherTimers(timers)
        callTimerUpdateCallback()
    }

    /**
     * Timers whose names don't map onto one of the app's own activity buttons (e.g. started from
     * the web UI) have no button to live on, so list them read-only instead of silently dropping them.
     */
    private fun renderOtherTimers(timers: Array<BabyBuddyClient.Timer>) {
        val others = timers.filter {
            val activity = BabyBuddyV2TimerAdapter.mapBabyBuddyNameToActivity(it.readableName())
            activity == null || activity !in IMPLEMENTED_ACTIVITIES
        }

        binding.otherTimersContainer.visibility = if (others.isEmpty()) View.GONE else View.VISIBLE
        val list = binding.otherTimersList
        while (list.childCount > others.size) {
            list.removeViewAt(list.childCount - 1)
        }
        while (list.childCount < others.size) {
            list.addView(TextView(list.context).apply { textSize = 16f })
        }

        others.forEachIndexed { i, timer ->
            val start = timer.start ?: return@forEachIndexed
            val elapsed = maxOf(0L, (timer.computeCurrentServerEndTime(client).time - start.time) / 1000)
            (list.getChildAt(i) as TextView).text = String.format(
                Locale.US, "%s  %d:%02d:%02d",
                timer.readableName(), elapsed / 3600, elapsed % 3600 / 60, elapsed % 60
            )
        }
    }

    private fun resetChildHistoryLoader() {
        childHistoryLoader?.close()
        childHistoryLoader = null
    }

    private fun closeButtonController() {
        loggingButtonController?.let {
            it.storeStateForSuspend()
            it.destroy()
            loggingButtonController = null
        }
    }

    fun onViewDeselected() {
        Log.d(
            "BabyLayoutHolder",
            "Deselected view for child " + child?.slug
        )
        closeButtonController()
        resetChildHistoryLoader()
        deselected = true
    }

    fun close() {
        closeButtonController()
        resetChildHistoryLoader()
        timerPollJob?.cancel()
        deselected = true
        child = null
        cachedTimers = null
        renderOtherTimers(arrayOf())
    }

    private inner class UpdateBufferingPromise<A, B>(private val promise: Promise<A, B>) :
        Promise<A, B> {
        init {
            pendingTimerModificationCalls++
        }

        override fun succeeded(a: A) {
            pendingTimerModificationCalls--
            promise.succeeded(a)
        }

        override fun failed(b: B) {
            pendingTimerModificationCalls--
            promise.failed(b)
        }
    }

    override fun createNewTimer(
        timer: BabyBuddyClient.Timer,
        cb: Promise<BabyBuddyClient.Timer, TranslatedException>
    ) {
        child?.let { child ->
            baseFragment.mainActivity.getChildTimerControl(child.id).createNewTimer(
                timer, UpdateBufferingPromise(cb)
            )
        }
    }

    override fun startTimer(
        timer: BabyBuddyClient.Timer,
        cb: Promise<BabyBuddyClient.Timer, TranslatedException>
    ) {
        child?.let { child ->
            baseFragment.mainActivity.getChildTimerControl(child.id).startTimer(
                timer, UpdateBufferingPromise(cb)
            )
        }
    }

    override fun stopTimer(timer: BabyBuddyClient.Timer, cb: Promise<Any, TranslatedException>) {
        child?.let { child ->
            baseFragment.mainActivity.getChildTimerControl(child.id).stopTimer(
                timer, UpdateBufferingPromise(cb)
            )
        }
    }

    override fun registerTimersUpdatedCallback(callback: TimersUpdatedCallback) {
        child?.let { child ->
            if (updateTimersCallbacks.contains(callback)) {
                return
            }
            updateTimersCallbacks.add(callback)

            baseFragment.mainActivity.getChildTimerControl(child.id).registerTimersUpdatedCallback(
                object : TimersUpdatedCallback {
                    override fun newTimerListLoaded(timers: Array<BabyBuddyClient.Timer>) {
                        for (c in updateTimersCallbacks) {
                            c.newTimerListLoaded(timers)
                        }
                    }
                }
            )

            callTimerUpdateCallback()
        }
    }

    override fun unregisterTimersUpdatedCallback(callback: TimersUpdatedCallback) {
        if (!updateTimersCallbacks.contains(callback)) {
            return
        }
        updateTimersCallbacks.remove(callback)
    }

    private fun callTimerUpdateCallback() {
        // urgh...
        child?.let { child ->
            val wrapped =
                baseFragment.mainActivity.getChildTimerControl(child.id).wrap as TimerControl
            if (cachedTimers != null) {
                wrapped.callTimerUpdateCallback(cachedTimers!!)
            }
        }
    }

    override fun getNotes(timer: BabyBuddyClient.Timer): CredStore.Notes {
        return child?.let { child ->
            return@let baseFragment.mainActivity.getChildTimerControl(child.id).getNotes(timer)
        } ?: CredStore.Notes("", false)
    }

    override fun setNotes(timer: BabyBuddyClient.Timer, notes: CredStore.Notes?) {
        child?.let { child ->
            baseFragment.mainActivity.getChildTimerControl(child.id).setNotes(timer, notes)
        }
    }
}

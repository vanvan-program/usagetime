package dev.usagetime.app

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class ReportRequestRunnerTest {
    private class QueueDispatcher:CoroutineDispatcher(){
        private val queue=ArrayDeque<Runnable>()
        override fun dispatch(context:CoroutineContext,block:Runnable){queue.addLast(block)}
        fun drain(){
            var steps=0
            while(queue.isNotEmpty()){
                check(++steps<10000){"Coroutine queue did not settle"}
                queue.removeFirst().run()
            }
        }
    }
    private class LateResult {
        private lateinit var continuation:Continuation<String>
        // Deliberately ignores cancellation until the source eventually replies.
        suspend fun await():String=suspendCoroutine{continuation=it}
        fun complete(value:String){continuation.resume(value)}
    }
    private class Fixture:AutoCloseable {
        val dispatcher=QueueDispatcher()
        val scope=CoroutineScope(SupervisorJob()+dispatcher)
        val events=mutableListOf<String>()
        var computing=false
        var display="saved"
        var error:Exception?=IllegalStateException("previous")
        val commits=mutableListOf<String>()
        val runner=ReportRequestRunner<String>(
            scope=scope,
            onComputing={computing=it;events+="computing:$it"},
            onError={error=it;events+="error:${it.message}"},
            beginTrace={events+="begin:$it"},
            endTrace={events+="end:$it"})
        fun submit(load:suspend ()->String)=runner.submit(load){value->
            assertTrue(computing)
            display=value;error=null;commits+=value;events+="commit:$value"
        }
        override fun close(){scope.cancel();dispatcher.drain()}
    }

    @Test fun successCommitsBeforeEndingTraceAndClearingComputing(){
        Fixture().use{f->
            val source=CompletableDeferred<String>()
            f.submit{source.await()};f.dispatcher.drain()
            assertTrue(f.computing);assertEquals("saved",f.display)
            assertEquals("previous",f.error!!.message)
            source.complete("fresh");f.dispatcher.drain()
            assertEquals("fresh",f.display);assertNull(f.error);assertFalse(f.computing)
            assertEquals(listOf("begin:1","computing:true","commit:fresh","end:1","computing:false"),f.events)
        }
    }

    @Test fun failureKeepsSavedDisplayAndReportsOriginalException(){
        Fixture().use{f->
            val failure=IllegalStateException("cannot read")
            f.submit{throw failure};f.dispatcher.drain()
            assertSame(failure,f.error);assertEquals("saved",f.display)
            assertTrue(f.commits.isEmpty());assertFalse(f.computing)
            assertEquals(listOf("begin:1","computing:true","error:cannot read","end:1","computing:false"),f.events)
        }
    }

    @Test fun sourceCancellationIsNotAReadFailure(){
        Fixture().use{f->
            val previous=f.error
            f.submit{throw CancellationException("source cancelled")};f.dispatcher.drain()
            assertSame(previous,f.error);assertEquals("saved",f.display)
            assertFalse(f.computing);assertTrue(f.commits.isEmpty())
            assertEquals(listOf("begin:1","computing:true","end:1","computing:false"),f.events)
        }
    }

    @Test fun replacedCooperativeRequestCannotClearNewLoadingState(){
        Fixture().use{f->
            val old=CompletableDeferred<String>();val next=CompletableDeferred<String>()
            f.submit{old.await()};f.dispatcher.drain()
            f.submit{next.await()};f.dispatcher.drain()
            assertTrue(f.computing);assertTrue(f.commits.isEmpty())
            assertFalse(f.events.contains("computing:false"))
            next.complete("next");f.dispatcher.drain()
            assertEquals(listOf("next"),f.commits);assertFalse(f.computing)
            assertTrue(f.events.contains("end:1"));assertTrue(f.events.contains("end:2"))
        }
    }

    @Test fun lateOldResultCannotOverwriteAlreadyCommittedNewResult(){
        Fixture().use{f->
            val old=LateResult()
            f.submit{old.await()};f.dispatcher.drain()
            f.submit{"new"};f.dispatcher.drain()
            old.complete("old");f.dispatcher.drain()
            assertEquals("new",f.display);assertEquals(listOf("new"),f.commits)
            assertNull(f.error);assertFalse(f.computing)
            assertEquals(1,f.events.count{it=="computing:false"})
            assertEquals(1,f.events.count{it=="end:1"})
        }
    }

    @Test fun lateOldResultCannotClearComputingWhileNewResultIsPending(){
        Fixture().use{f->
            val old=LateResult();val next=CompletableDeferred<String>()
            f.submit{old.await()};f.dispatcher.drain()
            f.submit{next.await()};f.dispatcher.drain()
            old.complete("old");f.dispatcher.drain()
            assertTrue(f.computing);assertEquals("saved",f.display)
            assertFalse(f.events.contains("computing:false"))
            next.complete("new");f.dispatcher.drain()
            assertEquals(listOf("new"),f.commits);assertFalse(f.computing)
        }
    }

    @Test fun rapidChangesBeforeDispatchOnlyStartLatestRequest(){
        Fixture().use{f->
            f.submit{"first"};f.submit{"second"};f.submit{"third"}
            f.dispatcher.drain()
            assertEquals(listOf("third"),f.commits)
            assertEquals(listOf("begin:3","computing:true","commit:third","end:3","computing:false"),f.events)
        }
    }

    @Test fun enteringSettingsStopsImmediatelyAndPreservesSavedState(){
        Fixture().use{f->
            val old=LateResult();val previous=f.error
            f.submit{old.await()};f.dispatcher.drain()
            f.runner.stop()
            assertFalse(f.computing);assertEquals("saved",f.display);assertSame(previous,f.error)
            old.complete("late");f.dispatcher.drain()
            assertTrue(f.commits.isEmpty());assertFalse(f.computing);assertSame(previous,f.error)
            assertEquals(1,f.events.count{it=="computing:false"})
            assertTrue(f.events.contains("end:1"))
        }
    }

    @Test fun returningFromSettingsStartsANewRequestWithoutReusingOldWork(){
        Fixture().use{f->
            val old=CompletableDeferred<String>()
            f.submit{old.await()};f.dispatcher.drain()
            f.runner.stop();f.dispatcher.drain()
            f.submit{"returned"};f.dispatcher.drain()
            assertEquals(listOf("returned"),f.commits);assertFalse(f.computing)
            assertEquals(1,f.events.count{it=="begin:2"})
        }
    }

    @Test fun cancelBeforeCapturingReplacementDoesNotPublishIdle(){
        Fixture().use{f->
            val old=CompletableDeferred<String>()
            f.submit{old.await()};f.dispatcher.drain()
            f.runner.cancel();f.dispatcher.drain()
            assertTrue(f.computing);assertTrue(f.commits.isEmpty())
            f.submit{"replacement"};f.dispatcher.drain()
            assertEquals(listOf("replacement"),f.commits);assertFalse(f.computing)
        }
    }

    @Test fun refreshWaitsForTheCurrentReportBeforeContinuing(){
        Fixture().use{f->
            val source=CompletableDeferred<String>();var continued=false
            f.submit{source.await()}
            f.scope.launch{f.runner.join();continued=true}
            f.dispatcher.drain();assertFalse(continued)
            source.complete("ready");f.dispatcher.drain()
            assertTrue(continued);assertEquals("ready",f.display)
        }
    }

    @Test fun joinKeepsTheJobCapturedBeforeAnotherReload(){
        Fixture().use{f->
            val old=CompletableDeferred<String>();val next=CompletableDeferred<String>()
            var continued=false
            f.submit{old.await()};f.dispatcher.drain()
            f.scope.launch{f.runner.join();continued=true};f.dispatcher.drain()
            f.submit{next.await()};f.dispatcher.drain()
            assertTrue(continued);assertTrue(f.computing);assertTrue(f.commits.isEmpty())
            next.complete("next");f.dispatcher.drain()
        }
    }

    @Test fun joinAfterReplacementDoesNotWaitForLateCancelledSource(){
        Fixture().use{f->
            val old=LateResult();val next=CompletableDeferred<String>();var continued=false
            f.submit{old.await()};f.dispatcher.drain()
            f.submit{next.await()}
            f.scope.launch{f.runner.join();continued=true};f.dispatcher.drain()
            assertFalse(continued)
            next.complete("next");f.dispatcher.drain()
            assertTrue(continued);assertEquals(listOf("next"),f.commits)
            old.complete("old");f.dispatcher.drain()
        }
    }

    @Test fun retryAfterFailureKeepsErrorUntilSuccess(){
        Fixture().use{f->
            val failure=IllegalArgumentException("bad history")
            f.submit{throw failure};f.dispatcher.drain()
            val retry=CompletableDeferred<String>()
            f.submit{retry.await()};f.dispatcher.drain()
            assertSame(failure,f.error);assertEquals("saved",f.display);assertTrue(f.computing)
            retry.complete("recovered");f.dispatcher.drain()
            assertNull(f.error);assertEquals("recovered",f.display);assertFalse(f.computing)
        }
    }

    @Test fun parentCancellationEndsTraceWithoutReportingAReadFailure(){
        Fixture().use{f->
            val source=CompletableDeferred<String>();val previous=f.error
            f.submit{source.await()};f.dispatcher.drain()
            f.scope.cancel();f.dispatcher.drain()
            assertSame(previous,f.error);assertTrue(f.commits.isEmpty())
            assertTrue(f.events.contains("end:1"))
            assertFalse(f.events.contains("computing:false"))
        }
    }

    @Test fun waitingWithoutAnyRequestCompletesImmediately(){
        Fixture().use{f->
            var continued=false
            f.scope.launch{f.runner.join();continued=true};f.dispatcher.drain()
            assertTrue(continued);assertTrue(f.events.isEmpty())
        }
    }
}

package dev.usagetime.app

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.usagetime.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/** CPU-side draw after state submission; no Espresso or Compose test rule. */
class StatisticsChartPerformanceTest {
    @Test fun refreshLongHistoryChartAndCaptureProcessMemory(){
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val generation=mutableIntStateOf(0)
        val requested=AtomicLong(0)
        val drawn=AtomicLong(0)
        val buckets=List(3653){i->
            Bucket(i*86_400_000L,(i+1)*86_400_000L,"${i%12+1}/${i%28+1}",
                (i%100)*60_000L,false,i%97!=0)
        }
        ActivityScenario.launch(MainActivity::class.java).use{scenario->
            scenario.onActivity{activity->
                activity.setContent{
                    val version=generation.intValue
                    MaterialTheme{
                        Box(Modifier.drawWithContent{
                            val drawGeneration=generation.intValue
                            drawContent()
                            if(drawGeneration>0&&requested.get()!=0L)drawn.compareAndSet(0,SystemClock.elapsedRealtimeNanos())
                        }){UsageChart(if(version%2==0)buckets else buckets.asReversed(),line=version%2==0)}
                    }
                }
            }
            instrumentation.waitForIdleSync()
            val timings=JSONArray()
            repeat(15){
                drawn.set(0)
                scenario.onActivity{
                    requested.set(SystemClock.elapsedRealtimeNanos())
                    generation.intValue++
                }
                val deadline=SystemClock.elapsedRealtime()+15_000
                while(drawn.get()==0L&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(1)
                assertTrue("Chart did not draw within 15 seconds",drawn.get()>0)
                timings.put((drawn.get()-requested.get())/1_000_000.0)
            }
            val sorted=(0 until timings.length()).map{timings.getDouble(it)}.sorted()
            val body=JSONObject().put("buckets",3653).put("runs",15)
                .put("median_ms",sorted[sorted.size/2]).put("p95_ms",sorted[(sorted.size*.95).roundToInt()-1])
                .put("state_to_cpu_draw_ms",timings).put("process_pss_bytes",Debug.getPss()*1024L)
                .put("scope","Debug emulator; chart only; synthetic data; excludes Room/ViewModel/GPU presentation")
            val context=instrumentation.targetContext
            java.io.File(context.cacheDir,"statistics-chart-performance.json").writeText(body.toString(2))
            Log.i("StatisticsPerformance",body.toString())
        }
    }
}

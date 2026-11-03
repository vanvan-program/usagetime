package dev.usagetime.app

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.usagetime.core.*
import kotlin.math.*

@Composable fun UsageChart(left:List<Bucket>,right:List<Bucket>?=null,line:Boolean=false){
    val count=max(left.size,right?.size?:0)
    if(count==0){Text("暂无可展示的记录",Modifier.padding(vertical=24.dp),color=MaterialTheme.colorScheme.onSurfaceVariant);return}
    var selected by remember(left,right){mutableIntStateOf((left.lastIndex).coerceAtLeast(0))}
    val scheme=MaterialTheme.colorScheme
    val density=LocalDensity.current
    val textSize=with(density){11.sp.toPx()}
    val primary=scheme.primary;val secondary=scheme.secondary;val grid=scheme.outlineVariant;val text=scheme.onSurfaceVariant
    val maxValue=remember(left,right){max(left.maxOfOrNull{it.millis}?:0,right?.maxOfOrNull{it.millis}?:0)}
    val maxHours=(maxValue/3600000.0*1.1).coerceAtLeast(.5)
    val topHours=listOf(.5,1.0,2.0,4.0,6.0,8.0,12.0,24.0,48.0,96.0,192.0,384.0,768.0,1536.0).firstOrNull{it>=maxHours}?:ceil(maxHours/100)*100
    val maxMs=topHours*3600000
    val paint=remember(text,textSize){Paint(Paint.ANTI_ALIAS_FLAG).apply{
        color=text.toArgb();this.textSize=textSize;typeface=android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.NORMAL)
    }}
    fun pointText(index:Int):String{
        val a=left.getOrNull(index);val b=right?.getOrNull(index)
        fun value(bucket:Bucket?)=if(bucket?.available==true)durationText(bucket.millis)else"未记录"
        return "${a?.label?:b?.label?:""} · ${if(right!=null)"统计 "else""}${value(a)}${if(right!=null)" / 对照 ${value(b)}"else""}"
    }
    val description=pointText(selected)
    Column{
        Canvas(Modifier.fillMaxWidth().height(172.dp).semantics{
            contentDescription="${if(line)"折线图"else"柱状图"}。$description。滑动调节日期查看详细时长。"
            progressBarRangeInfo=ProgressBarRangeInfo(selected.toFloat(),0f..(count-1).toFloat(),(count-2).coerceAtLeast(0))
            setProgress{selected=it.roundToInt().coerceIn(0,count-1);true}
        }.pointerInput(left,right,line){detectTapGestures{tap->
            val margin=with(density){44.dp.toPx()};val usable=size.width-margin-with(density){12.dp.toPx()}
            selected=(((tap.x-margin)/usable)*count).toInt().coerceIn(0,count-1)
        }}){
            val l=44.dp.toPx();val r=12.dp.toPx();val t=14.dp.toPx();val bottom=size.height-28.dp.toPx();val width=size.width-l-r;val height=bottom-t;val slot=width/count
            fun x(i:Int)=l+slot*(i+.5f)
            fun y(value:Long)=bottom-(value/maxMs*height).toFloat()
            paint.textAlign=Paint.Align.LEFT
            for(i in 0..2){
                val value=topHours*i/2;val at=bottom-height*i/2
                drawLine(grid,Offset(l,at),Offset(size.width-r,at),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(),3.dp.toPx())))
                val label=if(i==0)"0"else if(value<1)"${(value*60).roundToInt()}分"else"${if(value==value.toLong().toDouble())value.toLong().toString()else"%.1f".format(value)}时"
                drawContext.canvas.nativeCanvas.drawText(label,2.dp.toPx(),at+textSize*.35f,paint)
            }
            val every=ceil(count/5.0).toInt().coerceAtLeast(1)
            for(i in 0 until count){if(i%every==0||i==count-1&&count>1&&(i%every>=every/2)){
                val label=(left.getOrNull(i)?:right?.getOrNull(i))?.label?:""
                paint.textAlign=Paint.Align.CENTER
                drawContext.canvas.nativeCanvas.drawText(label,x(i),size.height-5.dp.toPx(),paint)
            }}
            drawRect(primary.copy(alpha=.06f),Offset(l+slot*selected,t),androidx.compose.ui.geometry.Size(slot,height))
            fun series(points:List<Bucket>,color:Color,isOther:Boolean){
                if(line){
                    var previous:Offset?=null
                    points.forEachIndexed{i,b->
                        if(!b.available){previous=null}else{
                            val pos=Offset(x(i),y(b.millis))
                            previous?.let{drawLine(color,it,pos,2.dp.toPx(),cap=StrokeCap.Round,pathEffect=if(isOther)PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(),4.dp.toPx()))else null)}
                            if(count<=35){drawCircle(if(isOther)scheme.surface else color,3.dp.toPx(),pos);if(isOther)drawCircle(color,3.dp.toPx(),pos,style=Stroke(1.5.dp.toPx()))}
                            previous=pos
                        }
                    }
                }else points.forEachIndexed{i,b->
                    val bar=min(26.dp.toPx(),slot*(if(right!=null).32f else .58f))
                    val center=x(i)+(if(right==null)0f else if(isOther)bar*.6f else -bar*.6f)
                    if(b.available){val h=(b.millis/maxMs*height).toFloat();drawRoundRect(color,Offset(center-bar/2,bottom-h),androidx.compose.ui.geometry.Size(bar,h.coerceAtLeast(if(b.millis>0)1f else 0f)),androidx.compose.ui.geometry.CornerRadius(min(3.dp.toPx(),bar/3)))}
                }
            }
            series(left,primary,false);right?.let{series(it,secondary,true)}
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            IconButton(onClick={selected=(selected-1).coerceAtLeast(0)},enabled=selected>0){Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft,"上一个数据点")}
            Text(description,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=scheme.onSurfaceVariant,maxLines=3)
            IconButton(onClick={selected=(selected+1).coerceAtMost(count-1)},enabled=selected<count-1){Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight,"下一个数据点")}
        }
    }
}

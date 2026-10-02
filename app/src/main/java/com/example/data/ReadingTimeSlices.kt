package com.example.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

internal data class ReadingTimeSlice(val date:String,val start:Long,val end:Long,val seconds:Long,val hour:Int)
internal object ReadingTimeSlices {
    fun split(start:Long,end:Long,seconds:Long):List<ReadingTimeSlice> {
        if(end<=start || seconds<=0) return emptyList()
        require(end-start<=7L*24*60*60*1000) { "阅读会话时间范围异常" }
        val format=SimpleDateFormat("yyyy-MM-dd",Locale.US)
        val result=mutableListOf<ReadingTimeSlice>()
        var cursor=start
        var allocated=0L
        while(cursor<end) {
            val calendar=Calendar.getInstance().apply { timeInMillis=cursor }
            val date=format.format(calendar.time)
            val hour=calendar.get(Calendar.HOUR_OF_DAY)
            calendar.add(Calendar.DAY_OF_YEAR,1)
            calendar.set(Calendar.HOUR_OF_DAY,0); calendar.set(Calendar.MINUTE,0)
            calendar.set(Calendar.SECOND,0); calendar.set(Calendar.MILLISECOND,0)
            val stop=minOf(end,calendar.timeInMillis)
            val through=if(stop==end) seconds else (seconds.toDouble()*(stop-start)/(end-start)).toLong()
            result.add(ReadingTimeSlice(date,cursor,stop,through-allocated,hour))
            allocated=through; cursor=stop
        }
        return result
    }
}

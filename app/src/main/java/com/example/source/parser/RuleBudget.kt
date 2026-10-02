package com.example.source.parser

/** Regex runs on a deadline-aware CharSequence, including backtracking in java.util.regex. */
internal object RuleBudget {
    fun validate(rule:String) { require(rule.length<=4096) { "书源规则过长" }; require(rule.count { it=='@' || it=='&' || it=='|' }<=128) { "书源规则嵌套过多" } }
    fun json(value:String) {
        require(value.length<=2*1024*1024) { "JSON 超过 2MiB" }
        var depth=0; var quoted=false; var escaped=false
        for(ch in value) {
            if(quoted) { if(escaped) escaped=false else if(ch=='\\') escaped=true else if(ch=='"') quoted=false }
            else when(ch) {
                '"' -> quoted=true
                '{','[' -> { depth++; require(depth<=64) { "JSON 嵌套过深" } }
                '}',']' -> depth--
            }
        }
    }
    fun text(value:String, millis:Long=1000):CharSequence {
        require(value.length<=2*1024*1024) { "规则输入超过 2MiB" }
        return Bounded(value,0,value.length,android.os.SystemClock.elapsedRealtime()+millis)
    }
    private class Bounded(val value:String,val begin:Int,override val length:Int,val deadline:Long):CharSequence {
        private var reads=0
        override fun get(index:Int):Char {
            if(++reads%256==0) check(android.os.SystemClock.elapsedRealtime()<=deadline && !Thread.currentThread().isInterrupted) { "书源正则执行超时" }
            require(index in 0 until length)
            return value[begin+index]
        }
        override fun subSequence(startIndex:Int,endIndex:Int):CharSequence=Bounded(value,begin+startIndex,endIndex-startIndex,deadline)
        override fun toString():String=value.substring(begin,begin+length)
    }
}

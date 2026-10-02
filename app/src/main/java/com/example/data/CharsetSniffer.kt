package com.example.data

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

internal object CharsetSniffer {
    fun detect(data:ByteArray, complete:Boolean=true):Charset {
        fun charset(name:String)=Charset.forName(name)
        if(data.size>=3 && data[0]==0xef.toByte() && data[1]==0xbb.toByte() && data[2]==0xbf.toByte()) return Charsets.UTF_8
        if(data.size>=2 && data[0]==0xff.toByte() && data[1]==0xfe.toByte()) return charset("UTF-16LE")
        if(data.size>=2 && data[0]==0xfe.toByte() && data[1]==0xff.toByte()) return charset("UTF-16BE")
        val sample=data.copyOf(minOf(data.size,65536))
        val pairs=sample.size/2
        if(pairs>8) {
            val even=(0 until pairs).count { sample[it*2]==0.toByte() }
            val odd=(0 until pairs).count { sample[it*2+1]==0.toByte() }
            if(odd>pairs/4 && even<pairs/10) return charset("UTF-16LE")
            if(even>pairs/4 && odd<pairs/10) return charset("UTF-16BE")
        }
        fun decode(cs:Charset):String? = runCatching {
            val decoder=cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val out=CharBuffer.allocate(sample.size+1)
            val result=decoder.decode(ByteBuffer.wrap(sample),out,complete && sample.size==data.size)
            if(result.isError) null else { out.flip(); out.toString() }
        }.getOrNull()
        if(decode(Charsets.UTF_8)!=null) return Charsets.UTF_8
        val frequent="的一是在不了有和人这中大为上个国我以要他时来用们生到作地于出就分对成会可主发年动同工也能下过子说产种面而方后多定行学法所民得经十三之进着等部度家电力里如水化高自二理起小物现实加量都两体制机当使点从业本去把性好应开它合还因由其些然前外天政四日那社义事平形相全表间样与关各重新线内数正心反你明看原又么利比或但质气第向道命此变条只结解问意建月公无系军很情者最立代想已通并提直题党程展五果料象员革位入常文总次品式活设及管特件长求老头基资边流路级少图山统接知较将组见计别她手角期根论运农指几九区强放决西被干做必战先回则任取据处队南给色光门即保治北造百规热领七海口东导器压志世金增争济阶油思术极交受联什认六共权收证改清美再采转更单风切打白教速花带安场身车例真务具万每目至达走积示议声报斗完类八离华名确才科张信马节话米整空元况今集温传土许步群广石记需段研界拉林律叫且究观越织装影算低持音众书布复容儿须际商非验连断深难近矿千周委素技备半办青省列习便响约支般史感劳团往酸历市克何除消构府称太准精值号率族维划选标写存候毛亲快效院查江型眼王按格养易置派层片始却专状育厂京识适属圆包火住调满县局照参红细引听该铁价严龙飞" + "國體學說這為個們時後會來發經書與關長開無話歡愛讀" 
        fun score(text:String):Double {
            val useful=text.filter { !it.isWhitespace() && it.code>127 }
            if(useful.isEmpty()) return 0.0
            val common=useful.count { it in frequent }.toDouble()/useful.length
            val kana=useful.count { it.code in 0x3040..0x30ff }.toDouble()/useful.length
            val hangul=useful.count { it.code in 0xac00..0xd7af }.toDouble()/useful.length
            val bad=useful.count { Character.isISOControl(it) || it.code in 0xe000..0xf8ff }.toDouble()/useful.length
            return common+kana*2.0+hangul*1.5-bad*5.0
        }
        val candidates=listOf("GB18030","Big5","Shift_JIS","EUC-KR").mapNotNull { name ->
            runCatching { val cs=charset(name); decode(cs)?.let { cs to score(it) } }.getOrNull()
        }
        return candidates.maxByOrNull { it.second }?.first ?: error("无法可靠识别文字编码，请将文件转换为 UTF-8")
    }
}

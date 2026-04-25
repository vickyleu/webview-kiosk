package uk.nktnet.webviewkiosk.utils.ime

import android.view.KeyEvent

data class ChineseCandidate(
    val text: String,
)

data class ChineseImeState(
    val enabled: Boolean = false,
    val composing: String = "",
    val candidates: List<ChineseCandidate> = emptyList(),
)

data class ChineseImeKeyResult(
    val consumed: Boolean,
    val state: ChineseImeState,
    val commitText: String? = null,
)

class ChineseImeController {
    var state: ChineseImeState = ChineseImeState()
        private set

    fun reset(): ChineseImeState {
        state = ChineseImeState()
        return state
    }

    fun closeComposition(): ChineseImeKeyResult {
        state = ChineseImeState()
        return ChineseImeKeyResult(consumed = true, state = state)
    }

    fun selectCandidate(index: Int): ChineseImeKeyResult {
        val text = state.candidates.getOrNull(index)?.text ?: return ChineseImeKeyResult(
            consumed = false,
            state = state
        )
        state = ChineseImeState(enabled = true)
        return ChineseImeKeyResult(consumed = true, state = state, commitText = text)
    }

    fun handleKeyEvent(event: KeyEvent): ChineseImeKeyResult {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return pass()
        }

        if (isToggleShortcut(event)) {
            state = if (state.enabled) {
                ChineseImeState()
            } else {
                ChineseImeState(enabled = true)
            }
            return ChineseImeKeyResult(consumed = true, state = state)
        }

        if (!state.enabled) {
            return pass()
        }

        if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) {
            return pass()
        }

        keyCodeToDigitIndex(event.keyCode)?.let { index ->
            if (state.composing.isNotEmpty()) {
                return selectCandidate(index)
            }
        }

        keyCodeToLetter(event.keyCode)?.let { letter ->
            updateComposing(state.composing + letter)
            return ChineseImeKeyResult(consumed = true, state = state)
        }

        return when (event.keyCode) {
            KeyEvent.KEYCODE_DEL -> handleDelete()
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER -> commitBestCandidate()
            KeyEvent.KEYCODE_ESCAPE -> closeComposition()
            else -> pass()
        }
    }

    private fun pass(): ChineseImeKeyResult = ChineseImeKeyResult(
        consumed = false,
        state = state
    )

    private fun handleDelete(): ChineseImeKeyResult {
        if (state.composing.isEmpty()) {
            return pass()
        }
        updateComposing(state.composing.dropLast(1))
        return ChineseImeKeyResult(consumed = true, state = state)
    }

    private fun commitBestCandidate(): ChineseImeKeyResult {
        if (state.composing.isEmpty()) {
            return pass()
        }
        val text = state.candidates.firstOrNull()?.text ?: state.composing
        state = ChineseImeState(enabled = true)
        return ChineseImeKeyResult(consumed = true, state = state, commitText = text)
    }

    private fun updateComposing(value: String) {
        state = state.copy(
            composing = value,
            candidates = lookupCandidates(value)
        )
    }

    private fun isToggleShortcut(event: KeyEvent): Boolean {
        return (
            event.keyCode == KeyEvent.KEYCODE_SPACE
            && event.isCtrlPressed
        ) || event.keyCode == KeyEvent.KEYCODE_LANGUAGE_SWITCH
            || event.keyCode == KeyEvent.KEYCODE_SWITCH_CHARSET
    }

    private fun keyCodeToLetter(keyCode: Int): Char? {
        if (keyCode !in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
            return null
        }
        return ('a'.code + keyCode - KeyEvent.KEYCODE_A).toChar()
    }

    private fun keyCodeToDigitIndex(keyCode: Int): Int? {
        return when (keyCode) {
            KeyEvent.KEYCODE_1,
            KeyEvent.KEYCODE_NUMPAD_1 -> 0
            KeyEvent.KEYCODE_2,
            KeyEvent.KEYCODE_NUMPAD_2 -> 1
            KeyEvent.KEYCODE_3,
            KeyEvent.KEYCODE_NUMPAD_3 -> 2
            KeyEvent.KEYCODE_4,
            KeyEvent.KEYCODE_NUMPAD_4 -> 3
            KeyEvent.KEYCODE_5,
            KeyEvent.KEYCODE_NUMPAD_5 -> 4
            KeyEvent.KEYCODE_6,
            KeyEvent.KEYCODE_NUMPAD_6 -> 5
            KeyEvent.KEYCODE_7,
            KeyEvent.KEYCODE_NUMPAD_7 -> 6
            KeyEvent.KEYCODE_8,
            KeyEvent.KEYCODE_NUMPAD_8 -> 7
            KeyEvent.KEYCODE_9,
            KeyEvent.KEYCODE_NUMPAD_9 -> 8
            KeyEvent.KEYCODE_0,
            KeyEvent.KEYCODE_NUMPAD_0 -> 9
            else -> null
        }
    }

    private fun lookupCandidates(rawInput: String): List<ChineseCandidate> {
        val input = rawInput.lowercase()
        if (input.isBlank()) {
            return emptyList()
        }

        val values = mutableListOf<String>()
        phraseCandidates[input]?.let(values::addAll)
        syllableCandidates[input]?.let(values::addAll)
        segmentToBestPhrase(input)?.let(values::add)
        return values
            .distinct()
            .take(10)
            .map(::ChineseCandidate)
    }

    private fun segmentToBestPhrase(input: String): String? {
        val memo = mutableMapOf<Int, List<String>?>()

        fun walk(index: Int): List<String>? {
            if (index == input.length) {
                return emptyList()
            }
            memo[index]?.let { return it }
            val best = syllableCandidates.keys
                .filter { input.startsWith(it, index) }
                .sortedByDescending(String::length)
                .asSequence()
                .mapNotNull { syllable ->
                    val rest = walk(index + syllable.length)
                    if (rest == null) null else listOf(syllable) + rest
                }
                .firstOrNull()
            memo[index] = best
            return best
        }

        val parts = walk(0) ?: return null
        if (parts.size <= 1) {
            return null
        }
        return parts.joinToString("") { syllable ->
            syllableCandidates.getValue(syllable).first()
        }
    }

    companion object {
        private val phraseCandidates = linkedMapOf(
            "woshi" to listOf("我是"),
            "woshizhongguoren" to listOf("我是中国人"),
            "zhongguo" to listOf("中国"),
            "zhongguoren" to listOf("中国人"),
            "dazilianxi" to listOf("打字练习"),
            "daziya" to listOf("打字鸭"),
            "keting" to listOf("客厅"),
            "dianshi" to listOf("电视"),
            "pingyin" to listOf("拼音"),
            "shenzhen" to listOf("深圳"),
            "haode" to listOf("好的"),
            "buyao" to listOf("不要"),
            "keyi" to listOf("可以"),
            "buxing" to listOf("不行"),
        )

        private val syllableCandidates = linkedMapOf(
            "a" to listOf("啊", "阿"),
            "ai" to listOf("爱", "矮", "挨"),
            "an" to listOf("安", "按", "案"),
            "ang" to listOf("昂"),
            "ao" to listOf("奥", "熬"),
            "ba" to listOf("把", "吧", "爸"),
            "bai" to listOf("白", "百", "败"),
            "ban" to listOf("办", "半", "班"),
            "bang" to listOf("帮", "棒"),
            "bao" to listOf("包", "保", "报"),
            "bei" to listOf("被", "北", "备"),
            "ben" to listOf("本", "笨"),
            "bi" to listOf("比", "必", "笔"),
            "bian" to listOf("边", "变", "便"),
            "biao" to listOf("表", "标"),
            "bie" to listOf("别"),
            "bin" to listOf("宾"),
            "bing" to listOf("并", "病", "冰"),
            "bo" to listOf("播", "波", "博"),
            "bu" to listOf("不", "部", "步"),
            "ca" to listOf("擦"),
            "cai" to listOf("才", "菜", "财"),
            "can" to listOf("看", "参", "餐"),
            "cao" to listOf("草"),
            "ce" to listOf("测", "侧"),
            "ceng" to listOf("层"),
            "cha" to listOf("查", "差", "茶"),
            "chai" to listOf("拆"),
            "chan" to listOf("产", "缠"),
            "chang" to listOf("长", "常", "场"),
            "chao" to listOf("超", "朝"),
            "che" to listOf("车", "撤"),
            "chen" to listOf("陈", "沉"),
            "cheng" to listOf("成", "城", "程"),
            "chi" to listOf("吃", "持"),
            "chong" to listOf("重", "冲"),
            "chu" to listOf("出", "楚", "处"),
            "chuan" to listOf("传", "船"),
            "chuang" to listOf("窗", "床"),
            "ci" to listOf("此", "次", "词"),
            "cong" to listOf("从", "聪"),
            "cuo" to listOf("错"),
            "da" to listOf("打", "大", "达"),
            "dai" to listOf("带", "待", "代"),
            "dan" to listOf("但", "单", "弹"),
            "dang" to listOf("当", "党"),
            "dao" to listOf("到", "道", "倒"),
            "de" to listOf("的", "得", "地"),
            "deng" to listOf("等", "灯"),
            "di" to listOf("第", "地", "低"),
            "dian" to listOf("点", "电", "店"),
            "diao" to listOf("掉", "调"),
            "die" to listOf("爹"),
            "ding" to listOf("定", "顶"),
            "dong" to listOf("动", "东", "懂"),
            "dou" to listOf("都", "斗"),
            "du" to listOf("读", "度", "堵"),
            "duan" to listOf("段", "短"),
            "dui" to listOf("对", "队"),
            "duo" to listOf("多", "躲"),
            "e" to listOf("额", "饿"),
            "en" to listOf("嗯"),
            "er" to listOf("二", "而", "儿"),
            "fa" to listOf("发", "法"),
            "fan" to listOf("反", "饭", "翻"),
            "fang" to listOf("方", "放", "房"),
            "fei" to listOf("飞", "非", "费"),
            "fen" to listOf("分", "份"),
            "feng" to listOf("风", "封"),
            "fo" to listOf("佛"),
            "fu" to listOf("服", "复", "父"),
            "gai" to listOf("该", "改"),
            "gan" to listOf("干", "感", "敢"),
            "gang" to listOf("刚", "港"),
            "gao" to listOf("高", "搞", "告"),
            "ge" to listOf("个", "哥", "各"),
            "gei" to listOf("给"),
            "gen" to listOf("跟", "根"),
            "geng" to listOf("更"),
            "gong" to listOf("工", "公", "功"),
            "gou" to listOf("够", "狗"),
            "gu" to listOf("古", "故", "谷"),
            "gua" to listOf("挂"),
            "guan" to listOf("关", "管", "观"),
            "guang" to listOf("光", "逛"),
            "gui" to listOf("贵", "归"),
            "guo" to listOf("国", "过", "果"),
            "ha" to listOf("哈"),
            "hai" to listOf("还", "海", "孩"),
            "han" to listOf("汉", "喊", "含"),
            "hao" to listOf("好", "号", "浩"),
            "he" to listOf("和", "喝", "何"),
            "hei" to listOf("黑"),
            "hen" to listOf("很", "狠"),
            "heng" to listOf("横"),
            "hong" to listOf("红", "洪"),
            "hou" to listOf("后", "候"),
            "hu" to listOf("户", "湖", "互"),
            "hua" to listOf("话", "花", "画"),
            "huai" to listOf("坏"),
            "huan" to listOf("换", "还", "环"),
            "huang" to listOf("黄", "慌"),
            "hui" to listOf("会", "回", "灰"),
            "hun" to listOf("混"),
            "huo" to listOf("或", "活", "火"),
            "ji" to listOf("机", "几", "记"),
            "jia" to listOf("家", "加", "假"),
            "jian" to listOf("见", "件", "间"),
            "jiang" to listOf("讲", "将", "江"),
            "jiao" to listOf("叫", "教", "交"),
            "jie" to listOf("接", "节", "解"),
            "jin" to listOf("进", "近", "金"),
            "jing" to listOf("经", "京", "静"),
            "jiu" to listOf("就", "九", "久"),
            "ju" to listOf("局", "句", "居"),
            "juan" to listOf("卷"),
            "jue" to listOf("觉", "决"),
            "kai" to listOf("开"),
            "kan" to listOf("看"),
            "kao" to listOf("考", "靠"),
            "ke" to listOf("可", "课", "客"),
            "ken" to listOf("肯"),
            "kong" to listOf("空", "控"),
            "kou" to listOf("口"),
            "kuai" to listOf("快"),
            "lai" to listOf("来"),
            "lan" to listOf("蓝", "栏"),
            "lao" to listOf("老"),
            "le" to listOf("了", "乐"),
            "lei" to listOf("类", "累"),
            "li" to listOf("里", "理", "力"),
            "lian" to listOf("练", "连", "脸"),
            "liang" to listOf("两", "亮"),
            "liao" to listOf("了", "聊"),
            "lie" to listOf("列"),
            "lin" to listOf("林"),
            "ling" to listOf("零", "另"),
            "liu" to listOf("六", "流"),
            "long" to listOf("龙"),
            "lou" to listOf("楼"),
            "lu" to listOf("路", "录"),
            "luan" to listOf("乱"),
            "ma" to listOf("吗", "妈", "马"),
            "mai" to listOf("买", "卖"),
            "man" to listOf("慢", "满"),
            "mang" to listOf("忙"),
            "mao" to listOf("猫", "毛"),
            "me" to listOf("么"),
            "mei" to listOf("没", "美"),
            "men" to listOf("们", "门"),
            "mi" to listOf("米"),
            "mian" to listOf("面", "免"),
            "ming" to listOf("名", "明"),
            "mo" to listOf("摸", "默"),
            "mu" to listOf("目", "木"),
            "na" to listOf("那", "拿"),
            "nai" to listOf("奶"),
            "nan" to listOf("难", "南"),
            "nao" to listOf("脑"),
            "ne" to listOf("呢"),
            "nei" to listOf("内"),
            "neng" to listOf("能"),
            "ni" to listOf("你"),
            "nian" to listOf("年", "念"),
            "niao" to listOf("鸟"),
            "nin" to listOf("您"),
            "niu" to listOf("牛"),
            "nong" to listOf("弄"),
            "nu" to listOf("怒"),
            "nuan" to listOf("暖"),
            "o" to listOf("哦"),
            "ou" to listOf("欧"),
            "pa" to listOf("怕", "爬"),
            "pai" to listOf("拍", "排"),
            "pan" to listOf("盘", "判"),
            "pao" to listOf("跑", "泡"),
            "pei" to listOf("配", "陪"),
            "peng" to listOf("朋"),
            "pi" to listOf("批", "皮"),
            "pian" to listOf("片", "篇"),
            "piao" to listOf("票", "飘"),
            "pin" to listOf("拼", "品"),
            "ping" to listOf("屏", "平"),
            "po" to listOf("破"),
            "pu" to listOf("普"),
            "qi" to listOf("起", "其", "气"),
            "qian" to listOf("前", "钱"),
            "qiang" to listOf("强"),
            "qiao" to listOf("桥", "巧"),
            "qie" to listOf("且", "切"),
            "qin" to listOf("亲"),
            "qing" to listOf("请", "清"),
            "qiu" to listOf("求"),
            "qu" to listOf("去", "区"),
            "quan" to listOf("全", "权"),
            "ran" to listOf("然"),
            "rang" to listOf("让"),
            "re" to listOf("热"),
            "ren" to listOf("人", "认", "任"),
            "ri" to listOf("日"),
            "rong" to listOf("容"),
            "ru" to listOf("入"),
            "ruan" to listOf("软"),
            "san" to listOf("三"),
            "se" to listOf("色"),
            "sha" to listOf("啥", "沙"),
            "shan" to listOf("山", "删"),
            "shang" to listOf("上", "商"),
            "shao" to listOf("少", "烧"),
            "she" to listOf("设", "社"),
            "shen" to listOf("深", "身"),
            "sheng" to listOf("声", "生"),
            "shi" to listOf("是", "时", "十", "事", "师"),
            "shou" to listOf("手", "收"),
            "shu" to listOf("书", "输", "数"),
            "shua" to listOf("刷"),
            "shuang" to listOf("双"),
            "shui" to listOf("水", "谁"),
            "shuo" to listOf("说"),
            "si" to listOf("四", "思"),
            "song" to listOf("送"),
            "sou" to listOf("搜"),
            "su" to listOf("速"),
            "suan" to listOf("算"),
            "ta" to listOf("他", "她", "它"),
            "tai" to listOf("太", "台"),
            "tan" to listOf("弹", "谈"),
            "tang" to listOf("糖", "唐"),
            "tao" to listOf("套"),
            "te" to listOf("特"),
            "teng" to listOf("疼"),
            "ti" to listOf("体", "题"),
            "tian" to listOf("天", "填"),
            "tiao" to listOf("条", "跳"),
            "tie" to listOf("贴"),
            "ting" to listOf("听", "厅"),
            "tong" to listOf("同", "通"),
            "tou" to listOf("头"),
            "tu" to listOf("图"),
            "tui" to listOf("退"),
            "wa" to listOf("哇"),
            "wai" to listOf("外"),
            "wan" to listOf("完", "万"),
            "wang" to listOf("网", "王"),
            "wei" to listOf("为", "位", "未"),
            "wen" to listOf("问", "文"),
            "wo" to listOf("我", "窝", "握"),
            "wu" to listOf("五", "无", "屋"),
            "xi" to listOf("西", "习", "洗"),
            "xia" to listOf("下", "吓"),
            "xian" to listOf("先", "现", "线"),
            "xiang" to listOf("想", "向", "像"),
            "xiao" to listOf("小", "笑"),
            "xie" to listOf("写", "些", "谢"),
            "xin" to listOf("新", "心"),
            "xing" to listOf("行", "星"),
            "xiong" to listOf("兄"),
            "xiu" to listOf("修"),
            "xu" to listOf("需", "许"),
            "xuan" to listOf("选"),
            "xue" to listOf("学"),
            "ya" to listOf("鸭", "呀"),
            "yan" to listOf("眼", "言"),
            "yang" to listOf("样", "养"),
            "yao" to listOf("要", "摇"),
            "ye" to listOf("也", "页"),
            "yi" to listOf("一", "以", "已"),
            "yin" to listOf("音", "因"),
            "ying" to listOf("应", "英"),
            "yong" to listOf("用", "永"),
            "you" to listOf("有", "又", "右"),
            "yu" to listOf("语", "与", "雨"),
            "yuan" to listOf("远", "原"),
            "yue" to listOf("月", "越"),
            "yun" to listOf("云", "运"),
            "za" to listOf("咋"),
            "zai" to listOf("在", "再"),
            "zan" to listOf("咱"),
            "zao" to listOf("早"),
            "ze" to listOf("则"),
            "zen" to listOf("怎"),
            "zeng" to listOf("增"),
            "zha" to listOf("炸"),
            "zhai" to listOf("摘"),
            "zhan" to listOf("站", "占"),
            "zhang" to listOf("张", "章"),
            "zhao" to listOf("找", "照"),
            "zhe" to listOf("这", "者"),
            "zhen" to listOf("真", "阵"),
            "zheng" to listOf("正", "整"),
            "zhi" to listOf("只", "知", "直"),
            "zhong" to listOf("中", "种", "重"),
            "zhou" to listOf("周"),
            "zhu" to listOf("主", "住"),
            "zhuan" to listOf("转"),
            "zi" to listOf("字", "自", "子"),
            "zou" to listOf("走"),
            "zuo" to listOf("做", "坐", "左"),
        )
    }
}

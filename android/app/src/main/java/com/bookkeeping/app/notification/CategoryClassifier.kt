package com.bookkeeping.app.notification

import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.merchant.MerchantRules

// 自动分类器：根据商户名 + 通知/短信文本，推测 TransactionCategory。
// 商户字段优先；同一字段内最长关键词优先，分类顺序只做同长度兜底。
// 都不命中返回 OTHER。
//
// 嵌入式类比：一个查找表（lookup table），关键词进，分类出。
// 后续可演进为：用户手动改分类后记住"商户→分类"映射，下次自动用。
object CategoryClassifier {

    // 关键词分组。匹配时先看商户、再看上下文；同一字段内最长关键词优先，
    // 分类顺序只在关键词等长时兜底。
    private val RULES: List<Pair<List<String>, TransactionCategory>> = listOf(
        // 工资 / 收入相关（优先匹配，避免被其他分类抢走）
        listOf("工资", "薪资", "薪水", "代发", "津贴", "年终") to TransactionCategory.SALARY,

        // 餐饮（咖啡 / 茶饮 / 快餐 / 中餐 / 外卖）
        listOf(
            "星巴克", "瑞幸", "Costa", "Tims", "Peet",
            "茶颜悦色", "喜茶", "奈雪", "蜜雪冰城", "霸王茶姬", "古茗", "茶百道", "沪上阿姨", "CoCo",
            "麦当劳", "肯德基", "KFC", "汉堡王", "Burger", "必胜客", "赛百味", "塔斯汀",
            "海底捞", "西贝", "外婆家", "呷哺", "南城香", "和府捞面",
            "华莱士", "德克士", "真功夫", "老乡鸡", "乡村基", "大米先生",
            "杨国福", "张亮麻辣烫", "觅姐麻辣烫", "袁记云饺", "喜家德",
            "遇见小面", "李先生", "吉野家", "萨莉亚",
            "绝味鸭脖", "周黑鸭", "煌上煌", "紫燕百味鸡", "正新鸡排",
            "书亦烧仙草", "益禾堂", "甜啦啦", "柠季", "7分甜",
            "好利来", "鲍师傅", "泸溪河", "良品铺子", "三只松鼠", "零食很忙",
            "小龙坎", "巴奴", "太二酸菜鱼", "木屋烧烤", "阿香米线",
            "奈雪的茶", "瑞幸咖啡",
            "库迪咖啡", "Manner", "幸运咖", "挪瓦咖啡",
            "美团外卖", "美团", "饿了么", "盒马", "叮咚",
            "餐厅", "酒楼", "饭店", "茶饮", "烧烤", "火锅", "面馆", "餐饮",
            "采蝶轩", "烤鸡", "鸡腿", "炒饭", "食堂", "水果", "零食", "赵一鸣",
            "炸鸡", "luckin", "咖啡", "羊肉粉", "烧柠",
        ) to TransactionCategory.FOOD,

        // 交通
        listOf(
            "滴滴", "高德打车", "美团打车", "花小猪", "T3", "曹操", "首汽", "出租车", "网约车",
            "享道出行", "如祺出行", "嘀嗒出行", "哈啰顺风车", "高德地图",
            "同程旅行", "春秋航空", "特来电", "星星充电", "途虎养车",
            "12306", "高铁", "动车", "火车", "铁路",
            "航空", "机票", "民航", "南航", "国航", "东航", "海航",
            "地铁", "公交", "公共交通",
            "ETC", "停车", "加油", "中石化", "中石油", "充电站", "充电",
            "广东联合电子",
            "共享单车", "哈啰", "美团单车", "青桔",
        ) to TransactionCategory.TRANSPORT,

        // 购物（电商 / 便利店 / 商超 / 数码）
        listOf(
            "淘宝", "天猫", "京东", "拼多多", "唯品会", "1688", "考拉",
            "抖音商城", "多多买菜", "美团优选", "小红书", "得物", "闲鱼", "转转",
            "名创优品", "屈臣氏", "万宁", "天福", "芙蓉兴盛", "红旗连锁",
            "十足", "快客", "好德", "可的", "华润万家", "物美", "大润发", "苏宁", "国美",
            "胖东来", "孩子王", "红星美凯龙", "万达广场", "王府井", "银泰", "迪卡侬",
            "苹果", "Apple", "华为", "小米", "OPPO", "vivo", "荣耀",
            "Nike", "Adidas", "优衣库", "Uniqlo", "无印良品", "MUJI", "宜家", "IKEA", "网易严选",
            "7-11", "7-Eleven", "全家", "FamilyMart", "罗森", "Lawson", "便利蜂", "美宜佳", "便利店",
            "永辉", "沃尔玛", "Walmart", "Costco", "山姆", "家乐福", "超市",
            "印象小店", "宠物用品", "快印", "生鲜", "商场", "丰巢",
        ) to TransactionCategory.SHOPPING,

        // 水电 / 公用事业。放在娱乐的通用“充值”之前，避免“中国电信 充值”
        // 被误判为游戏充值。
        listOf("水费", "电费", "燃气费", "燃气", "热力", "网费", "宽带", "话费", "流量充值",
            "中国移动", "中国联通", "中国电信", "中国广电", "自来充",
            "国家电网", "南方电网", "网上国网", "中国燃气", "华润燃气", "港华燃气", "新奥燃气",
            "北京燃气", "上海燃气", "深圳燃气", "北控水务", "首创环保", "深圳水务",
            "广州自来水", "支付宝生活缴费", "微信生活缴费", "暖气费", "水务") to TransactionCategory.UTILITIES,

        // 娱乐 / 数字订阅
        listOf(
            "Steam", "Epic", "PlayStation", "PSN", "Switch", "任天堂", "Xbox",
            "腾讯视频", "爱奇艺", "优酷", "芒果TV", "Netflix", "Disney", "HBO", "Spotify", "Apple Music", "网易云",
            "QQ音乐", "酷狗音乐", "喜马拉雅",
            "电影院", "万达", "CGV", "UME", "大地影院", "猫眼", "淘票票", "大麦",
            "横店影视", "金逸影城", "博纳影城", "秀动",
            "携程", "飞猪", "去哪儿",
            "B站", "bilibili", "哔哩哔哩", "抖音", "快手", "番茄小说", "微信读书",
            "KTV", "唱吧", "游戏", "氪金", "充值", "米哈游", "腾讯游戏", "网易游戏", "完美世界",
            "王者荣耀", "和平精英", "原神", "剧本杀", "密室逃脱",
            "迪士尼", "环球影城", "欢乐谷", "方特", "长隆", "酒店", "民宿", "旅游", "门票",
            "游卡", "桌游", "网吧",
        ) to TransactionCategory.ENTERTAINMENT,

        // 住房
        listOf("房租", "租金", "物业", "中介费", "押金", "二房东",
            "贝壳找房", "链家", "自如", "我爱我家", "相寓", "泊寓", "冠寓", "魔方公寓",
            "万科", "保利", "中海地产", "招商蛇口", "金地", "龙湖", "绿城", "华润置地",
            "恒大", "碧桂园", "融创", "装修", "土巴兔", "齐家网", "业之峰",
            "东易日盛", "金螳螂", "安居客") to TransactionCategory.HOUSING,

        // 医疗
        listOf("医院", "药店", "药房", "挂号", "诊所", "体检", "口腔", "牙科", "眼科", "医药",
            "美团买药", "叮当快药", "医保", "京东健康", "阿里健康", "平安好医生", "微医",
            "国大药房", "老百姓大药房", "益丰药房", "一心堂", "大参林", "海王星辰",
            "健之佳", "漱玉平民", "同仁堂", "云南白药", "片仔癀", "美年大健康", "爱康国宾",
            "慈铭体检", "爱尔眼科", "通策医疗", "拜博口腔", "瑞尔齿科", "美莱", "新氧",
            "医美", "保健品") to TransactionCategory.HEALTH,

        // 教育
        listOf("学费", "培训", "课程", "网课", "得到", "极客时间", "教材", "书店", "当当", "Kindle", "Audible",
            "大学", "背单词", "墨墨", "新东方", "好未来", "学而思", "作业帮", "猿辅导",
            "粉笔", "中公教育", "华图教育", "网易云课堂", "腾讯课堂", "慕课",
            "高途", "网易有道", "学大教育", "昂立教育", "卓越教育", "VIPKID", "51Talk",
            "流利说", "多邻国", "樊登读书", "中国大学MOOC", "学堂在线", "超星学习通",
            "知网", "新航道", "环球教育", "启德教育", "金吉列", "考试费") to TransactionCategory.EDUCATION,

        // 奖金 / 理财 / 保险 / 礼金
        listOf("奖金", "年终奖", "绩效奖", "绩效", "提成", "补贴", "报销", "退税",
            "生育津贴", "失业金", "养老金", "兼职", "稿费", "佣金", "返现", "中奖", "赔偿") to
            TransactionCategory.BONUS,
        listOf("理财", "基金", "股票", "证券", "利息", "收益", "余额宝", "零钱通",
            "蚂蚁财富", "天天基金", "蛋卷基金", "且慢", "招商银行理财", "平安证券",
            "华泰证券", "中信证券", "国泰君安", "东方财富", "同花顺", "雪球",
            "涨乐财富通", "广发证券", "银河证券", "国信证券", "兴业证券", "海通证券",
            "申万宏源", "中金公司", "中信建投", "富途牛牛", "老虎证券", "长桥",
            "盈透证券", "债券", "黄金", "存款", "股息", "分红", "定投") to
            TransactionCategory.INVESTMENT,
        listOf("保险", "保费", "重疾险", "医疗险", "中国平安", "中国人寿", "中国人保",
            "太平洋保险", "新华保险", "泰康保险", "太平保险", "阳光保险", "众安保险",
            "水滴保", "蚂蚁保", "微保", "平安产险", "人保财险", "国寿财险",
            "车险", "意外险", "寿险", "养老保险", "好医保", "蓝医保", "惠民保", "理赔") to
            TransactionCategory.INSURANCE,
        listOf("礼金", "份子钱", "随礼", "礼物", "礼品", "花点时间", "野兽派", "roseonly",
            "21cake", "幸福西饼", "礼物说", "京东礼品卡", "星巴克礼品卡", "苹果礼品卡",
            "伴手礼", "压岁钱", "彩礼", "乔迁", "满月", "升学") to TransactionCategory.GIFT,

        // 转账 / 红包 / 提现
        listOf("转账", "转入", "转出", "汇款", "提现", "红包", "发给", "还款") to TransactionCategory.TRANSFER,
    )

    private data class Match(
        val category: TransactionCategory,
        val keyword: String,
        val keywordLength: Int,
        val order: Int,
    )

    private data class RuleMatch(val rule: CategoryRule, val keywordLength: Int)

    private val SPECIFIC_RULES: List<CategoryRule> = listOf(
        CategoryRule(
            id = "health:medicine",
            stage = RuleStage.DEFAULT,
            specificity = 130,
            category = TransactionCategory.HEALTH,
            merchantKeywords = listOf("美团买药", "饿了么买药", "京东买药", "京东健康", "阿里健康", "叮当快药"),
            contextKeywords = listOf("买药", "处方药", "药品"),
            reason = "specific medicine phrase",
        ),
        CategoryRule(
            id = "utilities:topup",
            stage = RuleStage.DEFAULT,
            specificity = 125,
            category = TransactionCategory.UTILITIES,
            contextKeywords = listOf("话费充值", "流量充值", "燃气充值", "电费充值", "水费充值", "固话充值"),
            reason = "specific utility top-up phrase",
        ),
        CategoryRule(
            id = "transport:transit-card-topup",
            stage = RuleStage.DEFAULT,
            specificity = 125,
            category = TransactionCategory.TRANSPORT,
            contextKeywords = listOf("公交卡充值", "地铁卡充值", "交通卡充值"),
            reason = "transit card top-up",
        ),
        CategoryRule(
            id = "entertainment:apple-media",
            stage = RuleStage.DEFAULT,
            specificity = 120,
            category = TransactionCategory.ENTERTAINMENT,
            contextKeywords = listOf("apple music", "apple tv", "apple one", "icloud+"),
            reason = "apple media subscription",
        ),
        CategoryRule(
            id = "entertainment:wanda-cinema",
            stage = RuleStage.DEFAULT,
            specificity = 120,
            category = TransactionCategory.ENTERTAINMENT,
            merchantKeywords = listOf("万达影城", "万达电影"),
            reason = "cinema, not shopping mall",
        ),
        CategoryRule(
            id = "shopping:wanda-plaza",
            stage = RuleStage.DEFAULT,
            specificity = 115,
            category = TransactionCategory.SHOPPING,
            merchantKeywords = listOf("万达广场"),
            reason = "shopping mall",
        ),
        CategoryRule(
            id = "food:xiaomi-porridge",
            stage = RuleStage.DEFAULT,
            specificity = 120,
            category = TransactionCategory.FOOD,
            merchantKeywords = listOf("小米粥", "小米椒"),
            reason = "food, not Xiaomi brand",
        ),
        CategoryRule(
            id = "food:kfc",
            stage = RuleStage.DEFAULT,
            specificity = 110,
            category = TransactionCategory.FOOD,
            merchantKeywords = listOf("KFC", "肯德基"),
            reason = "KFC brand",
        ),
        CategoryRule(
            id = "shopping:meituan-grocery",
            stage = RuleStage.DEFAULT,
            specificity = 110,
            category = TransactionCategory.SHOPPING,
            merchantKeywords = listOf("美团买菜", "美团优选"),
            reason = "grocery, not restaurant",
        ),
        CategoryRule(
            id = "health:pharmacy",
            stage = RuleStage.DEFAULT,
            specificity = 100,
            category = TransactionCategory.HEALTH,
            contextKeywords = listOf("药店", "药房"),
            reason = "pharmacy",
        ),
        CategoryRule(
            id = "housing:rent",
            stage = RuleStage.DEFAULT,
            specificity = 100,
            category = TransactionCategory.HOUSING,
            contextKeywords = listOf("房租", "物业费", "租金"),
            reason = "housing",
        ),
        CategoryRule(
            id = "education:tuition",
            stage = RuleStage.DEFAULT,
            specificity = 100,
            category = TransactionCategory.EDUCATION,
            contextKeywords = listOf("学费", "培训费", "课程费"),
            reason = "education",
        ),
        CategoryRule(
            id = "insurance:premium",
            stage = RuleStage.DEFAULT,
            specificity = 100,
            category = TransactionCategory.INSURANCE,
            contextKeywords = listOf("保费", "保险"),
            reason = "insurance",
        ),
        CategoryRule(
            id = "gift:gift-money",
            stage = RuleStage.DEFAULT,
            specificity = 100,
            category = TransactionCategory.GIFT,
            merchantKeywords = listOf("礼金", "份子钱", "随礼"),
            contextKeywords = listOf("礼金", "份子钱", "随礼"),
            reason = "gift money",
        ),
        CategoryRule(
            id = "transfer:repayment",
            stage = RuleStage.POST,
            specificity = 100,
            category = TransactionCategory.TRANSFER,
            contextKeywords = listOf("信用卡还款", "还款"),
            reason = "credit-card repayment",
        ),
    )

    private val SOURCE_CATEGORY_MAP: List<Pair<String, TransactionCategory>> = listOf(
        "餐饮" to TransactionCategory.FOOD,
        "交通" to TransactionCategory.TRANSPORT,
        "日用百货" to TransactionCategory.SHOPPING,
        "文化休闲" to TransactionCategory.ENTERTAINMENT,
        "住房" to TransactionCategory.HOUSING,
        "通讯" to TransactionCategory.UTILITIES,
        "医疗" to TransactionCategory.HEALTH,
        "教育" to TransactionCategory.EDUCATION,
        "投资" to TransactionCategory.INVESTMENT,
        "信用" to TransactionCategory.TRANSFER,
        "转账" to TransactionCategory.TRANSFER,
        "账户" to TransactionCategory.TRANSFER,
        "退款" to TransactionCategory.OTHER,
    )

    /**
     * Classify a structured source event. This is the entry point used by all
     * capture paths; [classify] below remains as a compatibility overload.
     */
    fun classify(input: ClassificationInput): ClassificationResult {
        postClassification(input)?.let { return it }
        userRuleClassification(input)?.let { return it }
        learnedClassification(input)?.let { return it }
        sourceCategoryClassification(input)?.let { return it }
        specificRuleClassification(input)?.let { return it }

        val merchant = MerchantNormalizer.normalize(input.merchantRaw)
        val fromMerchant = bestMatch(merchant)
        val fromContext = bestMatch(input.contextText.take(4_000))
        val category = when {
            MerchantRules.categoryFor(input.merchantRaw) != null ->
                MerchantRules.categoryFor(input.merchantRaw)!!
            fromMerchant == null -> fromContext?.category ?: TransactionCategory.OTHER
            fromContext == null -> fromMerchant.category
            fromContext.keywordLength > fromMerchant.keywordLength &&
                input.contextText.contains(fromMerchant.keyword, ignoreCase = true) ->
                fromContext.category
            else -> fromMerchant.category
        }
        val userRule = MerchantRules.categoryFor(input.merchantRaw) != null
        val confidence = when {
            userRule -> 0.99f
            category == TransactionCategory.OTHER -> 0f
            else -> 0.85f
        }
        val alternatives = listOfNotNull(fromMerchant?.category, fromContext?.category)
            .distinct()
            .filter { it != category }
            .take(2)
            .map { it to 0.75f }
        return ClassificationResult(
            category = category,
            confidence = confidence,
            ruleId = if (userRule) "user:${MerchantRules.normalize(input.merchantRaw)}" else "keyword:${category.name}",
            reason = if (userRule) "user merchant rule" else "keyword match",
            alternatives = alternatives,
        )
    }

    private fun postClassification(input: ClassificationInput): ClassificationResult? = when (input.kind) {
        ClassificationKind.REFUND -> ClassificationResult(
            category = TransactionCategory.OTHER,
            confidence = 0.95f,
            ruleId = "post:refund",
            reason = "refund is settled by refund status, not consumption category",
        )
        ClassificationKind.TRANSFER,
        ClassificationKind.WITHDRAWAL -> ClassificationResult(
            category = TransactionCategory.TRANSFER,
            confidence = 0.95f,
            ruleId = "post:transfer",
            reason = "internal money movement",
        )
        ClassificationKind.RED_PACKET -> ClassificationResult(
            category = if (input.direction == ClassificationDirection.INCOME) {
                TransactionCategory.GIFT
            } else {
                TransactionCategory.TRANSFER
            },
            confidence = 0.95f,
            ruleId = "post:red-packet",
            reason = "red packet",
        )
        else -> null
    }

    private fun userRuleClassification(input: ClassificationInput): ClassificationResult? {
        val category = MerchantRules.categoryFor(input.merchantRaw) ?: return null
        return ClassificationResult(
            category = category,
            confidence = 0.99f,
            ruleId = "user:${MerchantRules.normalize(input.merchantRaw)}",
            reason = "user merchant rule",
        )
    }

    private fun learnedClassification(input: ClassificationInput): ClassificationResult? {
        val category = CategoryLearning.bestCategory(input.merchantRaw) ?: return null
        return ClassificationResult(
            category = category,
            confidence = 0.95f,
            ruleId = "learned:${MerchantRules.normalize(input.merchantRaw)}",
            reason = "learned from repeated user corrections",
        )
    }

    private fun sourceCategoryClassification(input: ClassificationInput): ClassificationResult? {
        val raw = input.sourceCategory?.trim().orEmpty()
        if (raw.isBlank()) return null
        val match = SOURCE_CATEGORY_MAP.firstOrNull { raw.contains(it.first) } ?: return null
        return ClassificationResult(
            category = match.second,
            confidence = 0.9f,
            ruleId = "source-category:${match.first}",
            reason = "official source category",
        )
    }

    private fun specificRuleClassification(input: ClassificationInput): ClassificationResult? {
        val merchant = MerchantNormalizer.normalize(input.merchantRaw)
        val context = input.contextText
        val match = SPECIFIC_RULES
            .asSequence()
            .filter { rule -> rule.matches(input, merchant, context) }
            .map { rule -> RuleMatch(rule, rule.keywordLength(merchant, context)) }
            .maxWithOrNull(compareBy<RuleMatch> { it.rule.specificity }.thenBy { it.keywordLength })
            ?: return null
        return ClassificationResult(
            category = match.rule.category,
            confidence = 0.95f,
            ruleId = match.rule.id,
            reason = match.rule.reason,
        )
    }

    private fun CategoryRule.matches(
        input: ClassificationInput,
        merchant: String,
        context: String,
    ): Boolean {
        if (directions.isNotEmpty() && input.direction !in directions) return false
        if (kinds.isNotEmpty() && input.kind !in kinds) return false
        if (sourceCategories.isNotEmpty() &&
            sourceCategories.none { input.sourceCategory?.contains(it, ignoreCase = true) == true }
        ) {
            return false
        }
        val merchantHit = merchantKeywords.any { merchant.contains(it.lowercase()) }
        val contextHit = contextKeywords.any { context.contains(it, ignoreCase = true) }
        return merchantHit || contextHit
    }

    private fun CategoryRule.keywordLength(merchant: String, context: String): Int =
        (merchantKeywords.filter { merchant.contains(it.lowercase()) } +
            contextKeywords.filter { context.contains(it, ignoreCase = true) })
            .maxOfOrNull { it.length }
            ?: 0

    fun classify(merchant: String, contextText: String = ""): TransactionCategory {
        MerchantRules.categoryFor(merchant)?.let { return it }
        val merchantMatch = bestMatch(merchant.take(4_000))
        val contextMatch = bestMatch(contextText.take(4_000))
        if (merchantMatch == null) {
            return contextMatch?.category ?: TransactionCategory.OTHER
        }
        if (contextMatch == null) return merchantMatch.category
        return if (contextMatch.keywordLength > merchantMatch.keywordLength &&
            contextText.contains(merchantMatch.keyword, ignoreCase = true)
        ) {
            contextMatch.category
        } else {
            merchantMatch.category
        }
    }

    /**
     * 表单预填用的「商户记忆」：用户显式规则 > 学到的纠正 > 内置关键词。
     *
     * 直接复用 [classify] 里那条现成的管道（[ClassificationInput] 版本），不另写优先级：
     * `post` 之外依次是 user rule → learned → source category → specific rule → keyword。
     *
     * 和 [classify] 的区别只有一个：**没有任何命中时返回 null**，而不是兜底成
     * [TransactionCategory.OTHER]。调用方（语音预填）据此保留解析器自己的猜测，
     * 不会被一句「记忆里没有」改成「其他」。
     */
    fun rememberedCategory(merchant: String, contextText: String = ""): TransactionCategory? {
        if (merchant.isBlank()) return null
        val result = classify(
            ClassificationInput(
                merchantRaw = merchant,
                contextText = contextText,
                direction = ClassificationDirection.EXPENSE,
                source = ClassificationSource.MANUAL,
                kind = ClassificationKind.PAYMENT,
            ),
        )
        // 无命中时管道给的是 OTHER + confidence 0；显式规则/学习/关键词都 > 0。
        return result.category.takeIf { result.confidence > 0f }
    }

    private fun bestMatch(text: String): Match? {
        if (text.isBlank()) return null
        var best: Match? = null
        RULES.forEachIndexed { order, (keywords, category) ->
            for (keyword in keywords) {
                if (!text.contains(keyword, ignoreCase = true)) continue
                // A coupon mentioning "红包" is not a red packet transfer.
                if (keyword == "红包" && isPromotionalRedPacket(text)) continue
                // "基金会/公益基金" is a charity name, not an investment.
                if (keyword == "基金" &&
                    (text.contains("基金会") || text.contains("公益基金"))
                ) continue
                val candidate = Match(category, keyword, keyword.length, order)
                if (best == null ||
                    candidate.keywordLength > best.keywordLength ||
                    (candidate.keywordLength == best.keywordLength && candidate.order < best.order)
                ) {
                    best = candidate
                }
            }
        }
        return best
    }

    private fun isPromotionalRedPacket(text: String): Boolean =
        listOf("红包抵扣", "红包优惠", "红包封面", "红包皮肤", "红包表情")
            .any { text.contains(it, ignoreCase = true) }
}

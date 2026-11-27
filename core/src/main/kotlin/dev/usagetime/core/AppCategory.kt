package dev.usagetime.core

enum class AppCategory(val label: String) {
    STUDY("学习"),
    WORK("工作"),
    SOCIAL("社交"),
    VIDEO("影音"),
    GAME("游戏"),
    BROWSE("浏览"),
    LIFE("生活"),
    TOOLS("工具"),
    OTHER("其他");

    companion object {
        // Exact Android package IDs work for installed, uninstalled and historically recorded apps.
        val defaults: Map<String, AppCategory> = buildMap {
            fun register(category: AppCategory, vararg packages: String) {
                packages.forEach { put(it, category) }
            }
            register(
                STUDY,
                "app.van.timetable", "cn.com.chsi.chsiapp", "com.chaoxing.mobile",
                "com.duolingo", "com.google.android.apps.classroom", "com.haooz.chedule",
                "com.huawei.edu.petal", "com.netease.edu.ucmooc", "com.suda.yzune.wakeupschedule",
                "com.youdao.dict", "com.zmzx.college.search", "io.studyguard.app", "org.khanacademy.android"
            )
            register(
                WORK,
                "cn.wps.moffice_eng", "com.alibaba.android.rimet", "com.google.android.apps.docs",
                "com.google.android.apps.docs.editors.docs", "com.google.android.apps.docs.editors.sheets",
                "com.google.android.apps.docs.editors.slides", "com.google.android.calendar",
                "com.google.android.gm", "com.larksuite.suite", "com.microsoft.office.excel",
                "com.microsoft.office.onenote", "com.microsoft.office.outlook", "com.microsoft.office.powerpoint",
                "com.microsoft.office.word", "com.microsoft.teams", "com.netease.mobimail",
                "com.slack", "com.ss.android.lark", "com.tencent.androidqqmail", "com.tencent.wemeet.app",
                "com.tencent.wework", "com.vivo.email", "com.vivo.smartoffice", "notion.id", "us.zoom.videomeetings"
            )
            register(
                SOCIAL,
                "com.android.mms", "com.baidu.tieba", "com.discord", "com.facebook.katana",
                "com.facebook.orca", "com.google.android.apps.messaging", "com.instagram.android",
                "com.instagram.barcelona", "com.kakao.talk", "com.reddit.frontpage", "com.sina.weibo",
                "com.snapchat.android", "com.tencent.mm", "com.tencent.mobileqq", "com.twitter.android",
                "com.whatsapp", "com.whatsapp.w4b", "com.xiaoshen.pakeplus.android", "com.xingin.xhs",
                "ink.zlb.app", "jp.naver.line.android", "org.thoughtcrime.securesms", "org.telegram.messenger"
            )
            register(
                VIDEO,
                "cn.wenyu.bodian", "com.android.VideoPlayer", "com.example.piliplus",
                "com.google.android.apps.youtube.music", "com.google.android.youtube", "com.kugou.android",
                "com.kwai.video", "com.netease.cloudmusic", "com.netflix.mediaclient", "com.pandora.android",
                "com.qiyi.video", "com.smile.gifmaker", "com.spotify.music", "com.ss.android.ugc.aweme",
                "com.ss.android.ugc.aweme.lite", "com.tencent.qqmusic", "com.tencent.qqlive",
                "com.youku.phone", "com.zhiliaoapp.musically", "org.videolan.vlc", "tv.danmaku.bili", "tv.twitch.android.app"
            )
            register(
                GAME,
                "com.HoYoverse.hkrpgoversea", "com.activision.callofduty.shooter",
                "com.garena.game.kgth", "com.hypergryph.arknights",
                "com.max.xiaoheihe", "com.miHoYo.GenshinImpact", "com.miHoYo.Yuanshen", "com.miHoYo.hkrpg",
                "com.mihoyo.hyperion", "com.mojang.minecraftpe", "com.netease.party", "com.roblox.client",
                "com.supercell.brawlstars", "com.supercell.clashofclans", "com.supercell.clashroyale",
                "com.taptap", "com.tencent.gamehelper.smoba", "com.tencent.ig",
                "com.tencent.tmgp.pubgmhd", "com.tencent.tmgp.sgame", "com.vivo.minigamecenter"
            )
            register(
                BROWSE,
                "com.amazon.kindle", "com.android.browser", "com.android.chrome", "com.baidu.searchbox",
                "com.brave.browser", "com.chrome.beta", "com.dragon.read", "com.google.android.apps.books",
                "com.google.android.googlequicksearchbox", "com.jiaohua_browser", "com.kiwibrowser.browser",
                "com.microsoft.emmx", "com.opera.browser", "com.opera.mini.native", "com.qidian.QDReader",
                "com.quark.browser", "com.sec.android.app.sbrowser", "com.sfacg", "com.tencent.mtt",
                "com.tencent.weread", "com.UCMobile", "com.vivo.browser", "com.vivo.browser.novel.widget",
                "com.xjs.ehviewer", "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.focus"
            )
            register(
                LIFE,
                "com.alibaba.aliexpresshd", "com.amazon.mShop.android.shopping", "com.android.bankabc",
                "com.autonavi.minimap", "com.baidu.BaiduMap", "com.booking", "com.chinamworld.bocmbci",
                "com.cmbchina.ccd.pluto.cmbActivity", "com.ct.client", "com.ebay.mobile",
                "com.ecitic.bank.mobile", "com.einnovation.temu", "com.eg.android.AlipayGphone",
                "com.google.android.apps.maps", "com.google.android.apps.walletnfcrel",
                "com.greenpoint.android.mc10086.activity", "com.hexin.plat.android", "com.icbc",
                "com.jd.jrapp", "com.jingdong.app.mall", "com.midea.vm.washer", "com.qunar",
                "com.sankuai.meituan", "com.sankuai.meituan.takeoutnew", "com.sdu.didi.psnger",
                "com.shizhuang.duapp", "com.taobao.idlefish", "com.taobao.taobao", "com.tencent.map",
                "com.ubercab", "com.ubercab.eats", "com.unionpay", "com.vivo.health", "com.vivo.lifeservice",
                "com.vivo.space", "com.vivo.vhome", "com.vivo.wallet", "com.vivo.weather", "com.waze",
                "com.wisentsoft.chinapost.android", "com.xiaomi.smarthome", "com.xiaomi.wearable",
                "com.xunmeng.pinduoduo", "com.mi.health", "com.zzw.october", "ctrip.android.view"
            )
            register(
                TOOLS,
                "android", "com.aliyun.tongyi", "com.android.BBKClock", "com.android.apps.tag",
                "com.android.appsearch.aiseal.config", "com.android.bbkcalculator", "com.android.bbksoundrecorder",
                "com.android.calculator2", "com.android.camera", "com.android.contacts", "com.android.contactspicker",
                "com.android.deskclock", "com.android.dialer", "com.android.documentsui", "com.android.eyedropper",
                "com.android.filemanager", "com.android.healthconnect.controller", "com.android.htmlviewer",
                "com.android.incallui", "com.android.mms.service", "com.android.nfc",
                "com.android.nfc.resources.overlay.common", "com.android.notes", "com.android.packageinstaller",
                "com.android.permissioncontroller", "com.android.phone", "com.android.photopicker",
                "com.android.providers.downloads", "com.android.intentresolver", "com.android.server.telecom",
                "com.android.server.telecom.resources", "com.android.server.telecomui", "com.android.settings",
                "com.android.systemui", "com.android.vending", "com.android.webapp.service", "com.android.wm.shell",
                "com.anthropic.claude", "com.bbk.account", "com.bbk.appstore", "com.bbk.calendar",
                "com.bbk.cloud", "com.bbk.launcher2", "com.bbk.theme", "com.bbk.updater", "com.deepseek.chat",
                "com.fido.client", "com.gms.android.ncm.overlay.common", "com.google.android.apps.authenticator2",
                "com.google.android.apps.bard", "com.google.android.apps.nbu.files", "com.google.android.apps.photos",
                "com.google.android.apps.subscriptions.red", "com.google.android.apps.translate", "com.google.android.contacts",
                "com.google.android.gms", "com.google.android.gms.policy_sidecar_aps", "com.google.android.keep",
                "com.google.android.permissioncontroller", "com.google.android.packageinstaller",
                "com.ifreedomer.flix", "com.iqoo.powersaving", "com.iqoo.secure", "com.larus.doubaom", "com.larus.nova",
                "com.lemon.lvoverseas", "com.lemon.lv", "com.mobiletools.systemhelper", "com.netease.uuremote",
                "com.openai.chatgpt", "com.os.callservice", "ai.perplexity.app.android", "com.qti.snapdragon.qdcm_ff",
                "com.qti.xdivert", "com.qualcomm.qti.voiceai.lifelogger.service", "com.sec.android.app.myfiles",
                "com.sec.android.gallery3d", "com.sohu.inputmethod.sogou", "com.ss.android.ies.ugc.cam",
                "com.tencent.wetype", "com.unionpay.tsmservice", "com.vivo.Tips", "com.vivo.abe",
                "com.vivo.ai.copilot", "com.vivo.ai.ime.nex", "com.vivo.ai.widget.component", "com.vivo.ai.widget.generate",
                "com.vivo.android.wifi.overlay", "com.vivo.appfilter", "com.vivo.assistant", "com.vivo.card",
                "com.vivo.carlauncher", "com.vivo.cipherchain", "com.vivo.credentialmanager", "com.vivo.daemonService",
                "com.vivo.doubleinstance", "com.vivo.easyshare", "com.vivo.familycare.local", "com.vivo.fileobserver",
                "com.vivo.findphone", "com.vivo.fuelsummary", "com.vivo.gallery", "com.vivo.gamecube",
                "com.vivo.globaldragdrop", "com.vivo.globalsearch", "com.vivo.healthwidget", "com.vivo.hiboard",
                "com.vivo.nfcbaseapp", "com.vivo.permissionmanager", "com.vivo.privacylauncher", "com.vivo.recents",
                "com.vivo.safecenter", "com.vivo.setupwizard", "com.vivo.smartshot", "com.vivo.systemuiplugin",
                "com.vivo.translator", "com.vivo.upnpserver", "com.vivo.vtouch", "com.vivo.xspace",
                "dev.usagetime.app", "io.github.ccch1mneyyy.dsh_app", "net.activitywatch.android", "vendor.qti.data.txpwradmin"
            )
        }

        fun defaultFor(app: String): AppCategory = defaults[app] ?: OTHER

        fun fromKey(key: String?): AppCategory = entries.firstOrNull { it.name == key } ?: OTHER
    }
}
